package com.sahidcode404.photonex2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhotonColors = darkColorScheme(
    primary = Color(0xFFE8EAED),
    onPrimary = Color(0xFF111315),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF202327),
    onSurfaceVariant = Color(0xFFD9DCE1),
    secondary = Color(0xFFA8C7FA),
)

@Composable
fun PhotonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PhotonColors, content = content)
}
