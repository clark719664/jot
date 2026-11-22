package com.jot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF815700),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8A7),
    onPrimaryContainer = Color(0xFF583A00),
    background = Color(0xFFF3F3F6),
    surface = Color.White,
    onBackground = Color(0xFF19191C),
    onSurface = Color(0xFF19191C),
    onSurfaceVariant = Color(0xFF65656D),
    outline = Color(0xFF797980),
    outlineVariant = Color(0xFFDBDBE0),
    surfaceVariant = Color(0xFFE8E8ED),
    surfaceContainer = Color(0xFFF3F3F6),
    surfaceContainerLow = Color(0xFFF6F6F8),
    surfaceContainerLowest = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE8E8ED),
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFFFCF59),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFCF59),
    onPrimary = Color(0xFF3D2800),
    primaryContainer = Color(0xFF493600),
    onPrimaryContainer = Color(0xFFFFDE85),
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    onBackground = Color(0xFFF6F6F8),
    onSurface = Color(0xFFF6F6F8),
    onSurfaceVariant = Color(0xFFADADB4),
    outline = Color(0xFF8C8C92),
    outlineVariant = Color(0xFF38383C),
    surfaceVariant = Color(0xFF29292D),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF171719),
    surfaceContainerLowest = Color.Black,
    surfaceContainerHigh = Color(0xFF29292D),
    surfaceContainerHighest = Color(0xFF303034),
    inverseSurface = Color(0xFFE9E9EB),
    inverseOnSurface = Color(0xFF19191C),
    inversePrimary = Color(0xFF815700),
)

private val JotTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-1.2).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = (-0.16).sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp),
)

@Composable
fun JotTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = JotTypography, content = content)
}
