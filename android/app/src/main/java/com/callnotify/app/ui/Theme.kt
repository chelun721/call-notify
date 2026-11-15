package com.callnotify.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE9FF),
    onPrimaryContainer = Color(0xFF072A5C),
    secondary = Color(0xFF4A6088),
    onSecondary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF14171C),
    surface = Color.White,
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = Color(0xFF5C6674),
    outline = Color(0xFFDCE2EA),
    error = Color(0xFFD33A3A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FAEF8),
    onPrimary = Color(0xFF08213F),
    primaryContainer = Color(0xFF12365F),
    onPrimaryContainer = Color(0xFFD8E6FF),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE7EAF0),
    surface = Color(0xFF171B21),
    onSurface = Color(0xFFE7EAF0),
    surfaceVariant = Color(0xFF222833),
    onSurfaceVariant = Color(0xFFA6AFBD),
    outline = Color(0xFF2B333F),
    error = Color(0xFFF08A8A),
    onError = Color(0xFF2A0A0A),
)

@Composable
fun CallNotifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
