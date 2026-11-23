package app.deskpanel

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameMillis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val HitWords = listOf("POW!", "BAM!", "ZAP!", "BONK!", "KAPOW!", "WHAM!", "CRIT!", "BOOM!")

private class Crowd(val x: List<Float>, val row: List<Int>, val color: List<Color>, val phase: List<Float>)

/**
 * The Claude arena: every open session is a pixel mascot. Sessions that are working pair up and brawl
 * (whoever is busy throws punches), the ones waiting for you jump and shout, finished ones celebrate,
 * idle ones nap. Name, level and an XP bar float above each fighter.
 */
@Composable
fun Arena(sessions: List<Session>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val time by produceState(0L) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { value = it - start }
    }
    val crowd = remember {
        val r = Random(7)
        val colors = listOf(ClaudeOrange, Blue, Green, Purple, Pink, Amber, Teal, Color(0xFFCCCCCC))
        val n = 90
        Crowd(
            x = List(n) { r.nextFloat() },
            row = List(n) { r.nextInt(3) },
            color = List(n) { colors[r.nextInt(colors.size)] },
            phase = List(n) { r.nextFloat() * 6.28f },
        )
    }
    val nameStyle = remember(density) { TextStyle(fontFamily = PixelHead, fontSize = 7.sp, color = TextMain) }
    val hitStyle = remember(density) { TextStyle(fontFamily = PixelHead, fontSize = 13.sp, color = Amber) }
    val hitShadow = remember(density) { TextStyle(fontFamily = PixelHead, fontSize = 13.sp, color = Color.Black) }

    Canvas(modifier) {
        val t = time / 1000f
        val fighters = sessions.take(6)
        val anyFight = fighters.count { it.state == "working" } >= 2
        drawBackdrop(t, crowd, anyFight)

        val floorY = size.height * 0.86f
        if (fighters.isEmpty()) {
            val cell = size.height / 30f
            val x = size.width / 2 - MASCOT_W * cell / 2
            drawMascot("idle", (t * 1000 / 650).toInt(), x, floorY - MASCOT_H * cell, cell)
            return@Canvas
        }

        val n = fighters.size
        val cell = minOf(size.width / (n * 20f), size.height / 34f)
        val spriteW = MASCOT_W * cell
        val spriteH = MASCOT_H * cell
        val slot = size.width / n
        val centers = FloatArray(n) { (it + 0.5f) * slot }
        val xs = centers.copyOf()
        val ys = FloatArray(n) { 0f }
        val states = Array(n) { fighters[it].state }
        val flips = BooleanArray(n)

        // pair up working sessions that sit next to each other in the list
        val working = fighters.indices.filter { fighters[it].state == "working" }
        val hits = mutableListOf<Triple<Float, Float, Int>>() // x, y, word index
        var k = 0
        var i = 0
        while (i < working.size) {
            val a = working[i]
            val b = working.getOrNull(i + 1)
            if (b == null) {
                // nobody to fight: shadow boxing
                xs[a] = centers[a] + sin(t * 3f + a) * cell * 3
                ys[a] = -abs(sin(t * 7f + a)) * cell * 2
                states[a] = "working"
                i++
                continue
            }
            val period = 1.7f
            val local = t + k * 0.63f
            val p = (local % period) / period
            val round = (local / period).toInt()
            val mid = (centers[a] + centers[b]) / 2
            val reach = (mid - centers[a] - spriteW * 0.55f).coerceAtLeast(0f)
            val approach = when {
                p < 0.42f -> ease(p / 0.42f)
                p < 0.58f -> 1f
                else -> 1f - ease((p - 0.58f) / 0.42f)
            }
            // the busier fighter lunges further
            val aBoost = if (fighters[a].stats.tools >= fighters[b].stats.tools) 1.08f else 0.92f
            xs[a] = centers[a] + reach * approach * aBoost
            xs[b] = centers[b] - reach * approach * (2f - aBoost)
            flips[b] = true
            states[a] = "fight"; states[b] = "fight"
            if (p in 0.42f..0.6f) {
                val shake = sin(t * 80f) * cell * 0.6f
                xs[a] += shake; xs[b] -= shake
                hits += Triple((xs[a] + xs[b]) / 2, floorY - spriteH * 0.55f, round + k)
            } else {
                ys[a] = -abs(sin(t * 9f)) * cell
                ys[b] = -abs(sin(t * 9f + 1f)) * cell
            }
            k++
            i += 2
        }

        for (j in 0 until n) {
            when (fighters[j].state) {
                "waiting" -> ys[j] = -abs(sin(t * 6f + j)) * cell * 5
                "input" -> ys[j] = -abs(sin(t * 3f + j)) * cell * 1.5f
                "done" -> {
                    ys[j] = -abs(sin(t * 4f + j)) * cell * 3
                    if (n <= 3) xs[j] = centers[j] + sin(t * 2f + j) * cell * 2
                }
            }
        }

        for (j in 0 until n) {
            val s = fighters[j]
            val x = xs[j] - spriteW / 2
            val y = floorY - spriteH + ys[j]
            // shadow
            val shadowW = spriteW * (0.8f - (-ys[j] / (cell * 12f)).coerceIn(0f, 0.4f))
            drawRect(Color.Black.copy(alpha = 0.35f), Offset(xs[j] - shadowW / 2, floorY - cell * 0.3f), Size(shadowW, cell * 0.8f))
            val tick = (time / mascotSpeed(states[j])).toInt()
            drawMascot(states[j], tick, x, y, cell, mascotColors(j), flips[j])
            if (s.state == "done") drawCrown(xs[j], y + 4 * cell, cell)
            // with many fighters, alternate tag heights so neighbours don't overlap
            val lift = if (n > 3 && j % 2 == 1) cell * 7 else 0f
            drawNameTag(measurer, nameStyle, s, j, xs[j], y + 2 * cell - lift, cell, if (n > 4) 9 else 12)
        }

        hits.forEach { (hx, hy, round) -> drawHit(measurer, hitStyle, hitShadow, hx, hy, round, t, cell) }
    }
}

