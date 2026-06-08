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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay

/** In-session screens. The camera surface stays mounted under all of them. */
private enum class CaptureView { CAMERA, SETUP, RECORD, ERROR }

@Composable
fun CaptureScreen(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    onStartCalibration: () -> Unit,
    onLeaveSession: () -> Unit,
    onBackToJoin: () -> Unit,
    onBackToLink: () -> Unit,
    onClearError: () -> Unit,
    onStartScreenEvidenceRecording: () -> Unit,
    onStopScreenEvidenceRecording: () -> Unit,
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
            viewModel.bindCloudAnchorEngine(cameraCapture)
            cameraCapture.start(lifecycleOwner)
        }
        onDispose {
            Log.i("CaptureScreen", "Stopping Pocap phone capture node")
            viewModel.bindCloudAnchorEngine(null)
            cameraCapture.stop()
        }
    }

    if (!hasCameraPermission) {
        PermissionScreen(
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onCancel = onBackToLink,
        )
        return
    }

    val sessionIsSingleCamera = uiState.joinedLobbyPreset == "single_live"
    val calibrationComplete = uiState.calibrationStep == CalibrationStep.COMPLETE
    val setupFailed = uiState.calibrationStep == CalibrationStep.FAILED || (uiState.errorMessage != null && !calibrationComplete)
    val activeView = when {
        setupFailed -> CaptureView.ERROR
        calibrationComplete -> CaptureView.CAMERA
        else -> CaptureView.SETUP
    }
    val recordingSeconds = rememberRecordingSeconds(viewModel.isScreenEvidenceRecording)

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
            // Landmarks are already transformed into ARCore view-normalized coordinates.
            // Passing no image size prevents the overlay from applying a second crop/rotation.
            imageWidth = 0,
            imageHeight = 0,
        )
        PocapCornerBrackets()
        PocapCameraScrim()
        if (viewModel.isScreenEvidenceRecording) {
            ScreenEvidenceRecordingChrome(
                recordingSeconds = recordingSeconds,
                onStopRecording = onStopScreenEvidenceRecording,
            )
        }

        when (activeView) {
            CaptureView.CAMERA -> CameraReadyChrome(
                uiState = uiState,
                viewModel = viewModel,
                onBackToJoin = onBackToJoin,
                onToggleScreenRecording = {
                    if (viewModel.isScreenEvidenceRecording) {
                        onStopScreenEvidenceRecording()
                    } else {
                        onStartScreenEvidenceRecording()
                    }
                },
            )

            CaptureView.SETUP -> SetupStepScreen(
                uiState = uiState,
                viewModel = viewModel,
                isSingleCamera = sessionIsSingleCamera,
                onBackToJoin = onBackToJoin,
                onStartCalibration = onStartCalibration,
                onClearError = onClearError,
            )

            CaptureView.RECORD -> RecordStepScreen(
                uiState = uiState,
                viewModel = viewModel,
                onToggleScreenRecording = {
                    if (viewModel.isScreenEvidenceRecording) onStopScreenEvidenceRecording() else onStartScreenEvidenceRecording()
                },
            )

            CaptureView.ERROR -> SessionErrorScreen(
                uiState = uiState,
                viewModel = viewModel,
                onBackToJoin = onBackToJoin,
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
    onBackToJoin: () -> Unit,
    onToggleScreenRecording: () -> Unit,
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
                    PocapIconButton(onClick = onBackToJoin, tone = PocapPaper.copy(alpha = 0.92f)) {
                        Icon(Icons.Rounded.LinkOff, contentDescription = "Back to code", tint = PocapInk)
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
                recording = viewModel.isScreenEvidenceRecording,
                recordingStatus = viewModel.screenEvidenceRecordingStatus,
                onToggleScreenRecording = onToggleScreenRecording,
            )
        }
    }
}

