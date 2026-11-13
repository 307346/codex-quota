package com.lcs.codexquota.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Ink {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF12161C)
    val SurfaceHigh = Color(0xFF1A2027)
    val Border = Color(0xFF232B34)
    val Accent = Color(0xFF10A37F)
    val AccentSoft = Color(0xFF4FD1C5)
    val TextPrimary = Color(0xFFE9EEF4)
    val TextMuted = Color(0xFF8A97A6)
    val Warn = Color(0xFFE0A458)
    val Danger = Color(0xFFE05C5C)
}

private val Scheme = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Color(0xFF04110D),
    secondary = Ink.AccentSoft,
    background = Ink.Background,
    onBackground = Ink.TextPrimary,
    surface = Ink.Surface,
    onSurface = Ink.TextPrimary,
    surfaceVariant = Ink.SurfaceHigh,
    onSurfaceVariant = Ink.TextMuted,
    outline = Ink.Border,
    error = Ink.Danger,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.6.sp),
)

@Composable
fun CodexQuotaTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, content = content)
}
