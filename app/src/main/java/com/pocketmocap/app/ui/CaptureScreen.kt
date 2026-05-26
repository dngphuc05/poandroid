package com.pocketmocap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketmocap.app.ui.theme.PocapMono
import com.pocketmocap.app.PocketMocapViewModel
import com.pocketmocap.app.PocketMocapViewModel.CalibrationStep
import com.pocketmocap.app.camera.ArCoreFrameCapture
import com.pocketmocap.app.pipeline.HybridPosePipeline

/** In-session screens. The camera surface stays mounted under all of them. */
private enum class CaptureView { CAMERA, CALIBRATION, ERROR }

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
        PermissionScreen(
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onCancel = onDisconnect,
        )
        return
    }

    // Manual navigation only ever points at the beige overlays (Calibration / Error).
    var manualView by remember { mutableStateOf<CaptureView?>(null) }
    val activeView = manualView ?: CaptureView.CAMERA

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Persistent camera surface — keeps ArCore + streaming alive across views.
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

        when (activeView) {
            CaptureView.CAMERA -> CameraReadyChrome(
                uiState = uiState,
                viewModel = viewModel,
                onOpenCalibration = { manualView = CaptureView.CALIBRATION },
                onOpenError = { manualView = CaptureView.ERROR },
                onDisconnect = onDisconnect,
            )

            CaptureView.CALIBRATION -> SyncCalibrationScreen(
                uiState = uiState,
                viewModel = viewModel,
                onBack = { manualView = null },
                onStartCalibration = onStartCalibration,
                onClearError = onClearError,
            )

            CaptureView.ERROR -> SessionErrorScreen(
                uiState = uiState,
                viewModel = viewModel,
                onRetry = { manualView = null },
                onLeave = onDisconnect,
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// CAMERA — minimal floating chrome so the live view stays unobstructed
// ════════════════════════════════════════════════════════════════════
@Composable
private fun CameraReadyChrome(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    onOpenCalibration: () -> Unit,
    onOpenError: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val visible = viewModel.visibleLandmarkCount
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // top — session identity + status chips (compact, top-left & top-right)
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
                    PocapIconButton(onClick = onOpenError, tone = PocapPaper.copy(alpha = 0.92f)) {
                        Text("...", color = PocapInk, fontWeight = FontWeight.Bold)
                    }
                    PocapIconButton(onClick = onDisconnect, tone = PocapPaper.copy(alpha = 0.92f)) {
                        Icon(Icons.Rounded.LinkOff, contentDescription = "Leave session", tint = PocapInk)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PocapChip(
                    label = captureStatusLabel(uiState, visible),
                    tone = captureStatusTone(uiState, visible),
                    dot = true,
                )
                PocapChip(
                    label = if (uiState.calibrationStep == CalibrationStep.COMPLETE) "pc can record" else "waiting for pc",
                    tone = PocapPaper.copy(alpha = 0.92f),
                )
            }
            captureWarning(uiState, visible)?.let { WarningBanner(it) }
        }

        // bottom — two slim cards only: live metrics + role/actions
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricsRowCard(uiState = uiState, viewModel = viewModel)
            CompactActionRow(
                onOpenCalibration = onOpenCalibration,
            )
        }
    }
}

