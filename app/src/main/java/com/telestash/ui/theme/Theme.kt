@file:Suppress("MagicNumber") // ARGB hex literals are idiomatic for color definitions

package com.telestash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand-first palettes on every Android version: the photo library's whole
// identity is the Telegram blue, so wallpaper-derived "dynamic color" is
// deliberately not used (that's why Gmail and YouTube skip it too).

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2AABEE),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC8E9FF),
        onPrimaryContainer = Color(0xFF003352),
        secondary = Color(0xFF0088CC),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD1E4FF),
        onSecondaryContainer = Color(0xFF004B72),
        tertiary = Color(0xFF3B7F6E),
        onTertiary = Color.White,
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = Color(0xFFF7F9FB),
        onBackground = Color(0xFF191C1E),
        surface = Color(0xFFF7F9FB),
        onSurface = Color(0xFF191C1E),
        surfaceVariant = Color(0xFFDEE7EE),
        onSurfaceVariant = Color(0xFF43515B),
        outline = Color(0xFF74828C),
        outlineVariant = Color(0xFFC3D0D9),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF64C9F5),
        onPrimary = Color(0xFF00344D),
        primaryContainer = Color(0xFF004B70),
        onPrimaryContainer = Color(0xFFC5E8FF),
        secondary = Color(0xFF54B6E2),
        onSecondary = Color(0xFF00354C),
        secondaryContainer = Color(0xFF004C6B),
        onSecondaryContainer = Color(0xFFC9E5FF),
        tertiary = Color(0xFF8CD5C1),
        onTertiary = Color(0xFF00372D),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0C1319),
        onBackground = Color(0xFFE1E7ED),
        surface = Color(0xFF0C1319),
        onSurface = Color(0xFFE1E7ED),
        surfaceVariant = Color(0xFF27333C),
        onSurfaceVariant = Color(0xFFB7C6D0),
        outline = Color(0xFF8A99A4),
        outlineVariant = Color(0xFF3E4D57),
    )

@Composable
fun TeleStashTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
