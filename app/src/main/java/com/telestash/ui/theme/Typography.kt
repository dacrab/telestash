package com.telestash.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/** Material 3 defaults, tuned: semibold headings and slight negative tracking
 *  read "editorial/photo app" without a custom brand font. */
val AppTypography =
    Typography(
        displaySmall =
            Default.displaySmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
        headlineMedium =
            Default.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.25).sp,
            ),
        headlineSmall =
            Default.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.25).sp,
            ),
        titleLarge =
            Default.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        titleMedium =
            Default.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        labelLarge =
            Default.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
    )