@Composable
private fun CompactActionRow(
    onOpenCalibration: () -> Unit,
) {
    PocapCard(color = PocapPaper.copy(alpha = 0.96f), radius = 18.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                PocapEyebrow("device / role")
                Text(
                    text = "Phone capture node",
                    style = MaterialTheme.typography.titleSmall,
                    color = PocapInk,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "PC records session artifacts. This phone streams camera landmarks and tracking metrics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocapInk3,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PocapIconButton(onClick = onOpenCalibration, tone = PocapViolet) {
                Text("SYNC", color = PocapInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// SYNC / CALIBRATION — beige, laptop-controlled stage (off-camera)
// ════════════════════════════════════════════════════════════════════
@Composable
private fun SyncCalibrationScreen(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    onBack: () -> Unit,
    onStartCalibration: () -> Unit,
    onClearError: () -> Unit,
) {
    val step = uiState.calibrationStep
    val stages = listOf("Sync", "Calibrate", "Capture")
    val stageIdx = when (step) {
        CalibrationStep.PENDING, CalibrationStep.INTRINSIC_CALC -> 0
        CalibrationStep.EXTRINSIC_ANCHOR, CalibrationStep.BOOTSTRAP -> 1
        CalibrationStep.COMPLETE -> 2
    }
    val (title, body) = when (step) {
        CalibrationStep.PENDING -> "Waiting for\nthe operator." to
            "The laptop starts sync when ready. Keep this phone steady where it is."
        CalibrationStep.INTRINSIC_CALC -> "Syncing\nthe camera." to
            "Negotiating intrinsics and a shared clock with the laptop. Don't move the phone."
        CalibrationStep.EXTRINSIC_ANCHOR -> "Stand in\nframe." to
            "Subject faces the camera, full body visible, arms loose. The laptop anchors the scene."
        CalibrationStep.BOOTSTRAP -> "Reading\nthe room." to
            "Collecting canonical-pose frames to lock metric scale and the floor plane."
        CalibrationStep.COMPLETE -> "Looks\ngood." to
            "Floor plane and subject height are locked. Ready to capture."
    }
    val sideLabel = when (step) {
        CalibrationStep.PENDING, CalibrationStep.INTRINSIC_CALC -> "Hold position"
        CalibrationStep.EXTRINSIC_ANCHOR -> "Subject ready"
        CalibrationStep.BOOTSTRAP -> "Hold still"
        CalibrationStep.COMPLETE -> "Ready"
    }
    val showSkeleton = step == CalibrationStep.EXTRINSIC_ANCHOR ||
        step == CalibrationStep.BOOTSTRAP || step == CalibrationStep.COMPLETE
    val showFloor = step == CalibrationStep.EXTRINSIC_ANCHOR || step == CalibrationStep.BOOTSTRAP

    PocapPaperScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            // header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 18.dp),
            ) {
                PocapIconButton(onClick = onBack, tone = PocapPaperLight) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = "Back to camera", tint = PocapInk)
                }
                Column(modifier = Modifier.weight(1f)) {
                    PocapEyebrow("stage / ${stages[stageIdx]}")
                    Text(
                        text = "PC controlled",
                        style = MaterialTheme.typography.titleSmall,
                        color = PocapInk,
                        fontFamily = PocapMono,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(PocapViolet))
            }

            // stage rail
            PocapCard(color = PocapPaperLight, radius = 12.dp, shadow = false) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    PocapStageRail(stages = stages, activeIndex = stageIdx)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = PocapInk,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = PocapInk2,
                modifier = Modifier.padding(top = 12.dp, bottom = 18.dp),
            )

            // visualizer card
            PocapCard(radius = 18.dp) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.5.dp, PocapInk, RoundedCornerShape(10.dp)),
                    ) {
                        PocapMockViewfinder(
                            modifier = Modifier.fillMaxSize(),
                            dark = step == CalibrationStep.PENDING,
                        ) {
                            if (showSkeleton) PocapMockSkeleton(confidence = if (step == CalibrationStep.COMPLETE) 1f else 0.85f)
                            if (showFloor) PocapFloorMarkers(detecting = step == CalibrationStep.EXTRINSIC_ANCHOR)
                        }
                        Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                            PocapChip(label = sideLabel, tone = PocapPaper.copy(alpha = 0.94f))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PocapEyebrow(calibrationProgressLabel(uiState))
                        calibrationProgress(uiState)?.let {
                            Text(
                                text = "${(it * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = PocapInk,
                                fontFamily = PocapMono,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    PocapProgressBar(
                        progress = calibrationProgress(uiState),
                        tone = if (step == CalibrationStep.COMPLETE) PocapCyan else PocapViolet,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
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
                            ?: viewModel.latestSceneMetrics?.bodyHeightMeters ?: Float.NaN,
                    ),
                    tone = PocapCyan,
                    modifier = Modifier.weight(1f),
                )
                PocapFactBox(
                    label = "Age",
                    value = viewModel.lastPose3DAgeMs?.let { "$it ms" } ?: "--",
                    tone = PocapPink,
                    modifier = Modifier.weight(1f),
                )
            }

            uiState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(14.dp))
                PocapCard(color = Color(0xFFFFEFEF), shadow = false) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapDanger,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onClearError).padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            if (step == CalibrationStep.PENDING) {
                PocapButton(
                    label = "Mark phone ready",
                    onClick = onStartCalibration,
                    modifier = Modifier.fillMaxWidth(),
                    tone = PocapViolet,
                    contentColor = PocapInk,
                )
            } else {
                PocapButton(
                    label = "Back to camera",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    tone = if (step == CalibrationStep.COMPLETE) PocapCyan else PocapPaperLight,
                    contentColor = PocapInk,
                )
            }
            if (step != CalibrationStep.COMPLETE && step != CalibrationStep.PENDING) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(PocapViolet))
                    Text(
                        text = "Phone is in standby. The PC drives the next step.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapInk2,
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// ERROR / DISCONNECTED — beige (off-camera)
// ════════════════════════════════════════════════════════════════════
@Composable
private fun SessionErrorScreen(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    onRetry: () -> Unit,
    onLeave: () -> Unit,
) {
    val hasError = uiState.errorMessage != null
    val title = if (hasError) "Phone hit\na snag." else "Connection\ndetails."
    val body = uiState.errorMessage
        ?: "The session is live on the laptop. This phone is streaming as a capture node."

    PocapPaperScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 22.dp),
            ) {
                PocapIconButton(onClick = onRetry, tone = PocapPaperLight) {
                    Text("×", color = PocapInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                PocapEyebrow("connection")
                Spacer(modifier = Modifier.weight(1f))
                PocapChip(
                    label = if (hasError) "warning" else "online",
                    tone = if (hasError) PocapDanger else PocapCyan,
                    dot = true,
                )
            }

            // glyph block with diagonal pink stripes
            PocapCard(radius = 18.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val step = 28.dp.toPx()
                        var x = -size.height
                        while (x < size.width) {
                            drawLine(
                                color = PocapPink.copy(alpha = 0.40f),
                                start = Offset(x, size.height),
                                end = Offset(x + size.height, 0f),
                                strokeWidth = 14.dp.toPx(),
                            )
                            x += step
                        }
                    }
                    PocapCard(color = PocapPaperLight, radius = 16.dp) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = PocapInk, modifier = Modifier.size(36.dp))
                            Column {
                                PocapEyebrow("phone status")
                                Text(
                                    text = if (hasError) "ATTENTION" else "ONLINE",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = PocapInk,
                                    fontFamily = PocapMono,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = PocapInk,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = PocapInk2,
                modifier = Modifier.padding(top = 12.dp, bottom = 18.dp),
            )

            // fact rows
            PocapCard(color = PocapPaperLight, radius = 12.dp, shadow = false) {
                Column {
                    PocapPayloadRow("server", uiState.serverUrl)
                    Spacer(modifier = Modifier.height(10.dp))
                    ErrorFactRow("session", formatSessionCode(uiState.joinedLobbyCode), ok = uiState.joinedLobbyCode.isNotBlank(), first = true)
                    ErrorFactRow(
                        "last pose",
                        viewModel.lastPose3DAgeMs?.let { "$it ms ago" } ?: "waiting",
                        ok = viewModel.lastPose3DAgeMs != null,
                    )
                    ErrorFactRow(
                        "data sent",
                        if (viewModel.framesSentToServer > 0) "${viewModel.framesSentToServer} frames" else "none yet",
                        ok = viewModel.framesSentToServer > 0,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            PocapButton(
                label = "Back to camera",
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                tone = PocapPink,
                contentColor = PocapInk,
            )
            Text(
                text = "Leave session",
                style = MaterialTheme.typography.bodySmall,
                color = PocapInk3,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onLeave).padding(top = 14.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorFactRow(label: String, value: String, ok: Boolean, first: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (!first) {
            Box(modifier = Modifier.fillMaxWidth().height(1.5.dp).background(PocapInk).align(Alignment.TopCenter))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PocapEyebrow(label)
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = if (ok) PocapInk else PocapDanger,
                fontFamily = PocapMono,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// PERMISSION — Riso illustration card + facts
// ════════════════════════════════════════════════════════════════════
@Composable
private fun PermissionScreen(
    onRequestPermission: () -> Unit,
    onCancel: () -> Unit,
) {
    PocapPaperScaffold {
        Column(
            modifier = Modifier
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
                PocapEyebrow("Step 3 / camera")
            }

            PocapCard(radius = 20.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // overprint accent blocks
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(20.dp).size(60.dp).clip(RoundedCornerShape(12.dp)).background(PocapViolet.copy(alpha = 0.85f)))
                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 30.dp).width(90.dp).height(30.dp).clip(RoundedCornerShape(6.dp)).background(PocapCyan.copy(alpha = 0.85f)))
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 70.dp).size(24.dp).clip(CircleShape).background(PocapPink.copy(alpha = 0.85f)))
                    // central camera glyph card
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(PocapPink)
                            .border(2.dp, PocapInk, RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        PocapCameraGlyph(size = 64.dp, color = PocapInk)
                    }
                    Box(modifier = Modifier.align(Alignment.TopStart).padding(14.dp)) {
                        PocapChip(label = "required", tone = PocapViolet, dot = true)
                    }
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
                text = "Your phone reads 2D landmarks from the camera and streams them to the PC. PC records the final session; local diagnostics are optional.",
                style = MaterialTheme.typography.bodyMedium,
                color = PocapInk2,
                modifier = Modifier.padding(top = 12.dp, bottom = 18.dp),
            )
            PermissionFact("On-device pose / no frames leave")
            PermissionFact("Streams 33 landmarks @ 30 fps")
            PermissionFact("Stays joined while the PC holds the session")
            Spacer(modifier = Modifier.weight(1f))
            PocapButton(
                label = "Allow camera",
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                tone = PocapCyan,
                contentColor = PocapInk,
            )
            Text(
                text = "Back to session",
                style = MaterialTheme.typography.bodySmall,
                color = PocapInk3,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onCancel).padding(top = 12.dp),
                textAlign = TextAlign.Center,
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
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PocapPaperLight)
                .border(1.5.dp, PocapInk, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            PocapLogoMark(modifier = Modifier.size(15.dp))
        }
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = PocapInk2)
    }
}

// ════════════════════════════════════════════════════════════════════
// Shared chrome pieces + helpers
// ════════════════════════════════════════════════════════════════════
@Composable
private fun SessionPill(code: String, status: String) {
    PocapCard(color = PocapPaper.copy(alpha = 0.94f), radius = 999.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Box(modifier = Modifier.width(1.5.dp).height(28.dp).background(PocapInk))
            Text(
                text = status.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = PocapInk,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(PocapCyan).padding(horizontal = 10.dp, vertical = 7.dp),
                maxLines = 1,
            )
        }
    }
}

