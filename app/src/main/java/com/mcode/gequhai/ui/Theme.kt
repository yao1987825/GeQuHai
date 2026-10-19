package com.mcode.gequhai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary   = Color(0xFF31C27C),
    secondary = Color(0xFF1B7A4B),
    tertiary  = Color(0xFF118AB2),
)

private val DarkColors = darkColorScheme(
    primary   = Color(0xFF31C27C),
    secondary = Color(0xFF6FE0A8),
    tertiary  = Color(0xFF66CFE8),
)

@Composable
fun GeQuHaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}