@Composable
private fun CompactActionRow(
    recording: Boolean,
    recordingStatus: String,
    onToggleScreenRecording: () -> Unit,
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
                    text = if (recording) {
                        recordingStatus
                    } else {
                        "PC records mocap. REC saves this phone screen, person, and 2D landmarks as MP4 in Movies/Pocap."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PocapInk3,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PocapChip(
                label = if (recording) "recording" else "capture live",
                tone = if (recording) PocapPink else PocapCyan,
                dot = true,
            )
            PocapIconButton(
                onClick = onToggleScreenRecording,
                tone = if (recording) PocapPink else PocapPaperLight,
            ) {
                Text("REC", color = PocapInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CameraHeightControl(
    cameraHeightMeters: Float,
    onCameraHeightChange: (Float) -> Unit,
) {
    val safeHeight = cameraHeightMeters.takeIf { it.isFinite() } ?: 1.17f
    PocapCard(color = PocapPaperLight, radius = 14.dp, shadow = false) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                PocapEyebrow("metric camera height")
                Text(
                    text = "Use the phone lens height from the floor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocapInk2,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PocapIconButton(
                onClick = { onCameraHeightChange(safeHeight - 0.01f) },
                tone = PocapPaper,
            ) {
                Text("-1", color = PocapInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = formatMeters(safeHeight),
                style = MaterialTheme.typography.titleSmall,
                color = PocapInk,
                fontFamily = PocapMono,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            PocapIconButton(
                onClick = { onCameraHeightChange(safeHeight + 0.01f) },
                tone = PocapCyan,
            ) {
                Text("+1", color = PocapInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SubjectHeightControl(
    subjectHeightMeters: Float,
    onSubjectHeightChange: (Float) -> Unit,
) {
    val hasHeight = subjectHeightMeters.isFinite()
    val displayHeight = if (hasHeight) subjectHeightMeters else 1.83f
    PocapCard(color = PocapPaperLight, radius = 14.dp, shadow = false) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                PocapEyebrow("actor height")
                Text(
                    text = if (hasHeight) {
                        "Used by the server as the metric body scale."
                    } else {
                        "Set once for stable single-cam scale."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = PocapInk2,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PocapIconButton(
                onClick = { onSubjectHeightChange(displayHeight - 0.01f) },
                tone = PocapPaper,
            ) {
                Text("-1", color = PocapInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = if (hasHeight) formatMeters(displayHeight) else "set",
                style = MaterialTheme.typography.titleSmall,
                color = PocapInk,
                fontFamily = PocapMono,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            PocapIconButton(
                onClick = { onSubjectHeightChange(displayHeight + 0.01f) },
                tone = PocapCyan,
            ) {
                Text("+1", color = PocapInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ScreenEvidenceRecordingChrome(
    recordingSeconds: Int,
    onStopRecording: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(18.dp))
                .border(3.dp, PocapPink, RoundedCornerShape(18.dp)),
        )
        PocapChip(
            label = "rec",
            tone = PocapPink,
            dot = true,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 2.dp, top = 4.dp),
        )
        PocapCard(
            color = PocapPaper.copy(alpha = 0.94f),
            radius = 14.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 2.dp, top = 38.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = formatRecordClock(recordingSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    color = PocapInk,
                    fontFamily = PocapMono,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = "SCREEN MP4",
                    style = MaterialTheme.typography.labelSmall,
                    color = PocapInk3,
                    fontFamily = PocapMono,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                    maxLines = 1,
                )
            }
        }
        PocapIconButton(
            onClick = onStopRecording,
            tone = PocapPink,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(PocapInk),
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// SYNC / CALIBRATION — beige, laptop-controlled stage (off-camera)
// ════════════════════════════════════════════════════════════════════

@Composable
private fun SetupStepScreen(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    isSingleCamera: Boolean,
    onBackToJoin: () -> Unit,
    onStartCalibration: () -> Unit,
    onClearError: () -> Unit,
) {
    val step = uiState.calibrationStep
    SessionStepScaffold(
        stepTitle = if (isSingleCamera) "single-cam setup" else "multi-cam setup",
        headline = if (isSingleCamera) "Single-cam\ncalibration." else "Multi-cam\ncalibration.",
        description = if (isSingleCamera)
            "Set camera height, send calibration, then capture starts."
        else
            "Sync phones, calibrate this camera, then capture starts.",
        steps = if (isSingleCamera) listOf("JOIN", "CALIB", "CAPT", "RECO") else listOf("JOIN", "SYNC", "CALIB", "CAPT", "RECO"),
        activeIndex = when {
            isSingleCamera && step == CalibrationStep.PENDING -> 1
            isSingleCamera && step == CalibrationStep.COMPLETE -> 2
            isSingleCamera -> 1
            !isSingleCamera && step == CalibrationStep.PENDING -> 1
            !isSingleCamera && step == CalibrationStep.INTRINSIC_CALC -> 1
            !isSingleCamera && step == CalibrationStep.SYNC_WAIT -> 1
            !isSingleCamera && step == CalibrationStep.COMPLETE -> 3
            else -> 2
        },
        progress = calibrationProgress(uiState),
        progressLabel = calibrationProgressLabel(uiState),
        onBack = onBackToJoin,
        body = {
            CalibrationPayloadRow("session mode", if (isSingleCamera) "single camera live" else "multi camera live")
            CalibrationPayloadRow("phones allowed", if (isSingleCamera) "1" else "multiple")
            if (!isSingleCamera) {
                CalibrationPayloadRow("server receives", "intrinsics, sync, extrinsics, landmarks")
                CalibrationPayloadRow("sync usage", "done during setup; later only if phones drift during live capture")
            }
            CameraHeightControl(
                cameraHeightMeters = uiState.manualCameraHeightMeters,
                onCameraHeightChange = viewModel::setManualCameraHeightMeters,
            )
        },
        footer = {
            uiState.errorMessage?.let { error ->
                PocapCard(color = Color(0xFFFFEFEF), shadow = false) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapDanger,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onClearError).padding(12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
            if (step == CalibrationStep.PENDING) {
                PocapButton(
                    label = when {
                        isSingleCamera -> "Send camera calibration"
                        else -> "Send sync calibration"
                    },
                    onClick = onStartCalibration,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                    tone = PocapCyan,
                    contentColor = PocapInk,
                )
            } else {
                PocapButton(
                    label = when (step) {
                        CalibrationStep.COMPLETE -> "Opening capture..."
                        CalibrationStep.INTRINSIC_CALC -> if (isSingleCamera) "Camera calibration running" else "Sync running"
                        CalibrationStep.EXTRINSIC_ANCHOR -> "Solving camera extrinsics"
                        CalibrationStep.SYNC_WAIT -> "Syncing multi-phone timing"
                        CalibrationStep.BOOTSTRAP -> "Locking metric scale"
                        CalibrationStep.FAILED -> "Calibration failed"
                        CalibrationStep.PENDING -> "Waiting"
                    },
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    tone = PocapPaperLight,
                    contentColor = PocapInk,
                )
            }
        },
    )
}

@Composable
private fun RecordStepScreen(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    onToggleScreenRecording: () -> Unit,
) {
    SessionStepScaffold(
        stepTitle = "capture / record",
        headline = "Capture and\nrecord.",
        description = "The session is calibrated. PC can run reconstruction and save session metrics. REC on phone saves the phone screen with person + 2D landmarks MP4.",
        steps = if (uiState.joinedLobbyPreset == "single_live") listOf("JOIN", "CALIB", "CAPT", "RECO") else listOf("JOIN", "SYNC", "CALIB", "CAPT", "RECO"),
        activeIndex = if (uiState.joinedLobbyPreset == "single_live") 3 else 4,
        onBack = {},
        body = {
            CalibrationPayloadRow("session", formatSessionCode(uiState.joinedLobbyCode))
            CalibrationPayloadRow("mode", if (uiState.joinedLobbyPreset == "single_live") "single camera" else "multi camera")
            CalibrationPayloadRow("stream", "${viewModel.framesSentToServer} frames sent")
            CalibrationPayloadRow("phone REC", if (viewModel.isScreenEvidenceRecording) "recording overlay MP4" else "ready")
        },
        footer = {
            PocapButton(
                label = if (viewModel.isScreenEvidenceRecording) "Stop REC" else "Start REC",
                onClick = onToggleScreenRecording,
                modifier = Modifier.fillMaxWidth(),
                tone = if (viewModel.isScreenEvidenceRecording) PocapPink else PocapCyan,
                contentColor = PocapInk,
            )
        },
    )
}

@Composable
private fun SessionStepScaffold(
    stepTitle: String,
    headline: String,
    description: String,
    steps: List<String>,
    activeIndex: Int,
    progress: Float? = null,
    progressLabel: String? = null,
    onBack: () -> Unit,
    body: @Composable () -> Unit,
    footer: @Composable () -> Unit,
) {
    PocapPaperScaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 18.dp),
            ) {
                PocapIconButton(onClick = onBack, tone = PocapPaperLight) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = "Back", tint = PocapInk)
                }
                Column(modifier = Modifier.weight(1f)) {
                    PocapEyebrow(stepTitle)
                    Text(
                        text = if (steps.size == 4) "Single-camera guided flow" else "Multi-camera guided flow",
                        style = MaterialTheme.typography.titleSmall,
                        color = PocapInk,
                        fontFamily = PocapMono,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(PocapViolet))
            }

            PocapCard(color = PocapPaperLight, radius = 12.dp, shadow = false) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    PocapStageRail(stages = steps, activeIndex = activeIndex.coerceIn(0, steps.lastIndex))
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineLarge,
                color = PocapInk,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = PocapInk2,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )

            PocapCard(color = PocapPaper, radius = 18.dp) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    body()
                }
            }

            progress?.let {
                Spacer(modifier = Modifier.height(16.dp))
                PocapProgressBar(progress = it)
                progressLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = PocapInk2,
                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            footer()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ERROR / DISCONNECTED — beige (off-camera)
// ════════════════════════════════════════════════════════════════════
@Composable
private fun SessionErrorScreen(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    onBackToJoin: () -> Unit,
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
                PocapIconButton(onClick = onBackToJoin, tone = PocapPaperLight) {
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
                label = "Back to code",
                onClick = onBackToJoin,
                modifier = Modifier.fillMaxWidth(),
                tone = PocapPink,
                contentColor = PocapInk,
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

@Composable
private fun CalibrationPayloadRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PocapEyebrow(label)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = PocapInk2,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
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

private fun calibrationProgress(uiState: PocketMocapViewModel.UiState): Float? = when (uiState.calibrationStep) {
    CalibrationStep.PENDING -> null
    CalibrationStep.INTRINSIC_CALC -> 0.20f
    CalibrationStep.EXTRINSIC_ANCHOR -> 0.40f
    CalibrationStep.SYNC_WAIT -> 0.55f
    CalibrationStep.BOOTSTRAP -> maxOf(0.65f, uiState.bootstrapProgress.coerceIn(0f, 1f))
    CalibrationStep.COMPLETE -> 1f
    CalibrationStep.FAILED -> null
}

private fun calibrationProgressLabel(uiState: PocketMocapViewModel.UiState): String = when (uiState.calibrationStep) {
    CalibrationStep.PENDING -> if (uiState.joinedLobbyPreset == "single_live") "Ready to send camera calibration" else "Ready to send sync calibration"
    CalibrationStep.INTRINSIC_CALC -> if (uiState.joinedLobbyPreset == "single_live") "Reading camera intrinsics" else "Reading camera intrinsics for multi-cam"
    CalibrationStep.EXTRINSIC_ANCHOR -> if (uiState.joinedLobbyPreset == "single_live") "Solving camera extrinsics" else "Solving camera extrinsics"
    CalibrationStep.SYNC_WAIT -> "Syncing multi-phone timing"
    CalibrationStep.BOOTSTRAP -> "Locking metric scale"
    CalibrationStep.COMPLETE -> "Capture live"
    CalibrationStep.FAILED -> "Calibration failed"
}

private fun formatMeters(value: Float): String =
    if (value.isFinite()) String.format(java.util.Locale.US, "%.2f m", value) else "waiting"

@Composable
private fun rememberRecordingSeconds(recording: Boolean): Int {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(recording) {
        seconds = 0
        while (recording) {
            delay(1000)
            seconds += 1
        }
    }
    return seconds
}

private fun formatRecordClock(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%02d:%02d", safe / 60, safe % 60)
}

private fun formatSessionCode(code: String): String =
    code.filter(Char::isDigit).take(6).chunked(3).joinToString(" ").ifBlank { "pending" }

@Composable
private fun RealLandmarkOverlay(
    x: FloatArray?,
    y: FloatArray?,
    visibility: FloatArray?,
    imageWidth: Int,
    imageHeight: Int,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (x == null || y == null) return@Canvas
        val count = minOf(x.size, y.size, visibility?.size ?: x.size)
        fun point(index: Int): Offset? {
            if (index !in 0 until count) return null
            val confidence = visibility?.getOrNull(index) ?: 1f
            if (confidence < 0.28f) return null
            val lx = x[index].coerceIn(0f, 1f)
            val ly = y[index].coerceIn(0f, 1f)
            if (imageWidth > 0 && imageHeight > 0) {
                val scale = maxOf(size.width / imageWidth.toFloat(), size.height / imageHeight.toFloat())
                val displayedWidth = imageWidth * scale
                val displayedHeight = imageHeight * scale
                val offsetX = (displayedWidth - size.width) * 0.5f
                val offsetY = (displayedHeight - size.height) * 0.5f
                return Offset(lx * displayedWidth - offsetX, ly * displayedHeight - offsetY)
            }
            return Offset(lx * size.width, ly * size.height)
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
