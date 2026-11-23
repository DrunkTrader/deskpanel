package app.deskpanel

import java.util.Locale

private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

fun gb(bytes: Long): String = String.format(Locale.US, if (bytes >= 100 * GB) "%.0f GB" else "%.1f GB", bytes / GB)

fun speed(bps: Long): String = when {
    bps < KB -> "$bps B/s"
    bps < MB -> String.format(Locale.US, "%.0f KB/s", bps / KB)
    else -> String.format(Locale.US, "%.1f MB/s", bps / MB)
}

fun dur(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun uptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

fun stateLabel(state: String): String = when (state) {
    "working" -> "Working"
    "waiting" -> "Needs permission"
    "input" -> "Waiting for you"
    "done" -> "Done"
    else -> "Idle"
}
