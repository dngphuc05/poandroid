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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pocketmocap.app.PocketMocapViewModel
import com.pocketmocap.app.camera.ArCoreFrameCapture
import com.pocketmocap.app.pipeline.HybridPosePipeline
import com.pocketmocap.app.ui.theme.CloudWarm
import com.pocketmocap.app.ui.theme.Glass
import com.pocketmocap.app.ui.theme.Ink
import com.pocketmocap.app.ui.theme.Mint
import com.pocketmocap.app.ui.theme.MintBright
import com.pocketmocap.app.ui.theme.MintDeep
import com.pocketmocap.app.ui.theme.OutlineSoft
import com.pocketmocap.app.ui.theme.RoseMist
import com.pocketmocap.app.ui.theme.Slate

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CloudWarm),
    ) {
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { cameraCapture.previewView },
            )
            LandmarkConfidenceOverlay(
                x = viewModel.poseLandmarksX,
                y = viewModel.poseLandmarksY,
                visibility = viewModel.poseVisibility,
            )
        } else {
            PermissionRequiredPanel(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            CaptureHeader(
                uiState = uiState,
                visibleLandmarks = viewModel.visibleLandmarkCount,
                onDisconnect = onDisconnect,
            )

            CaptureStatusSheet(
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
private fun CaptureHeader(
    uiState: PocketMocapViewModel.UiState,
    visibleLandmarks: Int,
    onDisconnect: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Glass,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusOrb(
                active = uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING,
                label = "capture",
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pocap Capture Node",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Session ${uiState.sessionId.ifBlank { "pending" }} · $visibleLandmarks landmarks",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onDisconnect),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                    Text("Leave", style = MaterialTheme.typography.labelLarge, color = Ink)
                }
            }
        }
    }
}

@Composable
private fun CaptureStatusSheet(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    hasCameraPermission: Boolean,
    onStartCalibration: () -> Unit,
    onClearError: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Glass,
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusMetric(
                    label = "Camera",
                    value = if (hasCameraPermission) "Active" else "Permission",
                    active = hasCameraPermission,
                    modifier = Modifier.weight(1f),
                )
                StatusMetric(
                    label = "MediaPipe",
                    value = "${viewModel.visibleLandmarkCount}/33",
                    active = viewModel.visibleLandmarkCount >= 18,
                    modifier = Modifier.weight(1f),
                )
                StatusMetric(
                    label = "Server",
                    value = viewModel.lastServerTransport.uppercase(),
                    active = viewModel.framesSentToServer > 0,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusMetric(
                    label = "Frames Sent",
                    value = viewModel.framesSentToServer.toString(),
                    active = viewModel.framesSentToServer > 0,
                    modifier = Modifier.weight(1f),
                )
                StatusMetric(
                    label = "Server Acks",
                    value = viewModel.pose3DReceivedCount.toString(),
                    active = viewModel.pose3DReceivedCount > 0,
                    modifier = Modifier.weight(1f),
                )
                StatusMetric(
                    label = "Pipeline",
                    value = "${uiState.lastPipelineMs.toInt()} ms",
                    active = uiState.lastPipelineMs in 1f..34f,
                    modifier = Modifier.weight(1f),
                )
            }

            PipelineSummary(uiState = uiState, viewModel = viewModel)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptureActionButton(
                    label = if (uiState.calibrationStep == PocketMocapViewModel.CalibrationStep.COMPLETE) {
                        "Recalibrate"
                    } else {
                        "Start Sync"
                    },
                    enabled = hasCameraPermission,
                    primary = true,
                    onClick = onStartCalibration,
                    modifier = Modifier.weight(1f),
                )
                CaptureActionButton(
                    label = if (viewModel.isCaptureRecording) "Stop Log" else "Log Raw",
                    enabled = hasCameraPermission,
                    primary = false,
                    onClick = { viewModel.toggleCaptureRecording() },
                    modifier = Modifier.weight(1f),
                )
            }

            uiState.errorMessage?.let { error ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFFEFEF),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClearError),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9B1C1C),
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PipelineSummary(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.86f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("CAPTURE PAYLOAD", style = MaterialTheme.typography.labelMedium, color = Slate)
            PayloadRow("2D landmarks", "${viewModel.visibleLandmarkCount} visible joints")
            PayloadRow(
                "Frame metadata",
                "${viewModel.cameraImageWidth}x${viewModel.cameraImageHeight} · timestamped",
            )
            PayloadRow("World tracking", viewModel.latestWorldTracking?.trackingState ?: "waiting")
            PayloadRow("Scene metrics", viewModel.latestSceneMetrics?.source ?: "waiting")
            PayloadRow("Calibration", uiState.calibrationStep.name.lowercase().replace('_', ' '))
            PayloadRow(
                "Server output",
                "received for diagnostics only; 3D display lives in Pocap PC",
            )
        }
    }
}

@Composable
private fun PayloadRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MintDeep.copy(alpha = 0.72f)),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Ink, modifier = Modifier.weight(0.42f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = Slate,
            modifier = Modifier.weight(0.58f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusMetric(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.90f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Slate, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (active) MintDeep else OutlineSoft),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CaptureActionButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (primary) {
        Brush.linearGradient(listOf(MintDeep, MintDeep.copy(alpha = 0.86f)))
    } else {
        Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.92f)))
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (primary) Icons.Rounded.PlayArrow else Icons.Rounded.Sensors,
                contentDescription = null,
                tint = if (primary) Color.White else MintDeep,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (primary) Color.White else Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PermissionRequiredPanel(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Glass,
        shadowElevation = 16.dp,
        modifier = modifier.padding(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = null, tint = MintDeep, modifier = Modifier.size(36.dp))
            Text("Camera permission needed", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                "Pocap needs camera frames to run MediaPipe landmarks and stream capture payloads to the server.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate,
            )
            CaptureActionButton(
                label = "Allow Camera",
                enabled = true,
                primary = true,
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatusOrb(active: Boolean, label: String) {
    val colors = if (active) listOf(MintBright, MintDeep) else listOf(RoseMist, Slate.copy(alpha = 0.42f))
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (active) Icons.Rounded.CloudDone else Icons.Rounded.Sensors,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun LandmarkConfidenceOverlay(
    x: FloatArray?,
    y: FloatArray?,
    visibility: FloatArray?,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (x == null || y == null) return@Canvas
        val count = minOf(x.size, y.size, visibility?.size ?: x.size)
        for (index in 0 until count) {
            val confidence = visibility?.getOrNull(index) ?: 1f
            if (confidence < 0.35f) continue
            val px = x[index].coerceIn(0f, 1f) * size.width
            val py = y[index].coerceIn(0f, 1f) * size.height
            drawCircle(
                color = MintBright.copy(alpha = 0.28f + confidence.coerceIn(0f, 1f) * 0.42f),
                radius = 4.dp.toPx(),
                center = Offset(px, py),
            )
        }
    }
}
