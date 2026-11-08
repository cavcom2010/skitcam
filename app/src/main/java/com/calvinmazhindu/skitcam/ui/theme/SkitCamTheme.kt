package com.calvinmazhindu.skitcam.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SkitCam's warm-rose-on-ink identity. Semantic tokens are intentionally
 * separate from Material's defaults so camera overlays and utility screens
 * share one palette without duplicating colour literals.
 */
object SkitCamColors {
    val Ink = Color(0xFF0E0F12)
    val InkSoft = Color(0xFF15161B)
    val Surface = Color(0xFF1A1B21)
    val SurfaceRaised = Color(0xFF24252C)
    val SurfacePressed = Color(0xFF302B32)
    val Rose = Color(0xFFC96873)
    val RoseStrong = Color(0xFF9E4351)
    val RoseDeep = Color(0xFF71313D)
    val Warm = Color(0xFFF2D4BC)
    val TextPrimary = Color(0xFFF7F5F2)
    val TextSecondary = Color(0xFFBEB9B5)
    val TextMuted = Color(0xFF8F8A87)
    val Success = Color(0xFF8FC7A1)
    val Warning = Color(0xFFF0C27A)
    val Error = Color(0xFFFFB4AB)
    val Scrim = Color(0xCC000000)
    val GridLine = Color(0x66FFFFFF)
}

object SkitCamSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val touchTarget = 48.dp
    val shutter = 80.dp
}

object SkitCamShapes {
    val small = RoundedCornerShape(10.dp)
    val medium = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
}

private val SkitCamColorScheme = darkColorScheme(
    primary = SkitCamColors.Rose,
    onPrimary = Color.White,
    primaryContainer = SkitCamColors.RoseStrong,
    onPrimaryContainer = Color.White,
    secondary = SkitCamColors.Warm,
    onSecondary = SkitCamColors.Ink,
    secondaryContainer = SkitCamColors.SurfacePressed,
    onSecondaryContainer = SkitCamColors.Warm,
    tertiary = SkitCamColors.Success,
    background = SkitCamColors.Ink,
    onBackground = SkitCamColors.TextPrimary,
    surface = SkitCamColors.Surface,
    onSurface = SkitCamColors.TextPrimary,
    surfaceVariant = SkitCamColors.SurfaceRaised,
    onSurfaceVariant = SkitCamColors.TextSecondary,
    outline = SkitCamColors.TextMuted,
    error = SkitCamColors.Error,
    onError = SkitCamColors.Ink,
    scrim = SkitCamColors.Scrim
)

private val SkitCamTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
)

private val SkitCamMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = SkitCamShapes.small,
    medium = SkitCamShapes.medium,
    large = SkitCamShapes.large,
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SkitCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SkitCamColorScheme,
        typography = SkitCamTypography,
        shapes = SkitCamMaterialShapes,
        content = content
    )
}
