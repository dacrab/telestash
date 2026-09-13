@file:Suppress("MagicNumber") // ARGB hex literals are idiomatic for color definitions

package com.telestash.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2AABEE),
        onPrimary = Color.White,
        secondary = Color(0xFF0088CC),
        surface = Color(0xFFF7F9FB),
        background = Color(0xFFF7F9FB),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF64C9F5),
        onPrimary = Color(0xFF00344D),
        secondary = Color(0xFF54B6E2),
    )

@Composable
fun TeleStashTheme(content: @Composable () -> Unit) {
    val colorScheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (isSystemInDarkTheme()) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }
            isSystemInDarkTheme() -> DarkColors
            else -> LightColors
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