private fun ease(x: Float): Float = (1 - cos(x.coerceIn(0f, 1f) * PI.toFloat())) / 2

private fun DrawScope.drawBackdrop(t: Float, crowd: Crowd, excited: Boolean) {
    val px = size.height / 60f
    // back wall: stepped gradient bands
    val wall = listOf(Color(0xFF10131D), Color(0xFF131826), Color(0xFF171D2E), Color(0xFF1B2236))
    val bandH = size.height * 0.62f / wall.size
    wall.forEachIndexed { i, c -> drawRect(c, Offset(0f, i * bandH), Size(size.width, bandH + 1)) }
    // spotlights
    for (s in 0 until 3) {
        val cx = size.width * (0.2f + 0.3f * s) + sin(t * 0.7f + s * 2) * size.width * 0.08f
        val beamW = px * 10
        drawRect(Color.White.copy(alpha = 0.03f), Offset(cx - beamW / 2, 0f), Size(beamW, size.height * 0.86f))
        drawRect(Color.White.copy(alpha = 0.03f), Offset(cx - beamW, 0f), Size(beamW * 2, size.height * 0.86f))
    }
    // crowd: little pixel heads that bounce harder when there's a fight
    val crowdTop = size.height * 0.14f
    for (i in crowd.x.indices) {
        val row = crowd.row[i]
        val bob = if (excited) abs(sin(t * 8f + crowd.phase[i])) * px * 2 else abs(sin(t * 1.5f + crowd.phase[i])) * px * 0.5f
        val hx = (crowd.x[i] * size.width / px).toInt() * px
        val hy = crowdTop + row * px * 5 - bob
        drawRect(crowd.color[i].copy(alpha = 0.55f - row * 0.12f), Offset(hx, hy), Size(px * 2, px * 2))
        drawRect(crowd.color[i].copy(alpha = 0.35f - row * 0.08f), Offset(hx - px * 0.5f, hy + px * 2), Size(px * 3, px * 2))
    }
    // ring ropes
    val ropeY = size.height * 0.58f
    for (r in 0 until 3) drawRect(Color(0xFF8B2E3C).copy(alpha = 0.7f), Offset(0f, ropeY + r * px * 3), Size(size.width, px * 0.8f))
    // floor tiles
    val floorY = size.height * 0.86f
    val tile = px * 4
    var x = 0f
    var odd = false
    while (x < size.width) {
        drawRect(if (odd) Color(0xFF1E2638) else Color(0xFF242E44), Offset(x, floorY), Size(tile, size.height - floorY))
        odd = !odd
        x += tile
    }
    drawRect(Color(0xFF3A4766), Offset(0f, floorY), Size(size.width, px * 0.8f))
}

