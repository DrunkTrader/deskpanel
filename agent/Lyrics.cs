using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace DeskPanel.Agent;

sealed record LyricLine(long T, string X);

/// <summary>
/// Time-synced lyrics for whatever is playing, from LRCLIB (free, no key). Only the cleaned-up song title and
/// artist are sent. Results are cached per track; when nothing is found the phone shows its time-lapse instead.
/// </summary>
sealed partial class Lyrics
{
    static readonly HttpClient Http = CreateClient();
    readonly object gate = new();
    readonly Dictionary<string, List<LyricLine>?> cache = new();
    string? currentKey;

    public int Version { get; private set; }
    public List<LyricLine>? Lines { get; private set; }
    public event Action? Changed;

    static HttpClient CreateClient()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("DeskPanel/1.0 (desk panel lyrics)");
        return c;
    }

    public void Track(string title, string artist, double duration)
    {
        var key = $"{title}|{artist}";
        lock (gate)
        {
            if (key == currentKey) return;
            currentKey = key;
            if (cache.TryGetValue(key, out var cached)) { Lines = cached; Version++; return; }
            Lines = null;
            Version++;
        }
        _ = Task.Run(async () =>
        {
            List<LyricLine>? lines = null;
            try { lines = await Fetch(title, artist, duration); }
            catch (Exception e) { Log.Error("lyrics", e); }
            lock (gate)
            {
                cache[key] = lines;
                if (currentKey != key) return;
                Lines = lines;
                Version++;
            }
            Log.Info($"lyrics for \"{title}\": {(lines == null ? "none" : lines.Count + " lines")}");
            Changed?.Invoke();
        });
    }

    public void Clear()
    {
        lock (gate)
        {
            if (currentKey == null) return;
            currentKey = null;
            Lines = null;
            Version++;
        }
    }

    static async Task<List<LyricLine>?> Fetch(string rawTitle, string rawArtist, double duration)
    {
        var (artist, track) = Clean(rawTitle, rawArtist);
        if (track.Length == 0) return null;

        var urls = new List<string>();
        if (artist.Length > 0)
            urls.Add($"https://lrclib.net/api/search?track_name={Uri.EscapeDataString(track)}&artist_name={Uri.EscapeDataString(artist)}");
        urls.Add($"https://lrclib.net/api/search?q={Uri.EscapeDataString((artist + " " + track).Trim())}");
        if (artist.Length > 0) urls.Add($"https://lrclib.net/api/search?q={Uri.EscapeDataString(track)}");

        foreach (var url in urls)
        {
            using var doc = JsonDocument.Parse(await Http.GetStringAsync(url));
            JsonElement? best = null;
            double bestDiff = double.MaxValue;
            foreach (var r in doc.RootElement.EnumerateArray())
            {
                if (!r.TryGetProperty("syncedLyrics", out var sl) || sl.ValueKind != JsonValueKind.String) continue;
                double d = r.TryGetProperty("duration", out var du) && du.ValueKind == JsonValueKind.Number ? du.GetDouble() : 0;
                double diff = duration > 0 && d > 0 ? Math.Abs(d - duration) : 0;
                if (duration > 0 && d > 0 && diff > 20) continue; // a different version of the song
                if (diff < bestDiff) { bestDiff = diff; best = r; }
            }
            if (best is { } b)
            {
                var lines = ParseLrc(b.GetProperty("syncedLyrics").GetString() ?? "");
                if (lines.Count > 0) return lines;
            }
        }
        return null;
    }

    public static (string artist, string track) Clean(string title, string artist)
    {
        var t = title;
        t = Brackets().Replace(t, " ");
        t = Pipes().Split(t)[0];
        t = Noise().Replace(t, " ");
        t = Feat().Replace(t, "");
        var a = artist;
        var m = Dash().Match(t);
        if (m.Success) { a = m.Groups[1].Value; t = m.Groups[2].Value; }
        a = Topic().Replace(a, "");
        a = ArtistSplit().Split(a)[0];
        return (Spaces().Replace(a, " ").Trim(), Spaces().Replace(t, " ").Trim(' ', '-', '"', '\''));
    }

    static List<LyricLine> ParseLrc(string lrc)
    {
        var list = new List<LyricLine>();
        foreach (var raw in lrc.Split('\n'))
        {
            var line = raw.Trim();
            var stamps = Stamp().Matches(line);
            if (stamps.Count == 0) continue;
            var text = Stamp().Replace(line, "").Trim();
            foreach (Match s in stamps)
            {
                var ms = (long)(int.Parse(s.Groups[1].Value) * 60000
                    + double.Parse(s.Groups[2].Value, CultureInfo.InvariantCulture) * 1000);
                list.Add(new LyricLine(ms, text));
            }
        }
        list.Sort((x, y) => x.T.CompareTo(y.T));
        return list;
    }

    [GeneratedRegex(@"\s*[\(\[【].*?[\)\]】]")] private static partial Regex Brackets();
    [GeneratedRegex(@"\s+[|/]{1,2}\s+")] private static partial Regex Pipes();
    [GeneratedRegex(@"(?i)\b(official\s+(music\s+)?(video|audio|lyrics?\s+video)|lyric(al)?\s+(video|visuali[sz]er)|lyrics?|full\s+(video|song|audio)|audio|video|hd|4k|prod\.?\s.*$)")] private static partial Regex Noise();
    [GeneratedRegex(@"(?i)\s+(ft\.?|feat\.?|featuring)\s+.*$")] private static partial Regex Feat();
    [GeneratedRegex(@"^(.*?)\s+[-–—]\s+(.*)$")] private static partial Regex Dash();
    [GeneratedRegex(@"(?i)\s*-\s*topic$|vevo$")] private static partial Regex Topic();
    [GeneratedRegex(@"\s*(,|&|\sx\s|\sand\s)\s*")] private static partial Regex ArtistSplit();
    [GeneratedRegex(@"\s+")] private static partial Regex Spaces();
    [GeneratedRegex(@"\[(\d+):(\d+(?:\.\d+)?)\]")] private static partial Regex Stamp();
}
