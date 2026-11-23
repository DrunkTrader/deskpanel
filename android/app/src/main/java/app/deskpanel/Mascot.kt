package app.deskpanel

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.delay

// A little pixel Claude: 14 x 15 grid (5 rows of headroom for "!", "?", "z" and sparkles, then a 10-row body).
const val MASCOT_W = 14
const val MASCOT_H = 15
private const val TOP = 5

private val BODY = arrayOf(
    "....OOOOOO....",
    "..OOOOOOOOOO..",
    ".OOOOOOOOOOOO.",
    ".OOOOOOOOOOOO.",
    ".OOOOOOOOOOOO.",
    ".OOOOOOOOOOOO.",
    "OOOOOOOOOOOOOO",
    ".OOOOOOOOOOOO.",
    "..DDDDDDDDDD..",
)

data class MascotColors(val body: Color, val shade: Color)

val MascotPalette = listOf(
    MascotColors(ClaudeOrange, Color(0xFFB5583B)),
    MascotColors(Color(0xFF5B8DEF), Color(0xFF3E66B8)),
    MascotColors(Color(0xFF4CC38A), Color(0xFF2F8A5E)),
    MascotColors(Color(0xFFA57AF0), Color(0xFF7650B8)),
    MascotColors(Color(0xFFF06BA8), Color(0xFFB84679)),
    MascotColors(Color(0xFFE8C547), Color(0xFFB0922C)),
)

/** Stable colour per session; the first fighter is always Claude orange. */
fun mascotColors(index: Int): MascotColors = MascotPalette[index.mod(MascotPalette.size)]

fun mascotSpeed(state: String): Long = when (state) {
    "working" -> 220L
    "waiting" -> 300L
    else -> 650L
}

private fun frame(state: String, tick: Int): Pair<Array<CharArray>, Int> {
    val g = Array(MASCOT_H) { CharArray(MASCOT_W) { '.' } }
    fun px(r: Int, c: Int, ch: Char) {
        if (r in 0 until MASCOT_H && c in 0 until MASCOT_W) g[r][c] = ch
    }
    BODY.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> if (ch != '.') px(TOP + r, c, ch) } }

    val walking = state == "working"
    val legs = if (walking && tick % 2 == 1) "..D.D....D.D.." else "...D.D..D.D..."
    legs.forEachIndexed { c, ch -> if (ch != '.') px(TOP + 9, c, ch) }

    val ey = TOP + 3
    fun openEyes(dx: Int, dy: Int) {
        for (x in intArrayOf(3, 9)) {
            px(ey + dy, x + dx, 'W'); px(ey + dy, x + 1 + dx, 'E')
            px(ey + 1 + dy, x + dx, 'E'); px(ey + 1 + dy, x + 1 + dx, 'E')
        }
    }
    fun closedEyes() {
        for (x in intArrayOf(3, 4, 9, 10)) px(ey + 1, x, 'E')
    }
    fun happyEyes() {
        for (x in intArrayOf(4, 10)) {
            px(ey, x, 'E'); px(ey + 1, x - 1, 'E'); px(ey + 1, x + 1, 'E')
        }
    }

    var bounce = 0
    when (state) {
        "working" -> {
            openEyes(dx = if ((tick / 4) % 2 == 0) 0 else 1, dy = 1)
            bounce = if (tick % 2 == 0) 0 else -1
            val dots = tick % 4
            for (i in 0 until dots) px(2, 5 + i * 2, 'B')
        }
        "waiting" -> {
            openEyes(0, 0)
            bounce = if (tick % 2 == 0) -1 else 0
            if (tick % 2 == 0) {
                px(0, 12, 'Y'); px(1, 12, 'Y'); px(2, 12, 'Y'); px(4, 12, 'Y')
            }
        }
        "input" -> {
            if (tick % 8 == 0) closedEyes() else openEyes(0, 0)
            px(0, 10, 'Y'); px(0, 11, 'Y'); px(0, 12, 'Y'); px(1, 12, 'Y'); px(2, 11, 'Y'); px(4, 11, 'Y')
        }
        "done" -> {
            happyEyes()
            val (r, c) = if (tick % 2 == 0) 1 to 2 else 2 to 11
            px(r - 1, c, 'G'); px(r, c - 1, 'G'); px(r, c, 'G'); px(r, c + 1, 'G'); px(r + 1, c, 'G')
        }
        "fight" -> {
            // angry eyes: slanted brows
            for (x in intArrayOf(3, 9)) {
                px(ey, x, 'E'); px(ey, x + 1, 'E'); px(ey + 1, x + 1, 'W')
            }
            bounce = if (tick % 2 == 0) -1 else 0
        }
        else -> { // idle / sleeping
            closedEyes()
            val phase = tick % 2
            val r = 1 - phase
            val c = 9 + phase
            for (i in 0..3) { px(r, c + i, 'Z'); px(r + 3, c + i, 'Z') }
            px(r + 1, c + 2, 'Z')
            px(r + 2, c + 1, 'Z')
        }
    }
    return g to bounce
}

private fun pixelColor(c: Char, colors: MascotColors): Color? = when (c) {
    'O' -> colors.body
    'D' -> colors.shade
    'E' -> Color(0xFF1B1B1F)
    'W' -> Color.White
    'Y' -> Amber
    'G' -> Green
    'Z' -> Color(0xFF9DB4FF)
    'B' -> Color(0xFFF5E6DC)
    else -> null
}

/** Draws the mascot with its top-left corner at (x, y); each sprite pixel is [cell] px. */
fun DrawScope.drawMascot(
    state: String,
    tick: Int,
    x: Float,
    y: Float,
    cell: Float,
    colors: MascotColors = MascotPalette[0],
    flip: Boolean = false,
) {
    val (grid, bounce) = frame(state, tick)
    val oy = y + bounce * cell
    for (r in 0 until MASCOT_H) for (c in 0 until MASCOT_W) {
        val col = pixelColor(grid[r][c], colors) ?: continue
        val cc = if (flip && r >= TOP) MASCOT_W - 1 - c else c
        drawRect(col, Offset(x + cc * cell, oy + r * cell), Size(cell + 0.6f, cell + 0.6f))
    }
}

@Composable
fun Mascot(state: String, modifier: Modifier = Modifier, colors: MascotColors = MascotPalette[0]) {
    val speed = mascotSpeed(state)
    val tick by produceState(0, state) {
        while (true) {
            delay(speed)
            value++
        }
    }
    Canvas(modifier) {
        val cell = minOf(size.width / MASCOT_W, size.height / (MASCOT_H + 1))
        val ox = (size.width - cell * MASCOT_W) / 2
        val oy = (size.height - cell * MASCOT_H) / 2
        drawMascot(state, tick, ox, oy, cell, colors)
    }
}
