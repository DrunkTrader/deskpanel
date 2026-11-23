using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace DeskPanel.Agent;

sealed class Client(WebSocket ws)
{
    public WebSocket Ws { get; } = ws;
    public SemaphoreSlim SendLock { get; } = new(1, 1);
    public int ArtSent = -1;
    public int LyricsSent = -1;
}

/// <summary>Keeps the connected (paired) phones and pushes the laptop's state to them once a second.</summary>
sealed class Hub
{
    static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    const int MaxMessage = 64 * 1024;

    readonly ConcurrentDictionary<Client, byte> clients = new();
    readonly SystemStats stats = new();
    readonly MediaSource media = new();
    readonly Lyrics lyrics = new();
    readonly SemaphoreSlim buildLock = new(1, 1);
    StatsSample? lastStats;
    int pushQueued;

    public ClaudeTracker Claude { get; } = new();
    public int ClientCount => clients.Count;

    sealed record Snapshot(string StateJson, int ArtVersion, string? Art, int LyricsVersion, string LyricsJson);

    public Hub()
    {
        Claude.Changed += PushSoon;
        lyrics.Changed += PushSoon;
    }

    public async Task Serve(WebSocket ws, IPAddress? remote, CancellationToken ct)
    {
        var c = new Client(ws);
        var buf = new byte[8192];
        using var ms = new MemoryStream();
        try
        {
            // 1. The phone introduces itself with its key; unknown phones must be approved on the laptop once.
            string? helloText;
            using (var helloTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                helloTimeout.CancelAfter(TimeSpan.FromSeconds(10));
                helloText = await ReceiveText(ws, buf, ms, helloTimeout.Token);
            }
            if (helloText == null || JsonNode.Parse(helloText) is not JsonObject hello || (string?)hello["cmd"] != "hello") return;

            var token = (string?)hello["token"];
            if (!Pairing.IsKnown(token))
            {
                var name = Trunc((string?)hello["name"] ?? "Phone", 40);
                await Send(c, JsonSerializer.Serialize(new { type = "pairing" }, Json));
                var newToken = await Pairing.Ask(name, remote?.MapToIPv4().ToString() ?? "?");
                if (newToken == null)
                {
                    await Send(c, JsonSerializer.Serialize(new { type = "rejected" }, Json));
                    return;
                }
                await Send(c, JsonSerializer.Serialize(new { type = "paired", token = newToken }, Json));
            }

            // 2. Paired: stream the state, accept commands.
            clients[c] = 0;
            Log.Info($"phone connected from {remote} ({clients.Count} total)");
            await PushTo(c, await Build(sampleStats: true));
            while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                var text = await ReceiveText(ws, buf, ms, ct);
                if (text == null) break;
                await Handle(text);
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or JsonException) { }
        catch (Exception e) { Log.Error("client", e); }
        finally
        {
            if (clients.TryRemove(c, out _)) Log.Info($"phone disconnected ({clients.Count} left)");
            if (ws.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                try { await ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, CancellationToken.None); } catch { }
            }
        }
    }

    static async Task<string?> ReceiveText(WebSocket ws, byte[] buf, MemoryStream ms, CancellationToken ct)
    {
        ms.SetLength(0);
        while (true)
        {
            var r = await ws.ReceiveAsync(buf, ct);
            if (r.MessageType == WebSocketMessageType.Close) return null;
            ms.Write(buf, 0, r.Count);
            if (ms.Length > MaxMessage) return null;
            if (r.EndOfMessage) return Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length);
        }
    }

    public async Task Loop(CancellationToken ct)
    {
        int tick = 0;
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (tick++ % 2 == 0) Claude.Scan();
                if (!clients.IsEmpty) await Broadcast(await Build(sampleStats: true));
            }
            catch (Exception e) { Log.Error("loop", e); }
            try { await Task.Delay(1000, ct); } catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>Push right away (coalesced), e.g. when a Claude session changes.</summary>
    void PushSoon()
    {
        if (clients.IsEmpty || Interlocked.Exchange(ref pushQueued, 1) == 1) return;
        _ = Task.Run(async () =>
        {
            await Task.Delay(50);
            Interlocked.Exchange(ref pushQueued, 0);
            try { await Broadcast(await Build(sampleStats: false)); } catch (Exception e) { Log.Error("push", e); }
        });
    }

    async Task Handle(string text)
    {
        JsonNode? n;
        try { n = JsonNode.Parse(text); } catch { return; }
        if (n is not JsonObject o) return;

        switch ((string?)o["cmd"])
        {
            case "media":
                await media.Command((string?)o["action"] ?? "");
                await Task.Delay(250); // give the player a moment to report its new state
                break;
            case "volume":
                if (o["level"] is JsonValue lv && lv.TryGetValue<double>(out var level))
                    Volume.SetLevel((int)Math.Round(Math.Clamp(level, 0, 100)));
                break;
            case "mute":
                if (o["muted"] is JsonValue mv && mv.TryGetValue<bool>(out var muted))
                    Volume.SetMute(muted);
                break;
            case "claude":
                Claude.Decide((string?)o["id"] ?? "", (string?)o["decision"] ?? "");
                return; // Decide triggers its own push
            case "demo":
                if (o["on"] is JsonValue dv && dv.TryGetValue<bool>(out var on)) Claude.SetDemo(on);
                return;
            default:
                return;
        }
        await Broadcast(await Build(sampleStats: false));
    }

    async Task<Snapshot> Build(bool sampleStats)
    {
        await buildLock.WaitAsync();
        try
        {
            if (sampleStats || lastStats == null) lastStats = stats.Sample();
            var s = lastStats;

            MediaInfo? m = null;
            try { m = await media.Get(); } catch (Exception e) { Log.Error("media", e); }
            if (m != null && m.Title.Length > 0) lyrics.Track(m.Title, m.Artist, m.Duration);
            else lyrics.Clear();
            var vol = Volume.Get();

            var state = new
            {
                type = "state",
                host = Environment.MachineName,
                cpu = Math.Round(s.Cpu, 1),
                ram = new { used = s.RamUsed, total = s.RamTotal },
                disk = new { used = s.DiskUsed, total = s.DiskTotal },
                battery = s.BatteryPercent is int p ? new { percent = p, charging = s.Charging } : null,
                net = new { down = s.NetDown, up = s.NetUp },
                uptime = s.UptimeSec,
                volume = vol is { } v ? new { level = v.Level, muted = v.Muted } : null,
                media = m is null ? null : new { m.Title, m.Artist, m.App, m.Playing, m.Position, m.Duration },
                artVersion = media.ArtVersion,
                claude = Claude.Snapshot(),
                demo = Claude.DemoEnabled,
                lyricsVersion = lyrics.Version,
                now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            };
            var lyricsJson = JsonSerializer.Serialize(new { type = "lyrics", version = lyrics.Version, lines = lyrics.Lines }, Json);
            return new Snapshot(JsonSerializer.Serialize(state, Json), media.ArtVersion, media.ArtBase64, lyrics.Version, lyricsJson);
        }
        finally { buildLock.Release(); }
    }

    Task Broadcast(Snapshot s) => Task.WhenAll(clients.Keys.Select(c => PushTo(c, s)));

    static async Task PushTo(Client c, Snapshot s)
    {
        await c.SendLock.WaitAsync();
        try
        {
            if (c.Ws.State != WebSocketState.Open) return;
            if (c.ArtSent != s.ArtVersion)
            {
                await SendText(c.Ws, JsonSerializer.Serialize(new { type = "art", version = s.ArtVersion, data = s.Art }, Json));
                c.ArtSent = s.ArtVersion;
            }
            if (c.LyricsSent != s.LyricsVersion)
            {
                await SendText(c.Ws, s.LyricsJson);
                c.LyricsSent = s.LyricsVersion;
            }
            await SendText(c.Ws, s.StateJson);
        }
        catch
        {
            c.Ws.Abort();
        }
        finally { c.SendLock.Release(); }
    }

    static async Task Send(Client c, string text)
    {
        await c.SendLock.WaitAsync();
        try { await SendText(c.Ws, text); }
        finally { c.SendLock.Release(); }
    }

    static async Task SendText(WebSocket ws, string text)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        await ws.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, timeout.Token);
    }

    static string Trunc(string s, int max) => s.Length <= max ? s : s[..max];
}
