package com.pocketmocap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketmocap.app.PocketMocapViewModel
import com.pocketmocap.app.camera.ArCoreFrameCapture
import com.pocketmocap.app.pipeline.HybridPosePipeline

@Composable
fun CaptureScreen(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    onStartCalibration: () -> Unit,
    onDisconnect: () -> Unit,
    onClearError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val cameraCapture = remember {
        ArCoreFrameCapture(
            context = context,
            onFrame = { frame -> viewModel.onCameraFrame(frame) },
        )
    }

    LaunchedEffect(uiState.manualCameraHeightMeters) {
        cameraCapture.setManualCameraHeightMeters(uiState.manualCameraHeightMeters)
    }

    DisposableEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            Log.i("CaptureScreen", "Starting Pocap phone capture node")
            cameraCapture.start(lifecycleOwner)
        }
        onDispose {
            Log.i("CaptureScreen", "Stopping Pocap phone capture node")
            cameraCapture.stop()
        }
    }

    if (!hasCameraPermission) {
        PermissionPanel(
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onCancel = onDisconnect,
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { cameraCapture.previewView },
        )
        RealLandmarkOverlay(
            x = viewModel.poseLandmarksX,
            y = viewModel.poseLandmarksY,
            visibility = viewModel.poseVisibility,
        )
        PocapCornerBrackets()
        PocapCameraScrim()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            CaptureTopBar(
                uiState = uiState,
                visibleLandmarks = viewModel.visibleLandmarkCount,
                viewModel = viewModel,
                onDisconnect = onDisconnect,
            )
            CaptureBottomSheet(
                uiState = uiState,
                viewModel = viewModel,
                hasCameraPermission = hasCameraPermission,
                onStartCalibration = onStartCalibration,
                onClearError = onClearError,
            )
        }
    }
}

@Composable
private fun CaptureTopBar(
    uiState: PocketMocapViewModel.UiState,
    visibleLandmarks: Int,
    viewModel: PocketMocapViewModel,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SessionPill(
                code = formatSessionCode(uiState.joinedLobbyCode),
                status = if (uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING) "streaming" else "cam node",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PocapIconButton(onClick = { }, tone = PocapPaper.copy(alpha = 0.92f), enabled = false) {
                    Text("...", color = PocapInk, fontWeight = FontWeight.Bold)
                }
                PocapIconButton(onClick = onDisconnect, tone = PocapPaper.copy(alpha = 0.92f)) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = "Leave session", tint = PocapInk)
                }
            }
        }

        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            PocapChip(
                label = captureStatusLabel(uiState, visibleLandmarks),
                tone = captureStatusTone(uiState, visibleLandmarks),
                dot = true,
            )
            PocapChip(
                label = if (uiState.calibrationStep == PocketMocapViewModel.CalibrationStep.COMPLETE) {
                    "PC can record"
                } else {
                    "waiting for pc"
                },
                tone = PocapPaper.copy(alpha = 0.92f),
            )
        }

        captureWarning(uiState, viewModel.visibleLandmarkCount)?.let { warning ->
            WarningBanner(warning)
        }
    }
}

@Composable
private fun CaptureBottomSheet(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    hasCameraPermission: Boolean,
    onStartCalibration: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (viewModel.isCaptureRecording) {
            LiveRecordingStrip(uiState = uiState, viewModel = viewModel)
        }
        MetricsRowCard(uiState = uiState, viewModel = viewModel)
        DeviceActionCard(
            uiState = uiState,
            viewModel = viewModel,
            hasCameraPermission = hasCameraPermission,
            onStartCalibration = onStartCalibration,
            onClearError = onClearError,
        )
    }
}

@Composable
private fun PermissionPanel(
    onRequestPermission: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PocapPaperScaffold {
        Column(
            modifier = modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 28.dp),
            ) {
                PocapIconButton(onClick = onCancel, tone = PocapPaperLight) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = "Back", tint = PocapInk)
                }
                PocapEyebrow("Step 3 - camera")
            }

            PocapCard(radius = 20.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PocapLogoMark(modifier = Modifier.size(74.dp), color = PocapInk)
                    Spacer(modifier = Modifier.height(16.dp))
                    PocapChip(label = "required", tone = PocapViolet, dot = true)
                }
            }
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "Open your\ncamera.",
                style = MaterialTheme.typography.headlineLarge,
                color = PocapInk,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Your phone reads MediaPipe 2D landmarks from the camera and streams timestamped capture evidence to the PC server.",
                style = MaterialTheme.typography.bodyMedium,
                color = PocapInk2,
                modifier = Modifier.padding(top = 12.dp, bottom = 18.dp),
            )
            PermissionFact("On-device pose landmarks")
            PermissionFact("Streams 33 joints plus frame metadata")
            PermissionFact("The PC controls capture and reconstruction")
            Spacer(modifier = Modifier.weight(1f))
            PocapButton(
                label = "Allow Camera",
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                tone = PocapCyan,
                contentColor = PocapInk,
            )
            Text(
                text = "Cancel - back to session",
                style = MaterialTheme.typography.bodySmall,
                color = PocapInk3,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCancel)
                    .padding(top = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PermissionFact(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PocapCard(color = PocapPaperLight, radius = 8.dp, shadow = false) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                PocapLogoMark(modifier = Modifier.size(15.dp))
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = PocapInk2,
        )
    }
}