private data class CaptureWarning(val title: String, val body: String, val tone: Color)

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
            Box(modifier = Modifier.width(1.5.dp).height(42.dp).background(PocapInk4))
            PocapBigNum(
                label = "Stream",
                value = viewModel.framesSentToServer.toString(),
                modifier = Modifier.weight(1f),
                unit = "sent",
                tone = if (viewModel.framesSentToServer > 0) PocapCyan else PocapInk,
            )
            Box(modifier = Modifier.width(1.5.dp).height(42.dp).background(PocapInk4))
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

private fun captureStatusLabel(uiState: PocketMocapViewModel.UiState, visibleLandmarks: Int): String = when {
    visibleLandmarks == 0 -> "No person"
    visibleLandmarks < 18 -> "Low tracking"
    uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING -> "Ready"
    else -> "Camera active"
}

private fun captureStatusTone(uiState: PocketMocapViewModel.UiState, visibleLandmarks: Int): Color = when {
    visibleLandmarks == 0 || visibleLandmarks < 18 -> PocapWarn
    uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING -> PocapCyan
    else -> PocapPaper
}

private fun captureWarning(uiState: PocketMocapViewModel.UiState, visibleLandmarks: Int): CaptureWarning? = when {
    uiState.errorMessage != null -> CaptureWarning("Phone warning", uiState.errorMessage, PocapDanger)
    visibleLandmarks == 0 -> CaptureWarning(
        "No person visible",
        "Adjust framing so the actor is visible before the PC starts sync.",
        PocapWarn,
    )
    else -> null
}

