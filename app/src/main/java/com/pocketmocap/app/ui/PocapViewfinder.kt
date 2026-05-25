package com.pocketmocap.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Riso-style mock camera viewfinder used by the beige Sync/Calibration screen
 * (the live ArCore preview can only occupy one surface, so off-camera previews
 * use this stylised stand-in). Dark cool gradient with a soft horizon, matching
 * the prototype `Viewfinder` component.
 */
@Composable
fun PocapMockViewfinder(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier.background(
            if (dark) {
                Brush.verticalGradient(listOf(Color(0xFF14181F), Color(0xFF08090C)))
            } else {
                Brush.verticalGradient(
                    0.0f to Color(0xFF1B2030),
                    0.6f to Color(0xFF0E1117),
                    1.0f to Color(0xFF07090C),
                )
            }
        ),
    ) {
        if (!dark) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // soft violet horizon line
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, PocapViolet.copy(alpha = 0.35f), Color.Transparent)
                    ),
                    start = Offset(0f, size.height * 0.58f),
                    end = Offset(size.width, size.height * 0.58f),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
        content()
    }
}

/** Canonical mock pose overlay (cyan torso, violet arms, pink legs) for previews. */
@Composable
fun PocapMockSkeleton(
    modifier: Modifier = Modifier,
    confidence: Float = 1f,
) {
    val pose = mapOf(
        "head" to Offset(0.51f, 0.18f), "neck" to Offset(0.50f, 0.28f),
        "lsh" to Offset(0.40f, 0.30f), "rsh" to Offset(0.61f, 0.31f),
        "lel" to Offset(0.30f, 0.39f), "rel" to Offset(0.72f, 0.41f),
        "lwr" to Offset(0.24f, 0.28f), "rwr" to Offset(0.78f, 0.30f),
        "lhp" to Offset(0.45f, 0.54f), "rhp" to Offset(0.56f, 0.54f),
        "lkn" to Offset(0.39f, 0.71f), "rkn" to Offset(0.61f, 0.73f),
        "lan" to Offset(0.35f, 0.87f), "ran" to Offset(0.64f, 0.89f),
    )
    val alpha = confidence.coerceIn(0.4f, 1f)
    Canvas(modifier = modifier.fillMaxSize()) {
        fun p(k: String) = pose.getValue(k).let { Offset(it.x * size.width, it.y * size.height) }
        fun bone(a: String, b: String, c: Color) = drawLine(
            color = c.copy(alpha = alpha), start = p(a), end = p(b),
            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
        )
        // torso (cyan)
        bone("neck", "lsh", PocapCyan); bone("neck", "rsh", PocapCyan)
        bone("neck", "lhp", PocapCyan); bone("neck", "rhp", PocapCyan)
        bone("lhp", "rhp", PocapCyan)
        // arms (violet)
        bone("lsh", "lel", PocapViolet); bone("lel", "lwr", PocapViolet)
        bone("rsh", "rel", PocapViolet); bone("rel", "rwr", PocapViolet)
        // legs (pink)
        bone("lhp", "lkn", PocapPink); bone("lkn", "lan", PocapPink)
        bone("rhp", "rkn", PocapPink); bone("rkn", "ran", PocapPink)
        // head ring
        val head = p("head")
        drawCircle(Color(0x731A1814), radius = 7.dp.toPx(), center = head)
        drawCircle(PocapCyan.copy(alpha = alpha), radius = 7.dp.toPx(), center = head, style = Stroke(2.5.dp.toPx()))
        // joints
        fun joint(k: String, c: Color) {
            drawCircle(PocapInk, radius = 4.5.dp.toPx(), center = p(k))
            drawCircle(c.copy(alpha = alpha), radius = 4.5.dp.toPx(), center = p(k), style = Stroke(2.dp.toPx()))
        }
        listOf("lsh", "rsh", "lhp", "rhp").forEach { joint(it, PocapCyan) }
        listOf("lel", "rel", "lwr", "rwr").forEach { joint(it, PocapViolet) }
        listOf("lkn", "rkn", "lan", "ran").forEach { joint(it, PocapPink) }
    }
}

/** ARCore-style floor anchor markers (4 corner crosshairs + quad). */
@Composable
fun PocapFloorMarkers(
    modifier: Modifier = Modifier,
    detecting: Boolean = false,
) {
    val color = if (detecting) PocapViolet else PocapCyan
    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height * 0.78f
        val w = size.width * 0.5f
        val h = size.height * 0.12f
        val dash = if (detecting) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null
        val pts = listOf(
            Offset(cx - w / 2f, cy - h / 2f),
            Offset(cx + w / 2f, cy - h / 2f),
            Offset(cx - w / 2f, cy + h / 2f),
            Offset(cx + w / 2f, cy + h / 2f),
        )
        val arm = 10.dp.toPx()
        pts.forEach { pt ->
            drawLine(color, Offset(pt.x - arm, pt.y), Offset(pt.x + arm, pt.y), strokeWidth = 2.dp.toPx(), pathEffect = dash)
            drawLine(color, Offset(pt.x, pt.y - arm), Offset(pt.x, pt.y + arm), strokeWidth = 2.dp.toPx(), pathEffect = dash)
        }
        // quad outline
        val quad = listOf(pts[0], pts[1], pts[3], pts[2], pts[0])
        for (i in 0 until quad.size - 1) {
            drawLine(color.copy(alpha = 0.5f), quad[i], quad[i + 1], strokeWidth = 2.dp.toPx(), pathEffect = dash)
        }
    }
}

/** Lens / camera glyph rendered on a 24-unit canvas (matches prototype PMC.camera). */
@Composable
fun PocapCameraGlyph(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = PocapInk,
) {
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.width / 24f
        val sw = 1.6f * u
        // body
        drawRoundRect(
            color = color,
            topLeft = Offset(2.5f * u, 6f * u),
            size = Size(19f * u, 13.5f * u),
            cornerRadius = CornerRadius(2.5f * u, 2.5f * u),
            style = Stroke(sw),
        )
        // lens
        drawCircle(color, radius = 3.8f * u, center = Offset(12f * u, 13f * u), style = Stroke(sw))
        drawCircle(color, radius = 1.5f * u, center = Offset(12f * u, 13f * u))
        // top bump  M8 6 l1.2 -2.4 h5.6 L16 6
        val path = Path().apply {
            moveTo(8f * u, 6f * u)
            lineTo(9.2f * u, 3.6f * u)
            lineTo(14.8f * u, 3.6f * u)
            lineTo(16f * u, 6f * u)
        }
        drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round))
    }
}
