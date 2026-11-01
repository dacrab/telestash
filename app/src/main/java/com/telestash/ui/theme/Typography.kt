package com.telestash.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/** Material 3 Expressive type, tuned: emphasized headlines carry the app's
 *  editorial voice, while body copy stays restrained. */
val AppTypography =
    Typography(
        displaySmall =
            Default.displaySmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
        displaySmallEmphasized =
            Default.displaySmallEmphasized.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.75).sp,
            ),
        headlineMedium =
            Default.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.25).sp,
            ),
        headlineMediumEmphasized =
            Default.headlineMediumEmphasized.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
        headlineSmall =
            Default.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.25).sp,
            ),
        headlineSmallEmphasized =
            Default.headlineSmallEmphasized.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
            ),
        titleLarge =
            Default.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        titleLargeEmphasized =
            Default.titleLargeEmphasized.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
            ),
        titleMedium =
            Default.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        titleMediumEmphasized =
            Default.titleMediumEmphasized.copy(
                fontWeight = FontWeight.Bold,
            ),
        titleSmall =
            Default.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        titleSmallEmphasized =
            Default.titleSmallEmphasized.copy(
                fontWeight = FontWeight.Bold,
            ),
        labelLarge =
            Default.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        labelLargeEmphasized =
            Default.labelLargeEmphasized.copy(
                fontWeight = FontWeight.Bold,
            ),
        labelMedium =
            Default.labelMedium.copy(
                fontWeight = FontWeight.Medium,
            ),
    )
