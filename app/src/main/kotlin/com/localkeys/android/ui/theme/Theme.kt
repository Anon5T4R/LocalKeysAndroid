package com.localkeys.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cores da marca (mesmas do app-icon do desktop): índigo #6366F1 → #4338CA.
val Indigo = Color(0xFF6366F1)
val IndigoDark = Color(0xFF4338CA)
val IndigoLight = Color(0xFFEEF0FF)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoLight,
    onPrimaryContainer = IndigoDark,
    secondary = IndigoDark,
    onSecondary = Color.White,
    background = Color(0xFFFBFBFF),
    surface = Color(0xFFFBFBFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9BDFF),
    onPrimary = Color(0xFF23286B),
    primaryContainer = Color(0xFF3A3E7A),
    onPrimaryContainer = IndigoLight,
    secondary = Color(0xFFB9BDFF),
    onSecondary = Color(0xFF23286B),
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
)

@Composable
fun LocalKeysTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