private fun DrawScope.drawCrown(cx: Float, top: Float, cell: Float) {
    val c = Amber
    val y = top - cell * 3
    val x = cx - cell * 2.5f
    drawRect(c, Offset(x, y + cell), Size(cell * 5, cell * 2))
    drawRect(c, Offset(x, y), Size(cell, cell))
    drawRect(c, Offset(x + cell * 2, y), Size(cell, cell))
    drawRect(c, Offset(x + cell * 4, y), Size(cell, cell))
}

private fun DrawScope.drawNameTag(
    measurer: TextMeasurer,
    style: TextStyle,
    s: Session,
    index: Int,
    cx: Float,
    spriteTop: Float,
    cell: Float,
    maxChars: Int,
) {
    val name = s.name.uppercase().take(maxChars)
    val label = "$name LV${s.stats.level}"
    val layout = measurer.measure(label, style)
    val barW = maxOf(layout.size.width.toFloat(), cell * 12)
    val barH = cell * 0.9f
    val top = (spriteTop - layout.size.height - barH - cell * 2).coerceAtLeast(2f)
    val pad = cell * 0.8f
    drawRect(
        Color(0xE60A0D14),
        Offset(cx - barW / 2 - pad, top - pad),
        Size(barW + pad * 2, layout.size.height + barH + cell * 0.5f + pad * 2),
    )
    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, top))
    // XP bar in 10 segments
    val segs = 10
    val gap = cell * 0.25f
    val segW = (barW - gap * (segs - 1)) / segs
    val filled = (s.stats.levelProgress * segs).toInt()
    val color = mascotColors(index).body
    for (i in 0 until segs) {
        drawRect(
            if (i < filled) color else Color(0xFF2A3346),
            Offset(cx - barW / 2 + i * (segW + gap), top + layout.size.height + cell * 0.5f),
            Size(segW, barH),
        )
    }
}

private fun DrawScope.drawHit(
    measurer: TextMeasurer,
    style: TextStyle,
    shadow: TextStyle,
    x: Float,
    y: Float,
    round: Int,
    t: Float,
    cell: Float,
) {
    val word = HitWords[round.mod(HitWords.size)]
    val layout = measurer.measure(word, style)
    val shadowLayout = measurer.measure(word, shadow)
    val tx = x - layout.size.width / 2f
    val ty = y - cell * 7
    drawText(shadowLayout, topLeft = Offset(tx + cell * 0.4f, ty + cell * 0.4f))
    drawText(layout, topLeft = Offset(tx, ty))
    // sparks
    val r = Random(round * 31 + 7)
    repeat(10) {
        val ang = r.nextFloat() * 6.28f
        val dist = cell * (2 + r.nextFloat() * 4) * (0.6f + abs(sin(t * 20f)) * 0.6f)
        val c = if (it % 2 == 0) Amber else Color.White
        drawRect(c, Offset(x + cos(ang) * dist, y + sin(ang) * dist), Size(cell, cell))
    }
}
