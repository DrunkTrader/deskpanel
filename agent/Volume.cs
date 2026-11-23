using System.Runtime.InteropServices;

namespace DeskPanel.Agent;

/// <summary>Master volume of the default playback device, through Windows Core Audio.</summary>
static class Volume
{
    public readonly record struct State(int Level, bool Muted);

    public static State? Get()
    {
        try
        {
            return Use(ep =>
            {
                ep.GetMasterVolumeLevelScalar(out float level);
                ep.GetMute(out bool muted);
                return new State((int)Math.Round(level * 100), muted);
            });
        }
        catch (Exception e)
        {
            Log.Error("volume read", e);
            return null;
        }
    }

    public static void SetLevel(int level)
    {
        try
        {
            Use(ep =>
            {
                var ctx = Guid.Empty;
                ep.SetMasterVolumeLevelScalar(Math.Clamp(level, 0, 100) / 100f, ref ctx);
                if (level > 0)
                {
                    ep.GetMute(out bool muted);
                    if (muted) ep.SetMute(false, ref ctx);
                }
                return 0;
            });
        }
        catch (Exception e) { Log.Error("volume set", e); }
    }

    public static void SetMute(bool muted)
    {
        try
        {
            Use(ep =>
            {
                var ctx = Guid.Empty;
                ep.SetMute(muted, ref ctx);
                return 0;
            });
        }
        catch (Exception e) { Log.Error("mute", e); }
    }

    static T Use<T>(Func<IAudioEndpointVolume, T> f)
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
        IMMDevice? device = null;
        IAudioEndpointVolume? ep = null;
        try
        {
            enumerator.GetDefaultAudioEndpoint(0 /* eRender */, 1 /* eMultimedia */, out device);
            var iid = typeof(IAudioEndpointVolume).GUID;
            device.Activate(ref iid, 23 /* CLSCTX_ALL */, IntPtr.Zero, out object o);
            ep = (IAudioEndpointVolume)o;
            return f(ep);
        }
        finally
        {
            if (ep != null) Marshal.ReleaseComObject(ep);
            if (device != null) Marshal.ReleaseComObject(device);
            Marshal.ReleaseComObject(enumerator);
        }
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumeratorCom { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        void EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        void GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        void Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    }

    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioEndpointVolume
    {
        void RegisterControlChangeNotify(IntPtr notify);
        void UnregisterControlChangeNotify(IntPtr notify);
        void GetChannelCount(out uint count);
        void SetMasterVolumeLevel(float levelDb, ref Guid eventContext);
        void SetMasterVolumeLevelScalar(float level, ref Guid eventContext);
        void GetMasterVolumeLevel(out float levelDb);
        void GetMasterVolumeLevelScalar(out float level);
        void SetChannelVolumeLevel(uint channel, float levelDb, ref Guid eventContext);
        void SetChannelVolumeLevelScalar(uint channel, float level, ref Guid eventContext);
        void GetChannelVolumeLevel(uint channel, out float levelDb);
        void GetChannelVolumeLevelScalar(uint channel, out float level);
        void SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid eventContext);
        void GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
