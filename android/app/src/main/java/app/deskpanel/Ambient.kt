package app.deskpanel

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shown while music plays and nobody has touched the screen for a bit: synced lyrics when they exist,
 * otherwise a pixel city time-lapse. Any touch brings the normal panel back.
 */
@Composable
fun AmbientView(media: Media, art: ImageBitmap?, lyrics: List<LyricLine>?, mediaAt: Long) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        // no clouds behind lyrics: they read as smudges through the dimmed lines
        TimeLapse(Modifier.fillMaxSize(), clouds = lyrics == null)
        if (lyrics != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            LyricsView(lyrics, media, mediaAt, Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp))
        }
        // now playing strip
        Row(
            Modifier.align(Alignment.BottomStart).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (art != null) {
                Image(
                    art, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.None,
                    modifier = Modifier.size(54.dp).clip(ButtonShape).border(2.dp, TextMain.copy(alpha = 0.6f), ButtonShape),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(media.title, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(420.dp))
                Text(
                    listOf(media.artist, if (lyrics != null) "LYRICS SYNCED" else "TIME-LAPSE MODE").filter { it.isNotEmpty() }.joinToString("  ·  "),
                    fontSize = 16.sp, color = TextMain.copy(alpha = 0.7f), maxLines = 1,
                )
            }
        }
        AmbientClock(Modifier.align(Alignment.TopEnd).padding(18.dp))
        Text(
            "TAP TO WAKE", fontFamily = PixelHead, fontSize = 7.sp, color = TextMain.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

@Composable
private fun AmbientClock(modifier: Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val date = remember { SimpleDateFormat("EEE dd MMM", Locale.getDefault()) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        // a hard pixel shadow keeps the clock readable when a cloud drifts behind it
        val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(4f, 4f), 0f)
        Text(time.format(Date(now)), fontSize = 64.sp, lineHeight = 60.sp, style = LocalTextStyle.current.copy(shadow = shadow))
        Text(date.format(Date(now)).uppercase(), fontFamily = PixelHead, fontSize = 8.sp, color = TextMain.copy(alpha = 0.7f))
    }
}

@Composable
private fun LyricsView(lines: List<LyricLine>, media: Media, mediaAt: Long, modifier: Modifier) {
    val posMs by produceState(0L, media, mediaAt) {
        while (true) {
            val elapsed = if (media.playing) SystemClock.elapsedRealtime() - mediaAt else 0
            value = (media.position * 1000).toLong() + elapsed
            delay(100)
        }
    }
    val idx = lines.indexOfLast { it.t <= posMs }
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = idx,
            transitionSpec = {
                (slideInVertically(tween(350)) { it / 3 } + fadeIn(tween(350)))
                    .togetherWith(slideOutVertically(tween(300)) { -it / 3 } + fadeOut(tween(300)))
            },
            label = "lyrics",
        ) { i ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in -2..2) {
                    val line = lines.getOrNull(i + k)
                    val text = when {
                        i + k < 0 && k == 0 -> "♪  ♪  ♪"
                        line == null -> ""
                        line.x.isBlank() -> "♪"
                        else -> line.x
                    }
                    val current = k == 0
                    Text(
                        text,
                        fontSize = if (current) 46.sp else 26.sp,
                        lineHeight = if (current) 46.sp else 26.sp,
                        color = when {
                            current -> Color.White
                            k < 0 -> TextMain.copy(alpha = 0.28f)
                            else -> TextMain.copy(alpha = 0.5f)
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pixel city time-lapse

private class Scene(
    val stars: List<Triple<Float, Float, Float>>,
    val buildings: List<IntArray>, // x, w, h (cells)
    val windowLight: List<FloatArray>, // per building, threshold per window
    val clouds: List<FloatArray>, // row, speed, offset, width
)

private fun makeScene(): Scene {
    val r = Random(42)
    val stars = List(140) { Triple(r.nextFloat(), r.nextFloat() * 0.62f, r.nextFloat() * 6.28f) }
    val buildings = mutableListOf<IntArray>()
    var x = 0
    while (x < 420) {
        val w = 5 + r.nextInt(9)
        val h = 8 + r.nextInt(22)
        buildings += intArrayOf(x, w, h)
        x += w + r.nextInt(2)
    }
    val windows = buildings.map { b -> FloatArray(((b[1] - 2) / 2 + 1) * (b[2] / 2 + 1)) { r.nextFloat() } }
    val clouds = List(7) { floatArrayOf(4f + r.nextInt(26), 2f + r.nextFloat() * 4f, r.nextFloat() * 400f, 6f + r.nextInt(10)) }
    return Scene(stars, buildings, windows, clouds)
}

// sky keyframes: time of day (0..1) -> top colour, bottom colour, daylight (0 night .. 1 day)
private val Keys = listOf(
    Triple(0.00f, Color(0xFF060A1C) to Color(0xFF18204A), 0f),
    Triple(0.20f, Color(0xFF060A1C) to Color(0xFF18204A), 0f),
    Triple(0.28f, Color(0xFF2B2D6E) to Color(0xFFF2876B), 0.45f),
    Triple(0.36f, Color(0xFF3E8EDE) to Color(0xFF9ED4F5), 1f),
    Triple(0.62f, Color(0xFF3E8EDE) to Color(0xFF9ED4F5), 1f),
    Triple(0.70f, Color(0xFF3B2A6B) to Color(0xFFFF8A4C), 0.45f),
    Triple(0.78f, Color(0xFF060A1C) to Color(0xFF18204A), 0f),
    Triple(1.00f, Color(0xFF060A1C) to Color(0xFF18204A), 0f),
)

private fun sky(p: Float): Triple<Color, Color, Float> {
    for (i in 0 until Keys.size - 1) {
        val (p0, c0, d0) = Keys[i]
        val (p1, c1, d1) = Keys[i + 1]
        if (p in p0..p1) {
            val f = if (p1 > p0) (p - p0) / (p1 - p0) else 0f
            return Triple(lerp(c0.first, c1.first, f), lerp(c0.second, c1.second, f), d0 + (d1 - d0) * f)
        }
    }
    return Triple(Keys[0].second.first, Keys[0].second.second, 0f)
}

@Composable
fun TimeLapse(modifier: Modifier = Modifier, clouds: Boolean = true) {
    val scene = remember { makeScene() }
    val time by produceState(0L) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { value = it - start }
    }
    Canvas(modifier) { drawCity(time / 1000f, scene, clouds) }
}

private fun DrawScope.drawCity(t: Float, scene: Scene, clouds: Boolean) {
    val rows = 90
    val cell = size.height / rows
    val cols = ceil(size.width / cell).toInt()
    val p = ((t / 45f) + 0.30f) % 1f // one full day every 45 s, start in the morning
    val (top, bottom, day) = sky(p)
    val horizon = 70

    // sky in stepped bands
    val bands = 22
    val bandH = horizon * cell / bands
    for (i in 0 until bands) drawRect(lerp(top, bottom, i / (bands - 1f)), Offset(0f, i * bandH), Size(size.width, bandH + 1))

    // stars
    val night = 1f - day
    if (night > 0.05f) {
        scene.stars.forEach { (sx, sy, ph) ->
            val a = night * (0.45f + 0.55f * sin(t * 2.5f + ph))
            if (a > 0.05f) {
                val cx = (sx * cols).toInt() * cell
                val cy = (sy * horizon).toInt() * cell
                drawRect(Color.White.copy(alpha = a.coerceIn(0f, 1f)), Offset(cx, cy), Size(cell, cell))
            }
        }
    }

    // sun and moon on arcs
    fun body(phase: Float, color: Color, radius: Int) {
        if (phase !in 0f..1f) return
        val bx = (phase * (cols + 20) - 10).toInt()
        val by = (horizon - sin(phase * Math.PI).toFloat() * horizon * 0.85f).toInt()
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx * dx + dy * dy <= radius * radius) drawRect(color, Offset((bx + dx) * cell, (by + dy) * cell), Size(cell, cell))
        }
    }
    body((p - 0.22f) / 0.56f, lerp(Color(0xFFFF9B4A), Color(0xFFFFE58A), day), 4)
    val moonPhase = ((p + 0.5f) % 1f - 0.22f) / 0.56f
    body(moonPhase, Color(0xFFE6E8F2), 3)

    // clouds
    val cloudColor = lerp(Color(0xFF2A3050), Color(0xFFF4F7FF), day).copy(alpha = 0.85f)
    if (clouds) scene.clouds.forEach { c ->
        val w = c[3].toInt()
        val cx = (((t * c[1] + c[2]) % (cols + 40)) - 20).toInt()
        val cy = c[0].toInt()
        drawRect(cloudColor, Offset((cx + 2) * cell, cy * cell), Size((w - 4) * cell, cell))
        drawRect(cloudColor, Offset(cx * cell, (cy + 1) * cell), Size(w * cell, cell * 2))
    }

    // two layers of mountains
    for (c in 0 until cols) {
        val far = (16 + 7 * sin(c * 0.07f + 1f) + 4 * sin(c * 0.19f)).toInt()
        drawRect(lerp(Color(0xFF141A2E), Color(0xFF557396), day), Offset(c * cell, (horizon - far) * cell), Size(cell + 0.5f, far * cell + 1))
    }
    for (c in 0 until cols) {
        val near = (9 + 5 * sin(c * 0.11f + 2f) + 3 * sin(c * 0.31f)).toInt()
        drawRect(lerp(Color(0xFF0E1322), Color(0xFF3A5A74), day), Offset(c * cell, (horizon - near) * cell), Size(cell + 0.5f, near * cell + 1))
    }
    // valley below the horizon
    drawRect(lerp(Color(0xFF0B0F1B), Color(0xFF2F4A5E), day), Offset(0f, horizon * cell), Size(size.width, (rows - horizon) * cell))

    // city skyline with windows that light up at night
    val ground = 86
    val buildingColor = lerp(Color(0xFF0A0D16), Color(0xFF26304A), day)
    val lit = Color(0xFFFFD36B)
    scene.buildings.forEachIndexed { bi, b ->
        val (bx, bw, bh) = Triple(b[0], b[1], b[2])
        if (bx > cols) return@forEachIndexed
        drawRect(buildingColor, Offset(bx * cell, (ground - bh) * cell), Size(bw * cell, bh * cell))
        if (night > 0.1f) {
            val th = scene.windowLight[bi]
            var w = 0
            var wy = ground - bh + 2
            while (wy < ground - 1) {
                var wx = bx + 1
                while (wx < bx + bw - 1) {
                    val threshold = th.getOrElse(w) { 1f }
                    val flicker = sin(t * 0.7f + w * 1.7f + bi) > -0.92f
                    if (night > threshold && flicker) drawRect(lit.copy(alpha = 0.85f), Offset(wx * cell, wy * cell), Size(cell, cell))
                    w++
                    wx += 2
                }
                wy += 2
            }
        }
    }
    // road and a car
    drawRect(Color(0xFF0A0C12), Offset(0f, ground * cell), Size(size.width, (rows - ground) * cell))
    for (c in 0 until cols step 6) drawRect(Color(0xFF3A3F4F), Offset(c * cell, (ground + 2) * cell), Size(cell * 3, cell * 0.6f))
    val carX = ((t * 22f) % (cols + 20)).toInt() - 10
    drawRect(Color(0xFFD94B4B), Offset(carX * cell, (ground + 1) * cell), Size(cell * 6, cell * 2))
    drawRect(Color(0xFFD94B4B), Offset((carX + 1) * cell, ground * cell), Size(cell * 3, cell))
    drawRect(if (night > 0.3f) Color(0xFFFFF2B0) else Color(0xFFBBBBBB), Offset((carX + 6) * cell, (ground + 1) * cell), Size(cell, cell))
}
