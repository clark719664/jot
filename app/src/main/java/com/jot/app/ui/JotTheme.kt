package com.jot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF315DC0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDF2FF),
    onPrimaryContainer = Color(0xFF193F97),
    background = Color.White,
    surface = Color.White,
    onBackground = Color(0xFF1D2026),
    onSurface = Color(0xFF1D2026),
    onSurfaceVariant = Color(0xFF666B76),
    outline = Color(0xFF747986),
    outlineVariant = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFFF3F5F8),
    surfaceContainerHigh = Color(0xFFF3F5F8),
    inverseSurface = Color(0xFF24272D),
    inverseOnSurface = Color(0xFFF0F1F4),
    inversePrimary = Color(0xFFADC4FF),
    error = Color(0xFFA62C35),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFADC4FF),
    onPrimary = Color(0xFF122348),
    primaryContainer = Color(0xFF202D47),
    onPrimaryContainer = Color(0xFFD9E3FF),
    background = Color(0xFF111315),
    surface = Color(0xFF111315),
    onBackground = Color(0xFFF0F1F4),
    onSurface = Color(0xFFF0F1F4),
    onSurfaceVariant = Color(0xFFA4AAB6),
    outline = Color(0xFF9198A4),
    outlineVariant = Color(0xFF30343B),
    surfaceVariant = Color(0xFF20242A),
    surfaceContainerHigh = Color(0xFF20242A),
    inverseSurface = Color(0xFFF0F1F4),
    inverseOnSurface = Color(0xFF1D2026),
    inversePrimary = Color(0xFF315DC0),
    error = Color(0xFFFFB0B7),
)

private val JotTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-1.1).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.45).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 25.sp,
    ),
)

@Composable
fun JotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = JotTypography,
        content = content,
    )
}
