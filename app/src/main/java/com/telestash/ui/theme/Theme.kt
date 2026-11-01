@file:Suppress("MagicNumber") // ARGB hex literals are idiomatic for color definitions

package com.telestash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand-first palettes on every Android version: the photo library's whole
// identity is the Telegram blue, so wallpaper-derived "dynamic color" is
// deliberately not used (that's why Gmail and YouTube skip it too).
//
// Every M3 Expressive role is spelled out, including the fixed accents and
// surface-container ramp, because the expressive components lean on them for
// their tonal layering and shape morphs.

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF006493),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFC8E9FF),
        onPrimaryContainer = Color(0xFF003352),
        inversePrimary = Color(0xFF8ACFFB),
        secondary = Color(0xFF0077B5),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD1E4FF),
        onSecondaryContainer = Color(0xFF004B72),
        tertiary = Color(0xFF2F6355),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFB2F1DC),
        onTertiaryContainer = Color(0xFF0A3B30),
        background = Color(0xFFF7F9FB),
        onBackground = Color(0xFF191C1E),
        surface = Color(0xFFF7F9FB),
        onSurface = Color(0xFF191C1E),
        surfaceVariant = Color(0xFFDEE7EE),
        onSurfaceVariant = Color(0xFF43515B),
        surfaceTint = Color(0xFF006493),
        inverseSurface = Color(0xFF2D3133),
        inverseOnSurface = Color(0xFFEEF1F3),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = Color(0xFF74828C),
        outlineVariant = Color(0xFFC3D0D9),
        scrim = Color.Black,
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFD7DADC),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF1F4F6),
        surfaceContainer = Color(0xFFEBEEF0),
        surfaceContainerHigh = Color(0xFFE5E8EA),
        surfaceContainerHighest = Color(0xFFDFE2E4),
        primaryFixed = Color(0xFFC8E9FF),
        primaryFixedDim = Color(0xFF8ACFFB),
        onPrimaryFixed = Color(0xFF001E2F),
        onPrimaryFixedVariant = Color(0xFF004A6E),
        secondaryFixed = Color(0xFFC8E6F8),
        secondaryFixedDim = Color(0xFF8ACBE6),
        onSecondaryFixed = Color(0xFF001F2B),
        onSecondaryFixedVariant = Color(0xFF004D68),
        tertiaryFixed = Color(0xFFB2F1DC),
        tertiaryFixedDim = Color(0xFF96D5C1),
        onTertiaryFixed = Color(0xFF00201A),
        onTertiaryFixedVariant = Color(0xFF124F42),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF64C9F5),
        onPrimary = Color(0xFF00344D),
        primaryContainer = Color(0xFF004B70),
        onPrimaryContainer = Color(0xFFC5E8FF),
        inversePrimary = Color(0xFF006493),
        secondary = Color(0xFF7FD0EE),
        onSecondary = Color(0xFF003543),
        secondaryContainer = Color(0xFF004E63),
        onSecondaryContainer = Color(0xFFC1E8F7),
        tertiary = Color(0xFF96D5C1),
        onTertiary = Color(0xFF00382E),
        tertiaryContainer = Color(0xFF174F43),
        onTertiaryContainer = Color(0xFFB2F1DC),
        background = Color(0xFF0C1319),
        onBackground = Color(0xFFE1E7ED),
        surface = Color(0xFF0C1319),
        onSurface = Color(0xFFE1E7ED),
        surfaceVariant = Color(0xFF27333C),
        onSurfaceVariant = Color(0xFFB7C6D0),
        surfaceTint = Color(0xFF64C9F5),
        inverseSurface = Color(0xFFE1E7ED),
        inverseOnSurface = Color(0xFF293136),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF8A99A4),
        outlineVariant = Color(0xFF3E4D57),
        scrim = Color.Black,
        surfaceBright = Color(0xFF323A40),
        surfaceDim = Color(0xFF0C1319),
        surfaceContainerLowest = Color(0xFF070D12),
        surfaceContainerLow = Color(0xFF141B21),
        surfaceContainer = Color(0xFF181F25),
        surfaceContainerHigh = Color(0xFF232A30),
        surfaceContainerHighest = Color(0xFF2E353B),
        primaryFixed = Color(0xFFC8E9FF),
        primaryFixedDim = Color(0xFF8ACFFB),
        onPrimaryFixed = Color(0xFF001E2F),
        onPrimaryFixedVariant = Color(0xFF004A6E),
        secondaryFixed = Color(0xFFC8E6F8),
        secondaryFixedDim = Color(0xFF8ACBE6),
        onSecondaryFixed = Color(0xFF001F2B),
        onSecondaryFixedVariant = Color(0xFF004D68),
        tertiaryFixed = Color(0xFFB2F1DC),
        tertiaryFixedDim = Color(0xFF96D5C1),
        onTertiaryFixed = Color(0xFF00201A),
        onTertiaryFixedVariant = Color(0xFF124F42),
    )

/**
 * Expressive shape scale: visibly rounder than the baseline M3 scale so cards,
 * sheets and FABs read as one soft, photo-first family.
 */
val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

@Composable
fun TeleStashTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
