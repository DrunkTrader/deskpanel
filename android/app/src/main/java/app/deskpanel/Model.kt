package app.deskpanel

import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.sqrt

data class Battery(val percent: Int, val charging: Boolean)
data class Vol(val level: Int, val muted: Boolean)
data class Media(
    val title: String,
    val artist: String,
    val app: String,
    val playing: Boolean,
    val position: Double,
    val duration: Double,
)
data class Pending(val id: String, val tool: String, val detail: String, val expires: Long)

data class SessionStats(
    val tools: Int = 0,
    val edits: Int = 0,
    val commands: Int = 0,
    val reads: Int = 0,
    val searches: Int = 0,
    val prompts: Int = 0,
    val failures: Int = 0,
) {
    val xp: Int get() = (tools * 10 + edits * 15 + commands * 5 + prompts * 25 - failures * 5).coerceAtLeast(0)
    val level: Int get() = floor(sqrt(xp / 40.0)).toInt() + 1
    /** 0..1 progress towards the next level */
    val levelProgress: Float
        get() {
            val lo = 40 * (level - 1) * (level - 1)
            val hi = 40 * level * level
            return ((xp - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        }
}

data class Session(
    val id: String,
    val project: String,
    val title: String,
    val state: String,
    val activity: String,
    val prompt: String,
    val since: Long,
    val started: Long,
    val demo: Boolean,
    val stats: SessionStats,
    val pending: Pending?,
) {
    val name: String get() = project.ifEmpty { title.ifEmpty { "claude" } }
}

data class LyricLine(val t: Long, val x: String)

data class Stats(
    val host: String,
    val cpu: Float,
    val ramUsed: Long,
    val ramTotal: Long,
    val diskUsed: Long,
    val diskTotal: Long,
    val battery: Battery?,
    val netDown: Long,
    val netUp: Long,
    val uptime: Long,
    val volume: Vol?,
    val media: Media?,
    val sessions: List<Session>,
    val demo: Boolean,
    /** laptop clock minus phone clock, ms */
    val clockOffset: Long,
)

enum class Conn { Searching, Connecting, Pairing, Rejected, Connected, Reconnecting }

data class PanelState(
    val conn: Conn = Conn.Searching,
    val address: String? = null,
    val stats: Stats? = null,
    val art: ImageBitmap? = null,
    val lyrics: List<LyricLine>? = null,
    /** SystemClock.elapsedRealtime() when the last media position arrived */
    val mediaAt: Long = 0,
    val downHistory: List<Long> = emptyList(),
    val upHistory: List<Long> = emptyList(),
    val cpuHistory: List<Float> = emptyList(),
)

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

fun parseStats(j: JSONObject): Stats {
    val ram = j.optJSONObject("ram")
    val disk = j.optJSONObject("disk")
    val net = j.optJSONObject("net")
    val sessions = mutableListOf<Session>()
    j.optJSONArray("claude")?.let { arr ->
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val p = s.optJSONObject("pending")
            val st = s.optJSONObject("stats")
            sessions += Session(
                id = s.str("id"),
                project = s.str("project"),
                title = s.str("title"),
                state = s.str("state"),
                activity = s.str("activity"),
                prompt = s.str("prompt"),
                since = s.optLong("since"),
                started = s.optLong("started"),
                demo = s.optBoolean("demo"),
                stats = if (st == null) SessionStats() else SessionStats(
                    st.optInt("tools"), st.optInt("edits"), st.optInt("commands"), st.optInt("reads"),
                    st.optInt("searches"), st.optInt("prompts"), st.optInt("failures"),
                ),
                pending = p?.let { Pending(it.str("id"), it.str("tool"), it.str("detail"), it.optLong("expires")) },
            )
        }
    }
    return Stats(
        host = j.str("host"),
        cpu = j.optDouble("cpu", 0.0).toFloat(),
        ramUsed = ram?.optLong("used") ?: 0,
        ramTotal = ram?.optLong("total") ?: 0,
        diskUsed = disk?.optLong("used") ?: 0,
        diskTotal = disk?.optLong("total") ?: 0,
        battery = j.optJSONObject("battery")?.let { Battery(it.optInt("percent"), it.optBoolean("charging")) },
        netDown = net?.optLong("down") ?: 0,
        netUp = net?.optLong("up") ?: 0,
        uptime = j.optLong("uptime"),
        volume = j.optJSONObject("volume")?.let { Vol(it.optInt("level"), it.optBoolean("muted")) },
        media = j.optJSONObject("media")?.let {
            Media(it.str("title"), it.str("artist"), it.str("app"), it.optBoolean("playing"),
                it.optDouble("position", 0.0), it.optDouble("duration", 0.0))
        },
        sessions = sessions,
        demo = j.optBoolean("demo"),
        clockOffset = if (j.has("now")) j.optLong("now") - System.currentTimeMillis() else 0,
    )
}

fun parseLyrics(j: JSONObject): List<LyricLine>? {
    val arr = j.optJSONArray("lines") ?: return null
    val out = ArrayList<LyricLine>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out += LyricLine(o.optLong("t"), if (o.isNull("x")) "" else o.optString("x"))
    }
    return out.takeIf { it.isNotEmpty() }
}
