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
import androidx.compose.runtime.mutableStateOf
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
/**
 * A selectable accent color theme — shown as a "marble" swatch in the nav bar's settings
 * reveal. [colors] is the gradient stop list used everywhere accent color appears (buttons,
 * the follow-up input's glow ring, the reading-language pill) — 2 stops for a simple two-tone
 * theme, 3 for a tri-tone one.
 */
enum class AccentTheme(val colors: List<Color>) {
    SkyDeep(listOf(Color(0xFF4FC3F7), Color(0xFF0B1F4B))),
    SkyDeepPurple(listOf(Color(0xFF4FC3F7), Color(0xFF0B1F4B), Color(0xFF6C3FC5))),
    LightDarkPurple(listOf(Color(0xFFCBB2F0), Color(0xFF4A148C))),
    LightDarkRed(listOf(Color(0xFFEF9A9A), Color(0xFFB71C1C))),
}

object Glass {
    /**
     * The app-wide accent theme, swapped from the nav bar's marble picker. Held as Compose
     * state directly on the object (rather than threaded through every composable) so any
     * composable that reads [skyBlue]/[deepBlue]/[buttonBrush] etc. automatically recomposes
     * when the user picks a different theme, with no plumbing required at call sites.
     */
    var selectedAccentTheme by mutableStateOf(AccentTheme.SkyDeep)
        private set

    fun selectAccentTheme(theme: AccentTheme) {
        selectedAccentTheme = theme
    }

    /**
     * App-wide dark mode, toggled from the nav bar's settings gear. Lives here alongside
     * [selectedAccentTheme] since both are the same kind of thing — a global UI preference
     * many unrelated composables need to react to without prop-threading.
     */
    var isDarkMode by mutableStateOf(false)
        private set

    fun toggleDarkMode() {
        isDarkMode = !isDarkMode
    }

    private val accentColors: List<Color> get() = selectedAccentTheme.colors

    /** Sky blue (or the current theme's first/dominant tone) — the majority tone in the two-tone glow/button palette. */
    val skyBlue: Color get() = accentColors.first()

    /** Deep night blue (or the current theme's last/minority tone). */
    val deepBlue: Color get() = accentColors.last()

    /**
     * Glow palette for the follow-up input's pulse ring — always resolved to 3 stops so the
     * ring animation stays consistent regardless of whether the selected theme has 2 or 3
     * tones: two-tone themes repeat their first color at the end, matching the original
     * "mostly-dominant-tone, dip into the minority tone" motion.
     */
    val heavenColors: List<Color> get() = if (accentColors.size >= 3) accentColors else listOf(accentColors[0], accentColors[1], accentColors[0])

    /** Stop positions paired with [heavenColors] — 0 → 0.65 → 1.0, giving the dominant tone the majority presence. */
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

    /** Accent-gradient button fill — the "heaven" aesthetic applied to key tappable buttons (not text). */
    fun buttonBrush(): Brush = Brush.linearGradient(colors = accentColors)

    /**
     * Frosted-glass nav bar background — a *flat, uniform* fill rather than a diagonal
     * gradient. A gradient brush spans the whole composable's bounds, and across a bar this
     * wide but this short, the diagonal color shift reads as visible banding/seams (especially
     * around the rounded pill corners) instead of a clean sheet of glass. A single uniform
     * alpha avoids that "artifacting" — still see-through enough for scripture to read faintly
     * behind it, but solid enough that the bar itself is unmistakably one continuous panel.
     */
    fun navBarBrush(): Brush = Brush.linearGradient(
        colors = listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.6f)),
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
