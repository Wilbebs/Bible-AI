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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * iOS-26-style "liquid glass" design tokens. Kept minimal and dependency-free
 * (no third-party blur library) so the look is consistent, cheap to render,
 * and safe to build without a device on hand to visually verify against.
 */
object Glass {
    /** Sky blue — the dominant (~65%) tone in the two-tone glow/button palette. */
    val skyBlue = Color(0xFF4FC3F7)

    /** Deep night blue — the minority (~35%) tone. */
    val deepBlue = Color(0xFF0B1F4B)

    /**
     * Two-tone glow palette for the follow-up input's pulse ring: sky blue holding the
     * majority of the loop, dipping into deep night blue and smoothly back — no yellow, no
     * discrete moving marker, just a slow color wash around a fixed ring.
     */
    val heavenColors = listOf(skyBlue, deepBlue, skyBlue)

    /** Stop positions paired with [heavenColors] — 0 → 0.65 → 1.0, giving sky blue the majority presence. */
    val heavenStops = floatArrayOf(0f, 0.65f, 1f)

    val panelShape = RoundedCornerShape(28.dp)
    val pillShape = RoundedCornerShape(50)

    /**
     * Frosted panel background: a mostly-opaque, faintly diagonal sheen over content.
     * Deliberately much closer to solid than a true see-through pane of glass — with
     * dense verse text sitting directly behind it, a lighter/more transparent fill
     * made the panel's own text unreadable, so legibility wins over transparency here.
     * Pushed a little more opaque than earlier passes so text reliably reads over it.
     */
    fun panelBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.985f),
            Color.White.copy(alpha = 0.94f),
            Color.White.copy(alpha = 0.975f),
        ),
    )

    /** A thin bright edge, like light catching the rim of a glass pane. */
    fun panelBorderBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.9f),
            Color.White.copy(alpha = 0.15f),
        ),
    )

    /** Sky-blue → deep-blue button fill — the "heaven" aesthetic applied to key tappable buttons (not text). */
    fun buttonBrush(): Brush = Brush.linearGradient(colors = listOf(skyBlue, deepBlue))

    /**
     * Frosted-glass nav bar background — same diagonal-sheen family as [panelBrush] (the study
     * bubble's panel), but a touch more see-through than it: the bar is a real card of its own
     * now (buttons, logo and search all sitting inside one glass container), and scripture keeps
     * scrolling directly behind it, so it reads best pitched just below the bubble's near-solid
     * opacity rather than matching it exactly.
     */
    fun navBarBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.9f),
            Color.White.copy(alpha = 0.82f),
            Color.White.copy(alpha = 0.88f),
        ),
    )

    /** A bright edge around the frosted nav bar card, echoing [panelBorderBrush]. */
    fun navBarBorderBrush(): Brush = Brush.linearGradient(
        colors = listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.25f)),
    )
}

/**
 * Draws a slow, "heavenly" pulse around the content: the ring's geometry stays put (no
 * rotation of any shape or marker — nothing that reads as a clock hand sweeping around) while
 * the two-tone sky-blue/deep-blue color underneath breathes in brightness. Used as the "Gemini
 * is ready" cue on the follow-up input.
 */
@Composable
fun Modifier.geminiGlowBorder(
    strokeWidth: Dp = 2.2.dp,
    cornerRadius: Dp = 28.dp,
    durationMillis: Int = 4200,
): Modifier {
    val transition = rememberInfiniteTransition(label = "geminiGlow")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
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
            val stops = Glass.heavenStops.zip(Glass.heavenColors).toTypedArray()
            val ringBrush = Brush.sweepGradient(*stops, center = center)
            drawRoundRect(
                brush = ringBrush,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokePx, size.height - strokePx),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = strokePx),
                alpha = pulseAlpha,
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
            // Sky-blue dominant with only a slight deep-blue accent at the tip.
            brush = Brush.linearGradient(
                0f to Glass.skyBlue,
                0.7f to Glass.skyBlue,
                1f to Glass.deepBlue,
            ),
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
