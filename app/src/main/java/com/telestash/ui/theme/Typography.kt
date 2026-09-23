package com.telestash.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Slight, deliberate tuning on top of the Material 3 defaults: semibold
// headlines and a touch of negative tracking read "editorial/photo app"
// without inventing a custom brand font. Bodies stay on the system font.
val AppTypography =
    Typography(
        displaySmall =
            Typography().displaySmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
        headlineMedium =
            Typography().headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.25).sp,
            ),
        headlineSmall =
            Typography().headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.25).sp,
            ),
        titleLarge =
            Typography().titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (0.0).sp,
            ),
        titleMedium =
            Typography().titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        labelLarge =
            Typography().labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
    )
