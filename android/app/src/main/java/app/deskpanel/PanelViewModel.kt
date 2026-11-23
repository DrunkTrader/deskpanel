package app.deskpanel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PanelViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val DISCOVERY_PORT = 47810
        const val DEFAULT_PORT = 47811
        const val HISTORY = 60
    }

    private val _state = MutableStateFlow(PanelState())
    val state: StateFlow<PanelState> = _state.asStateFlow()

    private val prefs = app.getSharedPreferences("deskpanel", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    private var loop: Job? = null
    @Volatile private var rejected = false
    private val retrySignal = MutableStateFlow(0)
    private val seenPending = mutableSetOf<String>()
    private var trackKey = ""
    private var localPosMs = 0L
    private var lastStateAt = 0L

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch(Dispatchers.IO) { connectLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.cancel()
        socket = null
    }

    fun retryPairing() {
        rejected = false
        _state.update { it.copy(conn = Conn.Searching) }
        retrySignal.value++
    }

    fun useAddress(ip: String) {
        prefs.edit().putString("host", ip.trim()).putInt("port", DEFAULT_PORT).apply()
        stop()
        start()
    }

    // ---------------------------------------------------------------- commands

    fun media(action: String) = send(JSONObject().put("cmd", "media").put("action", action))

    fun mute(muted: Boolean) {
        _state.update { s -> s.copy(stats = s.stats?.let { st -> st.copy(volume = st.volume?.copy(muted = muted)) }) }
        send(JSONObject().put("cmd", "mute").put("muted", muted))
    }

    private var volumeJob: Job? = null
    @Volatile private var pendingVolume = -1
    private var lastVolumeSend = 0L

    /** Sends at most ~16 updates a second while the slider is dragged; the last value always goes out. */
    fun setVolume(level: Int) {
        pendingVolume = level
        if (volumeJob?.isActive == true) return
        volumeJob = viewModelScope.launch {
            val wait = 60 - (SystemClock.uptimeMillis() - lastVolumeSend)
            if (wait > 0) delay(wait)
            lastVolumeSend = SystemClock.uptimeMillis()
            send(JSONObject().put("cmd", "volume").put("level", pendingVolume))
        }
    }

    fun decide(pendingId: String, decision: String) {
        _state.update { s ->
            s.copy(stats = s.stats?.let { st ->
                st.copy(sessions = st.sessions.map { if (it.pending?.id == pendingId) it.copy(pending = null) else it })
            })
        }
        send(JSONObject().put("cmd", "claude").put("id", pendingId).put("decision", decision))
    }

    fun demo(on: Boolean) = send(JSONObject().put("cmd", "demo").put("on", on))

    private fun send(o: JSONObject) {
        socket?.send(o.toString())
    }

    // ---------------------------------------------------------------- connection

    private suspend fun connectLoop() {
        while (currentCoroutineContext().isActive) {
            if (rejected) {
                val v = retrySignal.value
                retrySignal.first { it != v }
                continue
            }
            val found = discover()
            val saved = prefs.getString("host", null)?.let { it to prefs.getInt("port", DEFAULT_PORT) }
            val target = found ?: saved
            if (target == null) {
                markWaiting()
                delay(1500)
                continue
            }
            _state.update {
                if (it.conn == Conn.Pairing || it.conn == Conn.Connected) it
                else it.copy(conn = if (it.stats == null) Conn.Connecting else Conn.Reconnecting, address = target.first)
            }
            val opened = runSession(target.first, target.second)
            if (!rejected) markWaiting()
            delay(if (opened) 500 else 1500)
        }
    }

    private fun markWaiting() = _state.update {
        if (it.conn == Conn.Rejected) it
        else it.copy(conn = if (it.stats == null) Conn.Searching else Conn.Reconnecting)
    }

    private suspend fun runSession(host: String, port: Int): Boolean {
        val done = CompletableDeferred<Unit>()
        val opened = AtomicBoolean(false)
        val request = Request.Builder().url("ws://$host:$port/ws").build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                prefs.edit().putString("host", host).putInt("port", port).apply()
                val hello = JSONObject()
                    .put("cmd", "hello")
                    .put("token", prefs.getString("token", "") ?: "")
                    .put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(Unit)
            }
        })
        socket = ws
        try {
            done.await()
        } finally {
            ws.cancel()
            if (socket === ws) socket = null
        }
        return opened.get()
    }

    private fun onText(text: String) {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (j.optString("type")) {
            "pairing" -> _state.update { it.copy(conn = Conn.Pairing) }
            "paired" -> {
                prefs.edit().putString("token", j.optString("token")).apply()
                _state.update { it.copy(conn = Conn.Connected) }
            }
            "rejected" -> {
                rejected = true
                _state.update { it.copy(conn = Conn.Rejected) }
            }
            "art" -> {
                val bmp = if (j.isNull("data")) null else runCatching {
                    val bytes = Base64.decode(j.optString("data"), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { full ->
                        // shrink to 40x40 and draw it without smoothing: instant pixel art
                        Bitmap.createScaledBitmap(full, 40, 40, true).asImageBitmap()
                    }
                }.getOrNull()
                _state.update { it.copy(art = bmp) }
            }
            "lyrics" -> _state.update { it.copy(lyrics = parseLyrics(j)) }
            "state" -> {
                val parsed = parseStats(j)
                buzzOnNewPermission(parsed)
                // Some players (installed web apps) don't report a position: count it ourselves from the
                // moment the track appeared, pausing while it's paused, so lyrics still scroll.
                val now = SystemClock.elapsedRealtime()
                var media = parsed.media
                if (media != null) {
                    val key = media.title + "|" + media.artist
                    if (key != trackKey) {
                        trackKey = key
                        localPosMs = 0
                    } else if (media.playing && lastStateAt > 0) {
                        localPosMs += now - lastStateAt
                    }
                    if (media.duration <= 0) media = media.copy(position = localPosMs / 1000.0)
                } else {
                    trackKey = ""
                }
                lastStateAt = now
                val s = parsed.copy(media = media)
                _state.update { st ->
                    st.copy(
                        conn = Conn.Connected,
                        stats = s,
                        mediaAt = now,
                        downHistory = (st.downHistory + s.netDown).takeLast(HISTORY),
                        upHistory = (st.upHistory + s.netUp).takeLast(HISTORY),
                        cpuHistory = (st.cpuHistory + s.cpu).takeLast(HISTORY),
                    )
                }
            }
        }
    }

    private fun buzzOnNewPermission(s: Stats) {
        val ids = s.sessions.mapNotNull { it.pending?.id }
        val fresh = ids.any { it !in seenPending }
        seenPending.retainAll(ids.toSet())
        seenPending.addAll(ids)
        if (!fresh) return
        runCatching {
            val ctx = getApplication<Application>()
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 90, 120), -1))
        }
    }

    // ---------------------------------------------------------------- discovery

    private fun wifiNetwork(cm: ConnectivityManager): Network? {
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** Broadcasts "DESKPANEL_DISCOVER" on the Wi-Fi; the laptop answers with its port. */
    private fun discover(): Pair<String, Int>? {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val wifi = wifiNetwork(cm)
        // Talk over Wi-Fi even if Android prefers mobile data for the internet.
        if (wifi != null && cm.boundNetworkForProcess != wifi) cm.bindProcessToNetwork(wifi)

        val targets = mutableListOf<InetAddress>()
        (wifi ?: cm.activeNetwork)?.let { cm.getLinkProperties(it) }?.linkAddresses?.forEach { la ->
            val a = la.address as? Inet4Address ?: return@forEach
            val ip = ByteBuffer.wrap(a.address).int
            val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
            targets += InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or mask.inv()).array())
        }
        targets += InetAddress.getByName("255.255.255.255")

        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 700
                val msg = "DESKPANEL_DISCOVER".toByteArray()
                repeat(3) {
                    for (t in targets) runCatching { sock.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT)) }
                    val buf = ByteArray(1024)
                    val deadline = SystemClock.uptimeMillis() + 700
                    while (SystemClock.uptimeMillis() < deadline) {
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            sock.receive(pkt)
                        } catch (e: SocketTimeoutException) {
                            break
                        }
                        val j = runCatching { JSONObject(String(pkt.data, 0, pkt.length)) }.getOrNull() ?: continue
                        val addr = pkt.address?.hostAddress ?: continue
                        if (j.optString("app") == "deskpanel") return addr to j.optInt("port", DEFAULT_PORT)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
