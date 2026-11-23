using System.Net.NetworkInformation;
using System.Runtime.InteropServices;

namespace DeskPanel.Agent;

sealed record StatsSample(
    double Cpu,
    ulong RamUsed, ulong RamTotal,
    long DiskUsed, long DiskTotal,
    int? BatteryPercent, bool Charging,
    long NetDown, long NetUp,
    long UptimeSec);

sealed class SystemStats
{
    long prevIdle, prevKernel, prevUser;
    long prevRx, prevTx;
    long prevTicks;

    public StatsSample Sample()
    {
        // CPU: share of non-idle time since the last sample.
        double cpu = 0;
        if (GetSystemTimes(out var idle, out var kernel, out var user))
        {
            long dIdle = idle - prevIdle, dTotal = (kernel - prevKernel) + (user - prevUser);
            if (prevKernel != 0 && dTotal > 0) cpu = Math.Clamp(100.0 * (dTotal - dIdle) / dTotal, 0, 100);
            prevIdle = idle; prevKernel = kernel; prevUser = user;
        }

        var mem = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
        GlobalMemoryStatusEx(ref mem);

        long diskUsed = 0, diskTotal = 0;
        try
        {
            var d = new DriveInfo(Path.GetPathRoot(Environment.SystemDirectory) ?? "C:\\");
            diskTotal = d.TotalSize;
            diskUsed = d.TotalSize - d.TotalFreeSpace;
        }
        catch { }

        int? battery = null;
        bool charging = false;
        if (GetSystemPowerStatus(out var ps) && (ps.BatteryFlag & 128) == 0 && ps.BatteryLifePercent <= 100)
        {
            battery = ps.BatteryLifePercent;
            charging = ps.ACLineStatus == 1;
        }

        // Network: bytes per second across the real adapters (not VPN / virtual ones).
        long rx = 0, tx = 0;
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is not (NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.GigabitEthernet)) continue;
            var desc = ni.Description + " " + ni.Name;
            if (VirtualMarkers.Any(m => desc.Contains(m, StringComparison.OrdinalIgnoreCase))) continue;
            try
            {
                var st = ni.GetIPStatistics();
                rx += st.BytesReceived;
                tx += st.BytesSent;
            }
            catch { }
        }
        long now = Environment.TickCount64;
        long down = 0, up = 0;
        if (prevTicks != 0 && now > prevTicks)
        {
            double secs = (now - prevTicks) / 1000.0;
            down = Math.Max(0, (long)((rx - prevRx) / secs));
            up = Math.Max(0, (long)((tx - prevTx) / secs));
        }
        prevRx = rx; prevTx = tx; prevTicks = now;

        return new StatsSample(
            cpu,
            mem.ullTotalPhys - mem.ullAvailPhys, mem.ullTotalPhys,
            diskUsed, diskTotal,
            battery, charging,
            down, up,
            now / 1000);
    }

    static readonly string[] VirtualMarkers = ["virtual", "warp", "vpn", "hyper-v", "vethernet", "tap-", "wintun", "loopback", "bluetooth", "wan miniport", "tailscale"];

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetSystemTimes(out long idleTime, out long kernelTime, out long userTime);

    [StructLayout(LayoutKind.Sequential)]
    struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX buffer);

    [StructLayout(LayoutKind.Sequential)]
    struct SYSTEM_POWER_STATUS
    {
        public byte ACLineStatus;
        public byte BatteryFlag;
        public byte BatteryLifePercent;
        public byte SystemStatusFlag;
        public int BatteryLifeTime;
        public int BatteryFullLifeTime;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS status);
}
