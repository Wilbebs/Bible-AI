package com.logos.bibletranslate.ui.theme

import androidx.compose.material3.MaterialTheme
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

@Composable
fun BibleTranslateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
