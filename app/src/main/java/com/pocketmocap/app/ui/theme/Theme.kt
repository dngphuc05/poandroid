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

