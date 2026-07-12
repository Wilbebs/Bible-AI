package com.logos.bibletranslate.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * iOS-26-style "liquid glass" design tokens. Kept minimal and dependency-free
 * (no third-party blur library) so the look is consistent, cheap to render,
 * and safe to build without a device on hand to visually verify against.
 */
object Glass {
    /** Google's brand colors, used for the Gemini-style cycling glow accent. */
    val brandColors = listOf(
        Color(0xFF4285F4), // blue
        Color(0xFFEA4335), // red
        Color(0xFFFBBC05), // yellow
        Color(0xFF34A853), // green
        Color(0xFF4285F4), // repeat first color so the sweep loops seamlessly
    )

    val panelShape = RoundedCornerShape(28.dp)
    val pillShape = RoundedCornerShape(50)

    /**
     * Frosted panel background: a mostly-opaque, faintly diagonal sheen over content.
     * Deliberately much closer to solid than a true see-through pane of glass — with
     * dense verse text sitting directly behind it, a lighter/more transparent fill
     * made the panel's own text unreadable, so legibility wins over transparency here.
     */
    fun panelBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.97f),
            Color.White.copy(alpha = 0.90f),
            Color.White.copy(alpha = 0.95f),
        ),
    )

    /** A thin bright edge, like light catching the rim of a glass pane. */
    fun panelBorderBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.9f),
            Color.White.copy(alpha = 0.15f),
        ),
    )

    /** Interpolates a solid color at [position] along [brandColors] (0f..brandColors.lastIndex), for a still ring that only shifts color. */
    fun colorAtCyclePosition(position: Float): Color {
        val clamped = position.coerceIn(0f, (brandColors.size - 1).toFloat())
        val index = clamped.toInt().coerceAtMost(brandColors.size - 2)
        val fraction = clamped - index
        return lerp(brandColors[index], brandColors[index + 1], fraction)
    }
}

/**
 * Draws a still (non-rotating) border around the content whose color slowly
 * cycles through Google's brand colors — the "Gemini is ready" cue used on
 * the follow-up input. The ring itself never moves or spins; only its color
 * shifts, like the Gemini/Bard loading indicator's palette without the motion.
 */
@Composable
fun Modifier.geminiGlowBorder(
    strokeWidth: Dp = 2.dp,
    cornerRadius: Dp = 28.dp,
    durationMillis: Int = 5000,
): Modifier {
    val transition = rememberInfiniteTransition(label = "geminiGlow")
    val colorPosition by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Glass.brandColors.size - 1).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "colorPosition",
    )
    val color = Glass.colorAtCyclePosition(colorPosition)
    return this
        .clip(RoundedCornerShape(cornerRadius))
        .drawWithContent {
            drawContent()
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokePx, size.height - strokePx),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = strokePx),
            )
        }
}

/**
 * How many "units" (characters, or tokens for word-based content) of a
 * streaming-in string should currently be visible, growing over time to
 * produce a smooth typewriter reveal — modeled on the fade-in typing seen
 * on the Insureit "About Us" copy.
 */
@Composable
fun rememberTypewriterProgress(
    text: String,
    totalUnits: Int,
    unitsPerTick: Int = 1,
    tickMillis: Long = 18,
    animate: Boolean = true,
): Int {
    var visibleUnits by remember(text) { mutableIntStateOf(if (animate) 0 else totalUnits) }
    androidx.compose.runtime.LaunchedEffect(text, totalUnits, animate) {
        if (!animate) {
            visibleUnits = totalUnits
            return@LaunchedEffect
        }
        visibleUnits = 0
        while (visibleUnits < totalUnits) {
            delay(tickMillis)
            visibleUnits = (visibleUnits + unitsPerTick).coerceAtMost(totalUnits)
        }
    }
    return visibleUnits
}

@Composable
fun TypewriterText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    fontStyle: androidx.compose.ui.text.font.FontStyle? = null,
) {
    val visibleChars = rememberTypewriterProgress(
        text = text,
        totalUnits = text.length,
        unitsPerTick = 2,
        tickMillis = 14,
        animate = animate,
    )
    androidx.compose.material3.Text(
        text = text.take(visibleChars),
        style = style,
        fontStyle = fontStyle,
        modifier = modifier,
    )
}