@Composable
private fun SessionPill(
    code: String,
    status: String,
) {
    PocapCard(color = PocapPaper.copy(alpha = 0.94f), radius = 999.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PocapLogoMark(modifier = Modifier.size(16.dp))
                Text(
                    text = code,
                    style = MaterialTheme.typography.labelMedium,
                    color = PocapInk,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .height(28.dp)
                    .background(PocapInk),
            )
            Text(
                text = status.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = PocapInk,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(PocapCyan)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                maxLines = 1,
            )
        }
    }
}

private data class CaptureWarning(
    val title: String,
    val body: String,
    val tone: Color,
)

@Composable
private fun WarningBanner(warning: CaptureWarning) {
    PocapCard(color = warning.tone, radius = 14.dp) {
        Row(
            modifier = Modifier.padding(11.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("!", color = Color.White, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = warning.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = warning.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.94f),
                )
            }
        }
    }
}

@Composable
private fun LiveRecordingStrip(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
) {
    PocapCard(color = PocapPaper.copy(alpha = 0.94f), radius = 18.dp) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PocapChip(label = "rec", tone = PocapPink, dot = true)
            PocapBigNum(
                label = "frames",
                value = uiState.frameCount.toString(),
                modifier = Modifier.weight(1f),
                tone = PocapInk,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PocapEyebrow("conf")
                PocapSignalBars(value = (viewModel.visibleLandmarkCount / 33f).coerceIn(0f, 1f))
            }
        }
    }
}

@Composable
private fun MetricsRowCard(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
) {
    PocapCard(color = PocapPaper.copy(alpha = 0.96f), radius = 18.dp) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PocapBigNum(
                label = "Tracking",
                value = "${viewModel.visibleLandmarkCount}/33",
                modifier = Modifier.weight(1f),
                tone = if (viewModel.visibleLandmarkCount >= 18) PocapInk else PocapPink,
            )
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .height(42.dp)
                    .background(PocapInk4),
            )
            PocapBigNum(
                label = "Stream",
                value = viewModel.framesSentToServer.toString(),
                modifier = Modifier.weight(1f),
                unit = "sent",
                tone = if (viewModel.framesSentToServer > 0) PocapCyan else PocapInk,
            )
            Box(
                modifier = Modifier
                    .width(1.5.dp)
                    .height(42.dp)
                    .background(PocapInk4),
            )
            PocapBigNum(
                label = "RTT",
                value = uiState.lastPipelineMs.toInt().coerceAtLeast(0).toString(),
                modifier = Modifier.weight(1f),
                unit = "ms",
                tone = if (uiState.lastPipelineMs > 80f) PocapPink else PocapViolet,
            )
        }
    }
}

@Composable
private fun DeviceActionCard(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    hasCameraPermission: Boolean,
    onStartCalibration: () -> Unit,
    onClearError: () -> Unit,
) {
    PocapCard(color = PocapPaper.copy(alpha = 0.96f), radius = 18.dp) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PocapStageRail(
                stages = listOf("cam", "sync", "stream"),
                activeIndex = stageIndex(uiState, hasCameraPermission),
            )
            PocapProgressBar(
                progress = calibrationProgress(uiState),
                tone = if (uiState.calibrationStep == PocketMocapViewModel.CalibrationStep.COMPLETE) PocapCyan else PocapViolet,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PocapFactBox(
                    label = "Camera",
                    value = formatMeters(uiState.manualCameraHeightMeters),
                    tone = PocapViolet,
                    modifier = Modifier.weight(1f),
                )
                PocapFactBox(
                    label = "Height",
                    value = formatMeters(
                        viewModel.latestSceneMetrics?.correctedHeightMeters?.takeIf { it.isFinite() }
                            ?: viewModel.latestSceneMetrics?.bodyHeightMeters
                            ?: Float.NaN,
                    ),
                    tone = PocapCyan,
                    modifier = Modifier.weight(1f),
                )
                PocapFactBox(
                    label = "Age",
                    value = viewModel.lastPose3DAgeMs?.let { "${it} ms" } ?: "waiting",
                    tone = PocapPink,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    PocapEyebrow("device - role")
                    Text(
                        text = "Pocap phone - capture node",
                        style = MaterialTheme.typography.titleSmall,
                        color = PocapInk,
                        fontWeight = FontWeight.Bold,
                    )
                }
                PocapIconButton(
                    onClick = onStartCalibration,
                    tone = PocapViolet,
                    enabled = hasCameraPermission,
                ) {
                    Text("CAL", color = PocapInk, fontWeight = FontWeight.Bold)
                }
                PocapIconButton(
                    onClick = { viewModel.toggleCaptureRecording() },
                    tone = if (viewModel.isCaptureRecording) PocapPink else PocapPaperLight,
                    enabled = hasCameraPermission,
                ) {
                    Text("LOG", color = PocapInk, fontWeight = FontWeight.Bold)
                }
            }
            PocapCard(color = PocapPaperLight, radius = 12.dp, shadow = false) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PocapEyebrow("capture payload")
                    PocapPayloadRow("2D landmarks", "${viewModel.visibleLandmarkCount} visible joints")
                    PocapPayloadRow("Frame metadata", "${viewModel.cameraImageWidth}x${viewModel.cameraImageHeight} timestamped")
                    PocapPayloadRow("World tracking", viewModel.latestWorldTracking?.trackingState ?: "waiting")
                    PocapPayloadRow("Scene metrics", viewModel.latestSceneMetrics?.source ?: "waiting")
                }
            }
            uiState.errorMessage?.let { error ->
                PocapCard(color = Color(0xFFFFEFEF), shadow = false) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapDanger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClearError)
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

