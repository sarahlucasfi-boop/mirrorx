package com.mirrorx.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MirrorDarkColors = darkColorScheme(
    primary = MirrorAccent,
    onPrimary = MirrorText,
    background = MirrorBackground,
    onBackground = MirrorText,
    surface = MirrorSurface,
    onSurface = MirrorText,
    surfaceVariant = MirrorSurfaceVariant,
    onSurfaceVariant = MirrorTextDim,
    outline = MirrorBorder,
    error = MirrorRed,
    onError = MirrorText,
)

val MirrorTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.W800,
        fontSize = 32.sp,
        letterSpacing = (-1).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.W700,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 13.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun MirrorXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MirrorDarkColors,
        typography = MirrorTypography,
        content = content,
    )
}