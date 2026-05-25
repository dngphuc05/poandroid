package com.pocketmocap.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val PocapPaper = Color(0xFFEDE6D6)
val PocapPaperLight = Color(0xFFF4ECD9)
val PocapPaperDeep = Color(0xFFDCD1B8)
val PocapInk = Color(0xFF1A1814)
val PocapInk2 = Color(0xFF4A4338)
val PocapInk3 = Color(0xFF7A6F5C)
val PocapInk4 = Color(0xFFA39880)
val PocapCyan = Color(0xFF22D3EE)
val PocapViolet = Color(0xFFA78BFA)
val PocapPink = Color(0xFFF472B6)
val PocapWarn = Color(0xFFE8843B)
val PocapDanger = Color(0xFFDC4A4A)

@Composable
fun PocapPaperScaffold(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocapPaper),
    ) {
        PaperGrain()
        content()
    }
}

@Composable
private fun PaperGrain() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 7.dp.toPx()
        var y = 1.dp.toPx()
        while (y < size.height) {
            var x = 1.dp.toPx()
            while (x < size.width) {
                drawCircle(
                    color = PocapInk.copy(alpha = 0.045f),
                    radius = 0.45.dp.toPx(),
                    center = Offset(x, y),
                )
                x += step
            }
            y += step
        }
    }
}

@Composable
fun PocapCard(
    modifier: Modifier = Modifier,
    color: Color = PocapPaperLight,
    radius: Dp = 14.dp,
    shadow: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(modifier = modifier) {
        if (shadow) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(3.dp, 3.dp)
                    .clip(shape)
                    .background(PocapInk),
            )
        }
        Surface(
            shape = shape,
            color = color,
            border = BorderStroke(1.5.dp, PocapInk),
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
fun PocapButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color = PocapInk,
    contentColor: Color = PocapPaperLight,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier = modifier.height(56.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(3.dp, 3.dp)
                .clip(shape)
                .background(PocapInk.copy(alpha = if (enabled) 1f else 0.35f)),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (enabled) tone else PocapPaperDeep)
                .border(2.dp, PocapInk.copy(alpha = if (enabled) 1f else 0.55f), shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) contentColor else PocapInk3,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PocapIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Color = PocapPaperLight,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier = modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(2.dp, 2.dp)
                .clip(shape)
                .background(PocapInk.copy(alpha = if (enabled) 1f else 0.35f)),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (enabled) tone else PocapPaperDeep)
                .border(2.dp, PocapInk.copy(alpha = if (enabled) 1f else 0.55f), shape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
fun PocapChip(
    label: String,
    modifier: Modifier = Modifier,
    tone: Color = PocapPaperLight,
    dot: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(tone)
            .border(1.5.dp, PocapInk, CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(PocapInk),
            )
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = PocapInk,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun PocapEyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = PocapInk3,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun PocapLogoMark(
    modifier: Modifier = Modifier,
    color: Color = PocapInk,
) {
    val paths: List<Path> = remember {
        listOf(
            "M 8.5 2 H 5 A 3 3 0 0 0 2 5 V 8.5",
            "M 15.5 2 H 19 A 3 3 0 0 1 22 5 V 8.5",
            "M 2 15.5 V 19 A 3 3 0 0 0 5 22 H 8.5",
            "M 22 15.5 V 19 A 3 3 0 0 1 19 22 H 15.5",
            "M 7.5 17 L 14.5 14.8 L 16.5 7.5",
        ).map { PathParser().parsePathString(it).toPath() }
    }

    Canvas(modifier = modifier.size(32.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            paths.forEach { path ->
                drawPath(path, color = color, style = Stroke(width = 2.1f, cap = StrokeCap.Round))
            }
            drawCircle(color, radius = 1.9f, center = Offset(7.5f, 17f))
            drawCircle(color, radius = 2.1f, center = Offset(14.5f, 14.8f))
            drawCircle(color, radius = 1.9f, center = Offset(16.5f, 7.5f))
        }
    }
}

@Composable
fun PocapMetricTile(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    PocapCard(
        modifier = modifier,
        color = PocapPaperLight,
        radius = 12.dp,
        shadow = false,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PocapEyebrow(label)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (active) PocapCyan else PocapInk4),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = PocapInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun PocapBigNum(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    tone: Color = PocapInk,
    align: TextAlign = TextAlign.Start,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        PocapEyebrow(label)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = tone,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            unit?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = PocapInk3,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun PocapProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    tone: Color = PocapViolet,
    height: Dp = 14.dp,
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(PocapPaperDeep)
            .border(1.5.dp, PocapInk, shape),
    ) {
        if (progress == null) {
            Row(modifier = Modifier.fillMaxSize()) {
                repeat(9) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(18.dp)
                            .background(if (index % 2 == 0) tone else tone.copy(alpha = 0.62f)),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(tone)
                    .border(0.dp, Color.Transparent),
            )
        }
    }
}

@Composable
fun PocapFactBox(
    label: String,
    value: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    PocapCard(
        modifier = modifier,
        color = PocapPaperLight,
        radius = 12.dp,
        shadow = false,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(tone),
            )
            PocapEyebrow(label, modifier = Modifier.padding(top = 3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = PocapInk,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PocapSignalBars(
    value: Float,
    modifier: Modifier = Modifier,
) {
    val filled = (value.coerceIn(0f, 1f) * 5f).toInt().coerceIn(0, 5)
    Row(
        modifier = modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(5) { index ->
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height((8 + index * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < filled) PocapCyan else PocapPaperDeep)
                    .border(1.dp, PocapInk, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
fun PocapCornerBrackets(
    modifier: Modifier = Modifier,
    color: Color = PocapPaper,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val inset = 10.dp.toPx()
        val arm = 30.dp.toPx()
        val stroke = 2.5.dp.toPx()
        drawLine(color, Offset(inset, inset + arm), Offset(inset, inset), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(inset, inset), Offset(inset + arm, inset), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width - inset - arm, inset), Offset(size.width - inset, inset), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, inset), Offset(size.width - inset, inset + arm), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(inset, size.height - inset - arm), Offset(inset, size.height - inset), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(inset, size.height - inset), Offset(inset + arm, size.height - inset), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width - inset - arm, size.height - inset), Offset(size.width - inset, size.height - inset), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - inset - arm), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun PocapStageRail(
    stages: List<String>,
    activeIndex: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        stages.forEachIndexed { index, stage ->
            val live = index == activeIndex
            val done = index < activeIndex
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(if (live) 12.dp else 9.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> PocapCyan
                                live -> PocapViolet
                                else -> PocapPaperDeep
                            }
                        )
                        .border(1.5.dp, PocapInk, CircleShape),
                )
                Text(
                    text = stage.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) PocapInk else PocapInk3,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
fun PocapCameraScrim(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.38f),
                        Color.Transparent,
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.56f),
                    )
                )
            ),
    )
}

@Composable
fun PocapPayloadRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(PocapInk),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = PocapInk,
            modifier = Modifier.weight(0.42f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = PocapInk2,
            modifier = Modifier.weight(0.58f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