private fun captureStatusLabel(
    uiState: PocketMocapViewModel.UiState,
    visibleLandmarks: Int,
): String = when {
    visibleLandmarks == 0 -> "No person"
    visibleLandmarks < 18 -> "Low tracking"
    uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING -> "Ready"
    else -> "Camera active"
}

private fun captureStatusTone(
    uiState: PocketMocapViewModel.UiState,
    visibleLandmarks: Int,
): Color = when {
    visibleLandmarks == 0 || visibleLandmarks < 18 -> PocapWarn
    uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING -> PocapCyan
    else -> PocapPaper
}

private fun captureWarning(
    uiState: PocketMocapViewModel.UiState,
    visibleLandmarks: Int,
): CaptureWarning? = when {
    uiState.errorMessage != null -> CaptureWarning(
        title = "Phone warning",
        body = uiState.errorMessage,
        tone = PocapDanger,
    )
    visibleLandmarks == 0 -> CaptureWarning(
        title = "No person visible",
        body = "Adjust framing so the actor is visible before the PC starts sync.",
        tone = PocapWarn,
    )
    visibleLandmarks < 18 -> CaptureWarning(
        title = "Tracking weak",
        body = "$visibleLandmarks of 33 landmarks visible. Keep full body in frame.",
        tone = PocapWarn,
    )
    else -> null
}

private fun calibrationProgress(uiState: PocketMocapViewModel.UiState): Float? = when {
    uiState.calibrationStep == PocketMocapViewModel.CalibrationStep.COMPLETE -> 1f
    uiState.bootstrapProgress > 0f -> uiState.bootstrapProgress.coerceIn(0f, 1f)
    else -> null
}

private fun formatMeters(value: Float): String =
    if (value.isFinite()) {
        String.format(java.util.Locale.US, "%.2f m", value)
    } else {
        "waiting"
    }

@Composable
private fun RealLandmarkOverlay(
    x: FloatArray?,
    y: FloatArray?,
    visibility: FloatArray?,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (x == null || y == null) return@Canvas
        val count = minOf(x.size, y.size, visibility?.size ?: x.size)
        fun point(index: Int): Offset? {
            if (index !in 0 until count) return null
            val confidence = visibility?.getOrNull(index) ?: 1f
            if (confidence < 0.28f) return null
            return Offset(
                x[index].coerceIn(0f, 1f) * size.width,
                y[index].coerceIn(0f, 1f) * size.height,
            )
        }

        REAL_2D_EDGES.forEach { (a, b) ->
            val pa = point(a)
            val pb = point(b)
            if (pa != null && pb != null) {
                drawLine(
                    color = PocapCyan.copy(alpha = 0.72f),
                    start = pa,
                    end = pb,
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        for (index in 0 until count) {
            val p = point(index) ?: continue
            val confidence = visibility?.getOrNull(index)?.coerceIn(0f, 1f) ?: 1f
            drawCircle(
                color = PocapInk.copy(alpha = 0.72f),
                radius = 5.0.dp.toPx(),
                center = p,
            )
            drawCircle(
                color = when {
                    index in 13..22 -> PocapViolet
                    index >= 25 -> PocapPink
                    else -> PocapCyan
                }.copy(alpha = 0.42f + confidence * 0.42f),
                radius = 3.2.dp.toPx(),
                center = p,
            )
        }
    }
}

private fun stageIndex(
    uiState: PocketMocapViewModel.UiState,
    hasCameraPermission: Boolean,
): Int = when {
    !hasCameraPermission -> 0
    uiState.calibrationStep != PocketMocapViewModel.CalibrationStep.COMPLETE -> 1
    else -> 2
}

private fun formatSessionCode(code: String): String =
    code.filter(Char::isDigit).take(6).chunked(3).joinToString(" ").ifBlank { "pending" }

private val REAL_2D_EDGES = listOf(
    11 to 12,
    11 to 13,
    13 to 15,
    15 to 17,
    15 to 19,
    15 to 21,
    12 to 14,
    14 to 16,
    16 to 18,
    16 to 20,
    16 to 22,
    11 to 23,
    12 to 24,
    23 to 24,
    23 to 25,
    25 to 27,
    27 to 29,
    27 to 31,
    24 to 26,
    26 to 28,
    28 to 30,
    28 to 32,
)
