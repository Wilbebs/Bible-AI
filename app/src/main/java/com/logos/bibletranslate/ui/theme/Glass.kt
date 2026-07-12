package com.logos.bibletranslate.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * iOS-26-style "liquid glass" design tokens. Kept minimal and dependency-free
 * (no third-party blur library) so the look is consistent, cheap to render,
 * and safe to build without a device on hand to visually verify against.
 */
object Glass {
    /**
     * "Heaven" palette for the follow-up input's glow ring: deep night blue → sky blue →
     * golden hour yellow → sky blue → deep night blue. First and last stops match so the
     * sweep gradient loops with no seam.
     */
    val heavenColors = listOf(
        Color(0xFF0B1F4B), // deep night blue
        Color(0xFF4FC3F7), // sky blue
        Color(0xFFFFD54F), // golden heaven yellow
        Color(0xFF4FC3F7), // sky blue
        Color(0xFF0B1F4B), // deep night blue (closes the loop)
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

}

/**
 * Draws a slow, "heavenly" loading-style pulse around the content: a sweep gradient through
 * [Glass.heavenColors] that continuously rotates around the ring — so every color is visible
 * at once, just with more influence at different points around the loop at any given moment —
 * plus a soft bright "blare" flare that travels with it, and a gentle overall brightness
 * breathing on top. Used as the "Gemini is ready" cue on the follow-up input.
 */
@Composable
fun Modifier.geminiGlowBorder(
    strokeWidth: Dp = 2.2.dp,
    cornerRadius: Dp = 28.dp,
    durationMillis: Int = 7000,
): Modifier {
    val transition = rememberInfiniteTransition(label = "geminiGlow")
    val rotationDegrees by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    return this
        .clip(RoundedCornerShape(cornerRadius))
        .drawWithContent {
            drawContent()
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val ringBrush = Brush.sweepGradient(colors = Glass.heavenColors, center = center)

            rotate(degrees = rotationDegrees, pivot = center) {
                drawRoundRect(
                    brush = ringBrush,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                    style = Stroke(width = strokePx),
                    alpha = pulseAlpha,
                )
            }

            // A small bright "blare" of light that travels around the ring with the sweep,
            // like a glint catching the loop as it turns.
            val angleRad = Math.toRadians(rotationDegrees.toDouble())
            val rx = size.width / 2f - inset
            val ry = size.height / 2f - inset
            val flareCenter = Offset(
                x = center.x + rx * cos(angleRad).toFloat(),
                y = center.y + ry * sin(angleRad).toFloat(),
            )
            val flareRadius = strokePx * 3.2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.85f * pulseAlpha), Color.White.copy(alpha = 0f)),
                    center = flareCenter,
                    radius = flareRadius,
                ),
                radius = flareRadius,
                center = flareCenter,
            )
        }
}

/**
 * A small procedural 4-point sparkle glyph, tinted sky-blue → deep-blue — used as the "AI"
 * accent instead of the ✨ emoji, whose color can't be tinted since emoji glyphs render as
 * fixed full-color images on Android.
 */
@Composable
fun Sparkle(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val path = Path().apply {
            moveTo(cx, 0f)
            cubicTo(cx + w * 0.08f, cy - h * 0.08f, cx + w * 0.42f, cy - h * 0.08f, w, cy)
            cubicTo(cx + w * 0.42f, cy + h * 0.08f, cx + w * 0.08f, cy + h * 0.08f, cx, h)
            cubicTo(cx - w * 0.08f, cy + h * 0.08f, cx - w * 0.42f, cy + h * 0.08f, 0f, cy)
            cubicTo(cx - w * 0.42f, cy - h * 0.08f, cx - w * 0.08f, cy - h * 0.08f, cx, 0f)
            close()
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(listOf(Color(0xFF4FC3F7), Color(0xFF0B1F4B))),
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
