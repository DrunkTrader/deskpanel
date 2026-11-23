using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace DeskPanel.Agent;

/// <summary>Answers "DESKPANEL_DISCOVER" broadcasts from the phone so it can find this laptop on the Wi-Fi.</summary>
static class Discovery
{
    public static async Task Run(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var udp = new UdpClient(AddressFamily.InterNetwork);
                udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                // Windows: don't let an ICMP "port unreachable" from a phone that went away break ReceiveAsync.
                const int SIO_UDP_CONNRESET = -1744830452;
                udp.Client.IOControl(SIO_UDP_CONNRESET, [0, 0, 0, 0], null);
                udp.Client.Bind(new IPEndPoint(IPAddress.Any, Program.DiscoveryPort));
                var reply = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
                {
                    app = "deskpanel",
                    name = Environment.MachineName,
                    port = Program.HttpPort,
                }));

                while (!ct.IsCancellationRequested)
                {
                    var r = await udp.ReceiveAsync(ct);
                    if (Encoding.UTF8.GetString(r.Buffer).Trim() == "DESKPANEL_DISCOVER")
                        await udp.SendAsync(reply, r.RemoteEndPoint, ct);
                }
            }
            catch (OperationCanceledException) { return; }
            catch (Exception e)
            {
                Log.Error("discovery", e);
                try { await Task.Delay(3000, ct); } catch (OperationCanceledException) { return; }
            }
        }
    }
}
