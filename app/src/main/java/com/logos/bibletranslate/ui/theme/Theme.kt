package com.logos.bibletranslate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deliberate blue / deep-blue / purple palette — replaces Material3's default lavender-pink
 * light scheme so every stock component (buttons, containers, dialogs, chips) reads as part of
 * the same "heavenly glass" family as [Glass]'s sky-blue/deep-blue tokens, instead of clashing
 * with the default Material purple.
 */
private val BlueSkyPurple = Color(0xFF4FC3F7) // matches Glass.skyBlue
private val DeepNightBlue = Color(0xFF0B1F4B) // matches Glass.deepBlue
private val RoyalPurple = Color(0xFF5B4FE0)

private val LightColors = lightColorScheme(
    primary = DeepNightBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FB),
    onPrimaryContainer = Color(0xFF0B1F4B),
    secondary = RoyalPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3DFFB),
    onSecondaryContainer = Color(0xFF2A2260),
    tertiary = BlueSkyPurple,
    onTertiary = Color(0xFF00344F),
    tertiaryContainer = Color(0xFFCDEBFC),
    onTertiaryContainer = Color(0xFF00293F),
    background = Color(0xFFF6F7FE),
    onBackground = Color(0xFF13152B),
    surface = Color(0xFFF6F7FE),
    onSurface = Color(0xFF13152B),
    surfaceVariant = Color(0xFFE4E7F7),
    onSurfaceVariant = Color(0xFF44465A),
    outline = Color(0xFF6F7397),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/**
 * Dark counterpart of [LightColors] — same blue/purple family, just inverted for a dark
 * background, so switching modes doesn't also change the app's color identity.
 */
private val DarkColors = darkColorScheme(
    primary = BlueSkyPurple,
    onPrimary = Color(0xFF00344F),
    primaryContainer = DeepNightBlue,
    onPrimaryContainer = Color(0xFFD9E6FB),
    secondary = Color(0xFFC3B8FF),
    onSecondary = Color(0xFF2A2260),
    secondaryContainer = Color(0xFF433A85),
    onSecondaryContainer = Color(0xFFE3DFFB),
    tertiary = Color(0xFF7FD4FA),
    onTertiary = Color(0xFF00293F),
    tertiaryContainer = Color(0xFF00495F),
    onTertiaryContainer = Color(0xFFCDEBFC),
    background = Color(0xFF0E0F1E),
    onBackground = Color(0xFFE3E4F5),
    surface = Color(0xFF13152B),
    onSurface = Color(0xFFE3E4F5),
    surfaceVariant = Color(0xFF2B2E4A),
    onSurfaceVariant = Color(0xFFC3C5DC),
    outline = Color(0xFF8C8FB0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Reads [Glass.isDarkMode] — toggled from the nav bar's settings gear — so the whole app's
 * Material color scheme follows the same global toggle everything else (accent marbles) uses,
 * with no separate settings-screen plumbing needed.
 */
@Composable
fun BibleTranslateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (Glass.isDarkMode) DarkColors else LightColors,
        content = content,
    )
}
