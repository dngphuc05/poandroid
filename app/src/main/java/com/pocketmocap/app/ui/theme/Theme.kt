package com.pocketmocap.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketmocap.app.R

// ── Design tokens ──────────────────────────────────────────────
val Cloud = Color(0xFFF5F7F9)
val CloudWarm = Color(0xFFF5F4EB)
val Ink = Color(0xFF2C2F31)
val Slate = Color(0xFF595C5E)
val Mint = Color(0xFF89F0CB)
val MintBright = Color(0xFF98FFD9)
val MintDeep = Color(0xFF00684F)
val Fog = Color(0xFFD9DDE0)
val RoseMist = Color(0xFFFAD3FD)
val Glass = Color(0xCCFFFFFF)
val GlassStrong = Color(0x99D9DDE0)
val OutlineSoft = Color(0x33BAB9B2)
val SkeletonBlue = Color(0xFFBEEBFF)
val SkeletonMint = Color(0xFF7CF4C8)
val SkeletonPink = Color(0xFFF8D6FF)
val TechnicalGrid = Color(0x332CCFD0)

/** Backward-compatible aliases used by existing ViewModel / screens. */
object MocapColors {
    val GreenDark = MintDeep
    val GreenMid = Color(0xFF007353)
    val Mint = com.pocketmocap.app.ui.theme.Mint
    val MintBright = com.pocketmocap.app.ui.theme.MintBright
    val TextPrimary = Ink
    val TextSecondary = Slate
    val BackgroundHeader = Cloud
    val BackgroundViewport = CloudWarm
    val GlassBg = GlassStrong
    val GlassBgLight = Color(0xCCF5F7F9)
}

// ── Fonts ──────────────────────────────────────────────────────
private val DisplayFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_variable, weight = FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_variable, weight = FontWeight.ExtraBold),
)

private val BodyFontFamily = FontFamily(
    Font(R.font.be_vietnam_pro_regular, weight = FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, weight = FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semi_bold, weight = FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, weight = FontWeight.Bold),
)

// ── Typography ─────────────────────────────────────────────────
private val PocketMocapTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.ExtraBold,
        fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-1.4).sp, color = Ink,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp, color = Ink,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.6).sp, color = Ink,
    ),
    titleMedium = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 24.sp, color = Ink,
    ),
    titleSmall = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, lineHeight = 20.sp, color = Ink,
    ),
    bodyLarge = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 18.sp, lineHeight = 28.sp, letterSpacing = 0.45.sp, color = Slate,
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, color = Slate,
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, color = Slate,
    ),
    labelLarge = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, lineHeight = 20.sp, color = Ink,
    ),
    labelMedium = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp, color = Slate,
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 1.sp, color = Slate,
    ),
)

// ── Color Schemes ──────────────────────────────────────────────
private val LightScheme = lightColorScheme(
    background = Cloud,
    surface = CloudWarm,
    primary = MintDeep,
    onPrimary = Color.White,
    secondary = Mint,
    onSecondary = Ink,
    tertiary = RoseMist,
    onTertiary = Ink,
    surfaceVariant = Fog,
    onSurfaceVariant = Slate,
    outline = OutlineSoft,
    onSurface = Ink,
)

