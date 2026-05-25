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
val Cloud = Color(0xFFF4ECD9)
val CloudWarm = Color(0xFFEDE6D6)
val Ink = Color(0xFF1A1814)
val Slate = Color(0xFF4A4338)
val Mint = Color(0xFF22D3EE)
val MintBright = Color(0xFF22D3EE)
val MintDeep = Color(0xFF1A1814)
val Fog = Color(0xFFDCD1B8)
val RoseMist = Color(0xFFF472B6)
val Glass = Color(0xF2F4ECD9)
val GlassStrong = Color(0xEDEDE6D6)
val OutlineSoft = Color(0x66A39880)
val SkeletonBlue = Color(0xFF22D3EE)
val SkeletonMint = Color(0xFFA78BFA)
val SkeletonPink = Color(0xFFF472B6)
val TechnicalGrid = Color(0x6622D3EE)

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
        fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = 0.sp, color = Ink,
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = 0.sp, color = Ink,
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.sp, color = Ink,
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

private val DarkScheme = darkColorScheme(
    background = Color(0xFF0F1413),
    surface = Color(0xFF17201D),
    primary = Mint,
    onPrimary = Ink,
    secondary = MintBright,
    onSecondary = Ink,
    tertiary = RoseMist,
    onTertiary = Ink,
    surfaceVariant = Color(0xFF2A3331),
    onSurfaceVariant = Color(0xFFD1D7D4),
    outline = Color(0x33576B65),
    onSurface = Color(0xFFF2F6F4),
)

// ── Shapes ─────────────────────────────────────────────────────
private val PocketMocapShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(48.dp),
)

// ── Motion ─────────────────────────────────────────────────────
object PocketMocapMotion {
    const val ScreenTransitionMillis = 300
    const val ControlTransitionMillis = 160
    const val FadeThroughMillis = 280

    val EmphasizedEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

    val ScreenTween: FiniteAnimationSpec<Float> =
        tween(durationMillis = ScreenTransitionMillis, easing = EmphasizedEasing)

    val ControlTween: FiniteAnimationSpec<Float> =
        tween(durationMillis = ControlTransitionMillis, easing = EmphasizedEasing)

    val BubbleSpring: SpringSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
}

// ── Theme composable ───────────────────────────────────────────
@Composable
fun PocketMocapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = PocketMocapTypography,
        shapes = PocketMocapShapes,
        content = content,
    )
}