private fun calibrationProgress(uiState: PocketMocapViewModel.UiState): Float? = when {
    uiState.calibrationStep == CalibrationStep.COMPLETE -> 1f
    uiState.bootstrapProgress > 0f -> uiState.bootstrapProgress.coerceIn(0f, 1f)
    else -> null
}

private fun calibrationProgressLabel(uiState: PocketMocapViewModel.UiState): String = when (uiState.calibrationStep) {
    CalibrationStep.PENDING -> "Idle / waiting for PC"
    CalibrationStep.INTRINSIC_CALC -> "Sync running"
    CalibrationStep.EXTRINSIC_ANCHOR -> "Waiting for subject"
    CalibrationStep.BOOTSTRAP -> "Locking metric scale"
    CalibrationStep.COMPLETE -> "Calibration / OK"
}

private fun formatMeters(value: Float): String =
    if (value.isFinite()) String.format(java.util.Locale.US, "%.2f m", value) else "waiting"

private fun formatSessionCode(code: String): String =
    code.filter(Char::isDigit).take(6).chunked(3).joinToString(" ").ifBlank { "pending" }

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
            drawCircle(color = PocapInk.copy(alpha = 0.72f), radius = 5.0.dp.toPx(), center = p)
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

private val REAL_2D_EDGES = listOf(
    11 to 12, 11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21,
    12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22,
    11 to 23, 12 to 24, 23 to 24, 23 to 25, 25 to 27, 27 to 29, 27 to 31,
    24 to 26, 26 to 28, 28 to 30, 28 to 32,
)
