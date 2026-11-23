package app.deskpanel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ---------------------------------------------------------------- palette

val Bg = Color(0xFF0A0D14)
val CardBg = Color(0xFF131925)
val Track = Color(0xFF222B3A)
val TextMain = Color(0xFFE8ECF2)
val Muted = Color(0xFF7F8AA0)
val Blue = Color(0xFF4F8CFF)
val Purple = Color(0xFFA06BFF)
val Teal = Color(0xFF2DD4BF)
val Green = Color(0xFF3DDC84)
val Amber = Color(0xFFFFB020)
val Red = Color(0xFFFF5C5C)
val Pink = Color(0xFFF06BA8)
val ClaudeOrange = Color(0xFFD97757)

// ---------------------------------------------------------------- fonts

/** Readable terminal pixel font for body text and numbers. */
val PixelFont = FontFamily(Font(R.font.vt323))
/** Chunky 8-bit font for labels and headings. */
val PixelHead = FontFamily(Font(R.font.press_start))

@Composable
fun DeskPanelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = CardBg, onSurface = TextMain)) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = PixelFont, color = TextMain, fontSize = 20.sp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------- shapes

/** Rectangle with stepped "pixel" corners. */
class PixelShape(private val step: Dp = 4.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { step.toPx() }
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(2 * s, 0f); lineTo(w - 2 * s, 0f); lineTo(w - 2 * s, s); lineTo(w - s, s); lineTo(w - s, 2 * s); lineTo(w, 2 * s)
            lineTo(w, h - 2 * s); lineTo(w - s, h - 2 * s); lineTo(w - s, h - s); lineTo(w - 2 * s, h - s); lineTo(w - 2 * s, h)
            lineTo(2 * s, h); lineTo(2 * s, h - s); lineTo(s, h - s); lineTo(s, h - 2 * s); lineTo(0f, h - 2 * s)
            lineTo(0f, 2 * s); lineTo(s, 2 * s); lineTo(s, s); lineTo(2 * s, s); close()
        }
        return Outline.Generic(p)
    }
}

val PanelShape = PixelShape(4.dp)
val ButtonShape = PixelShape(3.dp)

// ---------------------------------------------------------------- components

@Composable
fun PixelPanel(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    right: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(PanelShape)
            .background(CardBg)
            .border(2.dp, accent.copy(alpha = 0.5f), PanelShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(title.uppercase(), fontFamily = PixelHead, fontSize = 9.sp, color = accent, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (right != null) Text(right.uppercase(), fontFamily = PixelHead, fontSize = 7.sp, color = Muted, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Retro segmented bar (like an HP bar). */
@Composable
fun SegBar(fraction: Float, color: Color, modifier: Modifier = Modifier, segments: Int = 20) {
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        val filled = (fraction.coerceIn(0f, 1f) * segments).roundToInt()
        for (i in 0 until segments) {
            drawRect(if (i < filled) color else Track, Offset(i * (w + gap), 0f), Size(w, size.height))
        }
    }
}

/** Bar graph made of stacked pixel blocks. Values are 0..1. */
@Composable
fun BlockGraph(values: List<Float>, color: Color, modifier: Modifier = Modifier, columns: Int = PanelViewModel.HISTORY) {
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val colW = (size.width - gap * (columns - 1)) / columns
        val block = colW.coerceAtLeast(3.dp.toPx())
        val rows = (size.height / (block + gap)).toInt().coerceAtLeast(1)
        val off = columns - values.size
        values.forEachIndexed { i, v ->
            val n = (v.coerceIn(0f, 1f) * rows).roundToInt().coerceAtLeast(if (v > 0.001f) 1 else 0)
            val x = (off + i) * (colW + gap)
            for (r in 0 until n) {
                val alpha = 0.35f + 0.65f * (r + 1) / rows
                drawRect(color.copy(alpha = alpha), Offset(x, size.height - (r + 1) * (block + gap) + gap), Size(colW, block))
            }
        }
    }
}

@Composable
fun PixelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Color = Track,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .clip(ButtonShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Small "[ LABEL ]" style tag. */
@Composable
fun Tag(text: String, color: Color) {
    Box(Modifier.border(1.dp, color, ButtonShape).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Text(text.uppercase(), fontFamily = PixelHead, fontSize = 7.sp, color = color, maxLines = 1)
    }
}
