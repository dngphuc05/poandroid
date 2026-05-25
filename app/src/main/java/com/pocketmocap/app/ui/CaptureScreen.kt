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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { cameraCapture.previewView },
            )
            RealLandmarkOverlay(
                x = viewModel.poseLandmarksX,
                y = viewModel.poseLandmarksY,
                visibility = viewModel.poseVisibility,
            )
            PocapCameraScrim()
        } else {
            PocapPaperScaffold {
                PermissionPanel(
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            CaptureTopBar(
                uiState = uiState,
                visibleLandmarks = viewModel.visibleLandmarkCount,
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
    onDisconnect: () -> Unit,
) {
    PocapCard(color = PocapPaper.copy(alpha = 0.94f), radius = 18.dp) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PocapLogoMark(modifier = Modifier.size(30.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pocap capture node",
                    style = MaterialTheme.typography.titleSmall,
                    color = PocapInk,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Session ${uiState.sessionId.ifBlank { "pending" }} - $visibleLandmarks/33 landmarks",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocapInk2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PocapChip(
                label = if (uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING) "live" else "ready",
                tone = if (uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING) PocapCyan else PocapPaperLight,
                dot = true,
            )
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clickable(onClick = onDisconnect),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.LinkOff, contentDescription = "Leave session", tint = PocapInk)
            }
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
    PocapCard(color = PocapPaper.copy(alpha = 0.96f), radius = 22.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PocapStageRail(
                stages = listOf("cam", "sync", "stream"),
                activeIndex = stageIndex(uiState, hasCameraPermission),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PocapMetricTile(
                    label = "camera",
                    value = if (hasCameraPermission) "active" else "blocked",
                    active = hasCameraPermission,
                    modifier = Modifier.weight(1f),
                )
                PocapMetricTile(
                    label = "mediapipe",
                    value = "${viewModel.visibleLandmarkCount}/33",
                    active = viewModel.visibleLandmarkCount >= 18,
                    modifier = Modifier.weight(1f),
                )
                PocapMetricTile(
                    label = "server",
                    value = viewModel.lastServerTransport.uppercase(),
                    active = viewModel.framesSentToServer > 0,
                    modifier = Modifier.weight(1f),
                )
            }

            PocapCard(color = PocapPaperLight, shadow = false) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PocapEyebrow("capture payload")
                    PocapPayloadRow("2D landmarks", "${viewModel.visibleLandmarkCount} visible joints")
                    PocapPayloadRow("Frame metadata", "${viewModel.cameraImageWidth}x${viewModel.cameraImageHeight} timestamped")
                    PocapPayloadRow("World tracking", viewModel.latestWorldTracking?.trackingState ?: "waiting")
                    PocapPayloadRow("Scene metrics", viewModel.latestSceneMetrics?.source ?: "waiting")
                    PocapPayloadRow("Server stream", "${viewModel.framesSentToServer} sent / ${viewModel.pose3DReceivedCount} acks")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PocapButton(
                    label = if (uiState.calibrationStep == PocketMocapViewModel.CalibrationStep.COMPLETE) "Recalibrate" else "Start Sync",
                    enabled = hasCameraPermission,
                    onClick = onStartCalibration,
                    modifier = Modifier.weight(1f),
                    tone = PocapCyan,
                    contentColor = PocapInk,
                )
                PocapButton(
                    label = if (viewModel.isCaptureRecording) "Stop Log" else "Log Raw",
                    enabled = hasCameraPermission,
                    onClick = { viewModel.toggleCaptureRecording() },
                    modifier = Modifier.weight(1f),
                    tone = if (viewModel.isCaptureRecording) PocapPink else PocapPaperLight,
                    contentColor = PocapInk,
                )
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

@Composable
private fun PermissionPanel(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PocapCard(modifier = modifier.padding(24.dp), radius = 22.dp) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PocapLogoMark(modifier = Modifier.size(48.dp))
            Text("Camera permission needed", style = MaterialTheme.typography.titleMedium, color = PocapInk)
            Text(
                "Pocap needs camera frames to run MediaPipe 2D landmarks and stream timestamped capture evidence to the server.",
                style = MaterialTheme.typography.bodySmall,
                color = PocapInk2,
            )
            PocapButton(
                label = "Allow Camera",
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                tone = PocapCyan,
                contentColor = PocapInk,
            )
        }
    }
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
