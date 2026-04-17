package com.pocketmocap.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pocketmocap.app.PocketMocapViewModel
import com.pocketmocap.app.camera.ArCoreFrameCapture
import com.pocketmocap.app.pipeline.HybridPosePipeline
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.ServerPoseDebugSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import com.pocketmocap.app.tracking.authoritativeDistanceMetersOrNull
import com.pocketmocap.app.tracking.authoritativeHeightMetersOrNull
import com.pocketmocap.app.tracking.hasCanonicalMetricPose
import com.pocketmocap.app.ui.components.CaptureView
import com.pocketmocap.app.ui.components.ViewToggle
import com.pocketmocap.app.ui.theme.CloudWarm
import com.pocketmocap.app.ui.theme.Glass
import com.pocketmocap.app.ui.theme.Ink
import com.pocketmocap.app.ui.theme.Mint
import com.pocketmocap.app.ui.theme.MintBright
import com.pocketmocap.app.ui.theme.MintDeep
import kotlin.math.sqrt
import com.pocketmocap.app.ui.theme.Slate
import com.pocketmocap.app.ui.theme.SkeletonBlue
import com.pocketmocap.app.ui.theme.SkeletonMint
import com.pocketmocap.app.ui.theme.SkeletonPink
import com.pocketmocap.app.ui.theme.TechnicalGrid
import kotlin.math.roundToInt

// ─── MediaPipe 33 Joint Names (COCO+ format) ────────────────────────────────
// 0:nose 1:left_eye_inner 2:left_eye 3:left_eye_outer
// 4:right_eye_inner 5:right_eye 6:right_eye_outer 7:left_ear 8:right_ear
// 9:mouth_left 10:mouth_right
// 11:left_shoulder 12:right_shoulder 13:left_elbow 14:right_elbow
// 15:left_wrist 16:right_wrist 17:left_pinky 18:right_pinky
// 19:left_index 20:right_index 21:left_thumb 22:right_thumb
// 23:left_hip 24:right_hip 25:left_knee 26:right_knee
// 27:left_ankle 28:right_ankle 29:left_heel 30:right_heel
// 31:left_foot_index 32:right_foot_index

/** 14 bone connections matching the legacy android-studio NativePosePipeline. */
private val BoneConnections = listOf(
    11 to 12, // shoulders
    11 to 23, // L shoulder -> L hip
    12 to 24, // R shoulder -> R hip
    23 to 24, // hips
    11 to 13, 13 to 15, // L arm
    12 to 14, 14 to 16, // R arm
    23 to 25, 25 to 27, // L leg
    24 to 26, 26 to 28, // R leg
    0 to 11,  0 to 12,  // head -> shoulders
)

/** Extra face connections for detail rendering. */
private val FaceConnections = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 7,  // left face
    0 to 4, 4 to 5, 5 to 6, 6 to 8,  // right face
    9 to 10,                           // mouth
)

/** Hand connections. */
private val HandConnections = listOf(
    15 to 17, 15 to 19, 15 to 21, // left hand
    16 to 18, 16 to 20, 16 to 22, // right hand
)

/** Foot connections. */
private val FootConnections = listOf(
    27 to 29, 27 to 31, 29 to 31, // left foot
    28 to 30, 28 to 32, 30 to 32, // right foot
)

@Composable
fun CaptureScreen(
    uiState: PocketMocapViewModel.UiState,
    viewModel: PocketMocapViewModel,
    activeView: CaptureView,
    onViewSelected: (CaptureView) -> Unit,
    onCameraHeightChanged: (Float) -> Unit,
    onSubjectHeightChanged: (Float) -> Unit,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
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
            Log.i("CaptureScreen", "Starting ARCore capture")
            cameraCapture.start(lifecycleOwner)
        }
        onDispose {
            Log.i("CaptureScreen", "Stopping ARCore capture")
            cameraCapture.stop()
        }
    }

    var cameraHeightMeters by remember { mutableFloatStateOf(uiState.manualCameraHeightMeters) }
    var subjectHeightMeters by remember {
        mutableFloatStateOf(uiState.manualSubjectHeightMeters.takeIf { it.isFinite() } ?: 1.70f)
    }
    var subjectHeightEnabled by remember { mutableStateOf(uiState.manualSubjectHeightMeters.isFinite()) }
    var cameraHeightExpanded by remember { mutableStateOf(false) }
    var facingFront by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.manualCameraHeightMeters) {
        cameraHeightMeters = uiState.manualCameraHeightMeters
    }
    LaunchedEffect(uiState.manualSubjectHeightMeters) {
        subjectHeightEnabled = uiState.manualSubjectHeightMeters.isFinite()
        if (uiState.manualSubjectHeightMeters.isFinite()) {
            subjectHeightMeters = uiState.manualSubjectHeightMeters
        }
    }

    // Figma viewport background: #f5f4eb + rgba(0,0,0,0.1) overlay
    val viewportBg = Color(0xFFF5F4EB)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(viewportBg),
    ) {
        // Slight darkening overlay (Figma: rgba(0,0,0,0.1))
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))

        // ── Camera frame container (rounded-24, frosted glass, taller) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 68.dp, bottom = 100.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.8f)),
        ) {
            if (!hasCameraPermission) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text("Camera permission required", color = Slate) }
            } else {
                // Camera preview — hidden in Technical mode (still running for MediaPipe)
                AndroidView(
                    factory = { cameraCapture.previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .alpha(if (activeView == CaptureView.TECHNICAL) 0f else 1f),
                )
                // Technical mode: always show true 3D scene with DLT-triangulated server pose
                if (activeView == CaptureView.TECHNICAL) {
                    val serverMetricPoseReady = viewModel.serverMetricPoseDisplayReady
                    val allowClientTechnicalFallback = !viewModel.serverMetricPoseExpected
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .clip(RoundedCornerShape(18.dp))
                    ) {
                        Technical3DSceneView(
                            serverPoseX = viewModel.serverPoseX.takeIf { serverMetricPoseReady },
                            serverPoseY = viewModel.serverPoseY.takeIf { serverMetricPoseReady },
                            serverPoseZ = viewModel.serverPoseZ.takeIf { serverMetricPoseReady },
                            serverGroundY = 0f,
                            serverPoseVisibility = viewModel.serverPoseConf.takeIf { serverMetricPoseReady },
                            clientMetricPoseX = viewModel.clientTechnicalPoseX.takeIf { allowClientTechnicalFallback },
                            clientMetricPoseY = viewModel.clientTechnicalPoseY.takeIf { allowClientTechnicalFallback },
                            clientMetricPoseZ = viewModel.clientTechnicalPoseZ.takeIf { allowClientTechnicalFallback },
                            clientMetricPoseVisibility = viewModel.clientTechnicalPoseConf.takeIf { allowClientTechnicalFallback },
                            subjectDistanceMeters = viewModel.technicalDistanceMeters,
                            subjectHeightMeters = viewModel.technicalHeightMeters,
                            worldTracking = viewModel.latestWorldTracking,
                            sceneMetrics = viewModel.latestTechnicalSceneMetrics,
                            screenX     = viewModel.poseLandmarksX,
                            screenY     = viewModel.poseLandmarksY,
                            screenZ     = viewModel.poseLandmarksZ,
                            poseVisibility       = viewModel.poseVisibility,
                            visibleLandmarkCount = viewModel.visibleLandmarkCount,
                            uiState              = uiState,
                        )
                    }
                }
                // Camera flip button is now in the outer control row (see below)
            }

            // ── Animated overlays (inside camera frame) ──
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .clip(RoundedCornerShape(18.dp)),
                ) {
                    when (activeView) {
                        CaptureView.SKELETON -> {
                            // SurfaceView renders on the analysis thread directly —
                            // bypasses Compose recomposition + Vsync coalescing (~8ms saved)
                            val currentActiveView by rememberUpdatedState(activeView)
                            DisposableEffect(Unit) {
                                onDispose { viewModel.directLandmarkCallback = null }
                            }
                            AndroidView(
                                factory = { ctx ->
                                    SkeletonSurfaceView(ctx).also { sv ->
                                        viewModel.directLandmarkCallback = { x, y, vis, iw, ih ->
                                            when (currentActiveView) {
                                                CaptureView.SKELETON -> sv.renderSkeleton(x, y, vis, iw, ih)
                                                else -> {}
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            // Compose composable for demo animation when no live data
                            if (viewModel.poseLandmarksX == null) {
                                FullSkeleton33Overlay(
                                    accented = false,
                                    liveLandmarksX = null,
                                    liveLandmarksY = null,
                                )
                            }
                        }
                        CaptureView.AVATAR -> {
                            val serverMetricPoseReady = viewModel.serverMetricPoseDisplayReady
                            val allowClientTechnicalFallback = !viewModel.serverMetricPoseExpected
                            val metricServerPoseX = viewModel.serverPoseX.takeIf { serverMetricPoseReady }
                            val metricServerPoseY = viewModel.serverPoseY.takeIf { serverMetricPoseReady }
                            val metricServerPoseZ = viewModel.serverPoseZ.takeIf { serverMetricPoseReady }
                            val metricServerPoseConf = viewModel.serverPoseConf.takeIf { serverMetricPoseReady }
                            val fallbackTechnicalPoseX = viewModel.technicalPoseX ?: viewModel.clientTechnicalPoseX.takeIf { allowClientTechnicalFallback }
                            val fallbackTechnicalPoseY = viewModel.technicalPoseY ?: viewModel.clientTechnicalPoseY.takeIf { allowClientTechnicalFallback }
                            val fallbackTechnicalPoseZ = viewModel.technicalPoseZ ?: viewModel.clientTechnicalPoseZ.takeIf { allowClientTechnicalFallback }
                            val hasServerAvatarPose =
                                (fallbackTechnicalPoseX != null || metricServerPoseX != null) &&
                                (fallbackTechnicalPoseY != null || metricServerPoseY != null) &&
                                (fallbackTechnicalPoseZ != null || metricServerPoseZ != null) &&
                                ((fallbackTechnicalPoseX ?: metricServerPoseX)?.size == 33) &&
                                ((fallbackTechnicalPoseY ?: metricServerPoseY)?.size == 33) &&
                                ((fallbackTechnicalPoseZ ?: metricServerPoseZ)?.size == 33)

                            if (hasServerAvatarPose) {
                                val avatarPoseX = metricServerPoseX ?: fallbackTechnicalPoseX
                                val avatarPoseY = metricServerPoseY ?: fallbackTechnicalPoseY
                                val avatarPoseZ = metricServerPoseZ ?: fallbackTechnicalPoseZ
                                val useMetricServerAvatarPose =
                                    metricServerPoseX != null &&
                                        metricServerPoseY != null &&
                                        metricServerPoseZ != null
                                val avatarGroundY =
                                    if (
                                        metricServerPoseX != null &&
                                        metricServerPoseY != null &&
                                        metricServerPoseZ != null
                                    ) {
                                        0f
                                    } else {
                                        viewModel.technicalGroundY
                                    }
                                AvatarVrmOverlay(
                                    poseX = avatarPoseX,
                                    poseY = avatarPoseY,
                                    poseZ = avatarPoseZ,
                                    groundY = avatarGroundY,
                                    subjectHeightMeters = viewModel.technicalHeightMeters,
                                    worldTracking = viewModel.latestWorldTracking,
                                    screenX = viewModel.poseLandmarksX,
                                    screenY = viewModel.poseLandmarksY,
                                    poseVisibility = metricServerPoseConf
                                        ?: viewModel.clientTechnicalPoseConf.takeIf { allowClientTechnicalFallback }
                                        ?: viewModel.poseVisibility,
                                    imageWidth = viewModel.cameraImageWidth,
                                    imageHeight = viewModel.cameraImageHeight,
                                    preferMetricPose = useMetricServerAvatarPose,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (viewModel.poseLandmarksX != null && viewModel.poseLandmarksY != null) {
                                AvatarBodyOverlay(
                                    liveLandmarksX = viewModel.poseLandmarksX,
                                    liveLandmarksY = viewModel.poseLandmarksY,
                                    poseVisibility = viewModel.poseVisibility,
                                    imageWidth = viewModel.cameraImageWidth,
                                    imageHeight = viewModel.cameraImageHeight,
                                )
                            } else {
                                AvatarGhostOverlay()
                            }
                        }
                        CaptureView.TECHNICAL -> { /* rendered in Technical3DSceneView above */ }
                    }
                }

                // Low bone count warning banner — pushed below the control row
                if (viewModel.visibleLandmarkCount in 1..14) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 58.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color(0xFFFFD066).copy(alpha = 0.92f))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "Position clearly \u2014 ${viewModel.visibleLandmarkCount}/33 joints visible",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF3D2A00),
                        )
                    }
                }
                if (viewModel.visibleLandmarkCount == 0 &&
                    uiState.pipelineState == HybridPosePipeline.PipelineState.CAPTURING) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 58.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color(0xFFFF6B6B).copy(alpha = 0.92f))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "No person detected",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }

                // ── Camera Height overlay — collapsible, inside camera frame ──
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    if (cameraHeightExpanded) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFD9DDE0).copy(alpha = 0.92f),
                            shadowElevation = 8.dp,
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Camera Height",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Ink,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${"%.2f".format(cameraHeightMeters)}m",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(Ink.copy(alpha = 0.14f))
                                                .clickable { cameraHeightExpanded = false },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("×", style = MaterialTheme.typography.labelLarge, color = Ink)
                                        }
                                    }
                                }
                                Slider(
                                    value = cameraHeightMeters,
                                    onValueChange = { cameraHeightMeters = it },
                                    valueRange = 0.20f..2.50f,
                                    onValueChangeFinished = { onCameraHeightChanged(cameraHeightMeters) },
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = Mint,
                                        inactiveTrackColor = Color(0xFFE5E9EB),
                                    ),
                                )
                                HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Subject Height",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Ink,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (subjectHeightEnabled) "${"%.2f".format(subjectHeightMeters)}m" else "Off",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Switch(
                                            checked = subjectHeightEnabled,
                                            onCheckedChange = { enabled ->
                                                subjectHeightEnabled = enabled
                                                onSubjectHeightChanged(if (enabled) subjectHeightMeters else Float.NaN)
                                            },
                                        )
                                    }
                                }
                                Slider(
                                    value = subjectHeightMeters,
                                    enabled = subjectHeightEnabled,
                                    onValueChange = { subjectHeightMeters = it },
                                    valueRange = 1.05f..2.35f,
                                    onValueChangeFinished = {
                                        if (subjectHeightEnabled) onSubjectHeightChanged(subjectHeightMeters)
                                    },
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White,
                                        activeTrackColor = SkeletonBlue,
                                        inactiveTrackColor = Color(0xFFE5E9EB),
                                    ),
                                )
                                if (!subjectHeightEnabled) {
                                    Text(
                                        "Metric body height calibration is off",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFF4D4D).copy(alpha = 0.82f),
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(Color(0xFFD9DDE0).copy(alpha = 0.85f))
                                .clickable { cameraHeightExpanded = true }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (subjectHeightEnabled) {
                                        "Camera: ${"%.2f".format(cameraHeightMeters)}m  Subject: ${"%.2f".format(subjectHeightMeters)}m"
                                    } else {
                                        "Camera: ${"%.2f".format(cameraHeightMeters)}m  Subject: OFF"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Ink,
                                )
                                Text("▲", style = MaterialTheme.typography.labelSmall, color = Ink.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }

        // ── Top controls: keep the mode toggle truly centered, independent of camera actions ──
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 72.dp),
        ) {
            ViewToggle(
                activeView = activeView,
                onViewSelected = onViewSelected,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (viewModel.isCaptureRecording) {
                            Color(0xFFFF4D4D).copy(alpha = 0.92f)
                        } else {
                            Color.White.copy(alpha = 0.90f)
                        }
                    )
                    .clickable { viewModel.toggleCaptureRecording() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (viewModel.isCaptureRecording) Color.White else Color(0xFFFF4D4D)),
                    )
                    Text(
                        if (viewModel.isCaptureRecording) "STOP" else "REC",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (viewModel.isCaptureRecording) Color.White else Ink,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (activeView != CaptureView.TECHNICAL) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable {
                            facingFront = !facingFront
                            cameraCapture.switchCamera(facingFront)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FlipCameraAndroid,
                        contentDescription = "Flip camera",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // ── Pipeline status badge ──
        if (uiState.pipelineState != HybridPosePipeline.PipelineState.CAPTURING) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 128.dp)
                    .background(Color.Black.copy(alpha = 0.48f), RoundedCornerShape(9999.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = when (uiState.pipelineState) {
                        HybridPosePipeline.PipelineState.IDLE -> "Initializing..."
                        HybridPosePipeline.PipelineState.CONNECTING -> "Connecting..."
                        HybridPosePipeline.PipelineState.CALIBRATING -> "Calibrating..."
                        HybridPosePipeline.PipelineState.BOOTSTRAPPING ->
                            "Bootstrap ${(uiState.bootstrapProgress * 100).toInt()}%"
                        HybridPosePipeline.PipelineState.CAPTURING -> ""
                        HybridPosePipeline.PipelineState.ERROR -> "Error"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }

        // (Camera Height is now inside the camera frame as a collapsible overlay)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Full 33-joint skeleton overlay (MediaPipe COCO+ format)
// Ported from legacy android-studio/app NativePosePipeline + PreviewScenography
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FullSkeleton33Overlay(
    accented: Boolean,
    liveLandmarksX: FloatArray? = null,
    liveLandmarksY: FloatArray? = null,
    poseVisibility: FloatArray? = null,
    imageWidth: Int = 0,
    imageHeight: Int = 0,
) {
    // Always animate — used as a demo when no live data
    val transition = rememberInfiniteTransition(label = "skeleton33")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeletonPhase",
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (liveLandmarksX != null) 0.dp else 32.dp,
                     vertical = if (liveLandmarksX != null) 0.dp else 80.dp),
    ) {
        val sway = kotlin.math.sin(phase * Math.PI * 2.0).toFloat()
        val lift = kotlin.math.cos(phase * Math.PI * 2.0).toFloat() * 0.008f
        val breathe = kotlin.math.sin(phase * Math.PI * 4.0).toFloat() * 0.003f

        // Use live landmark data when available, fall back to demo animation
        val j: Array<Offset> = if (liveLandmarksX != null && liveLandmarksY != null &&
            liveLandmarksX.size == 33 && liveLandmarksY.size == 33) {
            Array(33) { i -> Offset(liveLandmarksX[i], liveLandmarksY[i]) }
        } else {
            // ── Demo 33 joints in normalized [0..1] coordinates ──
            arrayOf(
            /* 0  nose            */ Offset(0.500f, 0.120f + lift),
            /* 1  left_eye_inner  */ Offset(0.478f, 0.105f + lift),
            /* 2  left_eye        */ Offset(0.470f, 0.103f + lift),
            /* 3  left_eye_outer  */ Offset(0.462f, 0.105f + lift),
            /* 4  right_eye_inner */ Offset(0.522f, 0.105f + lift),
            /* 5  right_eye       */ Offset(0.530f, 0.103f + lift),
            /* 6  right_eye_outer */ Offset(0.538f, 0.105f + lift),
            /* 7  left_ear        */ Offset(0.445f, 0.115f + lift),
            /* 8  right_ear       */ Offset(0.555f, 0.115f + lift),
            /* 9  mouth_left      */ Offset(0.485f, 0.138f + lift),
            /* 10 mouth_right     */ Offset(0.515f, 0.138f + lift),
            /* 11 left_shoulder   */ Offset(0.380f - sway * 0.012f, 0.225f + breathe),
            /* 12 right_shoulder  */ Offset(0.620f + sway * 0.012f, 0.225f + breathe),
            /* 13 left_elbow      */ Offset(0.320f - sway * 0.025f, 0.360f),
            /* 14 right_elbow     */ Offset(0.680f + sway * 0.025f, 0.360f),
            /* 15 left_wrist      */ Offset(0.340f - sway * 0.035f, 0.480f),
            /* 16 right_wrist     */ Offset(0.660f + sway * 0.035f, 0.480f),
            /* 17 left_pinky      */ Offset(0.330f - sway * 0.038f, 0.505f),
            /* 18 right_pinky     */ Offset(0.670f + sway * 0.038f, 0.505f),
            /* 19 left_index      */ Offset(0.345f - sway * 0.038f, 0.500f),
            /* 20 right_index     */ Offset(0.655f + sway * 0.038f, 0.500f),
            /* 21 left_thumb      */ Offset(0.358f - sway * 0.032f, 0.492f),
            /* 22 right_thumb     */ Offset(0.642f + sway * 0.032f, 0.492f),
            /* 23 left_hip        */ Offset(0.430f - sway * 0.005f, 0.520f),
            /* 24 right_hip       */ Offset(0.570f + sway * 0.005f, 0.520f),
            /* 25 left_knee       */ Offset(0.420f - sway * 0.008f, 0.700f),
            /* 26 right_knee      */ Offset(0.580f + sway * 0.008f, 0.700f),
            /* 27 left_ankle      */ Offset(0.415f + sway * 0.010f, 0.870f),
            /* 28 right_ankle     */ Offset(0.585f - sway * 0.010f, 0.870f),
            /* 29 left_heel       */ Offset(0.405f + sway * 0.010f, 0.890f),
            /* 30 right_heel      */ Offset(0.595f - sway * 0.010f, 0.890f),
            /* 31 left_foot_index */ Offset(0.425f + sway * 0.012f, 0.900f),
            /* 32 right_foot_idx  */ Offset(0.575f - sway * 0.012f, 0.900f),
        ) // end demo fallback
        } // end live/demo branch

        fun px(idx: Int): Offset {
            val lx = j[idx].x
            val ly = j[idx].y
            return if (imageWidth > 0 && imageHeight > 0) {
                // Compensate for FILL_CENTER scaling: the PreviewView crops the image to fill
                val scale = kotlin.math.max(size.width / imageWidth, size.height / imageHeight)
                val dispW = imageWidth * scale
                val dispH = imageHeight * scale
                val offX = (dispW - size.width) / 2f
                val offY = (dispH - size.height) / 2f
                Offset(lx * dispW - offX, ly * dispH - offY)
            } else {
                Offset(lx * size.width, ly * size.height)
            }
        }

        // ── Draw major bone connections (14 core bones) ──
        val coreBrush = Brush.linearGradient(
            if (accented) listOf(SkeletonPink, SkeletonBlue, SkeletonMint)
            else listOf(SkeletonMint, SkeletonBlue)
        )
        BoneConnections.forEach { (a, b) ->
            drawLine(brush = coreBrush, start = px(a), end = px(b), strokeWidth = 6f, cap = StrokeCap.Round)
        }

        // ── Draw face connections (thin, subtle) ──
        val faceBrush = Brush.linearGradient(
            if (accented) listOf(SkeletonPink.copy(alpha = 0.5f), SkeletonBlue.copy(alpha = 0.5f))
            else listOf(SkeletonMint.copy(alpha = 0.4f), SkeletonBlue.copy(alpha = 0.4f))
        )
        FaceConnections.forEach { (a, b) ->
            drawLine(brush = faceBrush, start = px(a), end = px(b), strokeWidth = 2f, cap = StrokeCap.Round)
        }

        // ── Draw hand connections ──
        HandConnections.forEach { (a, b) ->
            drawLine(brush = faceBrush, start = px(a), end = px(b), strokeWidth = 2.5f, cap = StrokeCap.Round)
        }

        // ── Draw foot connections ──
        FootConnections.forEach { (a, b) ->
            drawLine(brush = faceBrush, start = px(a), end = px(b), strokeWidth = 2.5f, cap = StrokeCap.Round)
        }

        // ── Draw all 33 joint dots ──
        //  Major joints (indices 0, 11-16, 23-28): big dots
        //  Minor joints (face/hand/foot details): small dots
        val majorJoints = setOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        for (i in 0 until 33) {
            val center = px(i)
            if (i in majorJoints) {
                // Glow ring
                drawCircle(
                    color = Mint.copy(alpha = 0.45f),
                    radius = 16f,
                    center = center,
                    style = Stroke(width = 3f),
                )
                // Core dot
                drawCircle(
                    color = Color.White.copy(alpha = 0.92f),
                    radius = 8f,
                    center = center,
                )
            } else {
                // Small dot for minor landmarks
                drawCircle(
                    color = Color.White.copy(alpha = 0.70f),
                    radius = 4f,
                    center = center,
                )
            }
        }

        // ── ROI bounding box (dashed rect around ALL detected landmarks) ──
        if (liveLandmarksX != null && liveLandmarksX.size == 33) {
            val pad = 28f
            // Use FILL_CENTER-compensated positions to compute ROI bounds
            var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
            for (i in 0 until 33) {
                val pos = px(i)
                if (pos.x < minX) minX = pos.x
                if (pos.x > maxX) maxX = pos.x
                if (pos.y < minY) minY = pos.y
                if (pos.y > maxY) maxY = pos.y
            }
            val rcLeft  = (minX - pad).coerceAtLeast(0f)
            val rcTop   = (minY - pad).coerceAtLeast(0f)
            val rcRight = (maxX + pad).coerceAtMost(size.width)
            val rcBot   = (maxY + pad).coerceAtMost(size.height)
            if (rcRight > rcLeft && rcBot > rcTop) {
                drawRoundRect(
                    color = SkeletonMint.copy(alpha = 0.75f),
                    topLeft = Offset(rcLeft, rcTop),
                    size = Size(rcRight - rcLeft, rcBot - rcTop),
                    cornerRadius = CornerRadius(18f, 18f),
                    style = Stroke(
                        width = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f)),
                    ),
                )
            }
        }
    }
}

// ─── Avatar Body Overlay — silhouette fill driven by live landmarks ─────────
// Draws a stylised avatar body: head, torso, limb capsules, plus the full
// 33-joint accented skeleton on top.  Uses the same FILL_CENTER landmark
// compensation as FullSkeleton33Overlay.

@Composable
private fun AvatarBodyOverlay(
    liveLandmarksX: FloatArray? = null,
    liveLandmarksY: FloatArray? = null,
    poseVisibility: FloatArray? = null,
    imageWidth: Int = 0,
    imageHeight: Int = 0,
    modelName: String? = null,
) {
    // Avatar has the same animated demo skeleton as FullSkeleton33Overlay
    val transition = rememberInfiniteTransition(label = "avatar33")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "avatarPhase",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Skeleton + body silhouette canvas ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sway  = kotlin.math.sin(phase * Math.PI * 2.0).toFloat()
            val lift  = kotlin.math.cos(phase * Math.PI * 2.0).toFloat() * 0.008f
            val breathe = kotlin.math.sin(phase * Math.PI * 4.0).toFloat() * 0.003f

            val j: Array<Offset> = if (liveLandmarksX != null && liveLandmarksY != null &&
                liveLandmarksX.size == 33 && liveLandmarksY.size == 33) {
                Array(33) { i -> Offset(liveLandmarksX[i], liveLandmarksY[i]) }
            } else {
                arrayOf(
                    Offset(0.500f, 0.120f + lift), Offset(0.478f, 0.105f + lift),
                    Offset(0.470f, 0.103f + lift), Offset(0.462f, 0.105f + lift),
                    Offset(0.522f, 0.105f + lift), Offset(0.530f, 0.103f + lift),
                    Offset(0.538f, 0.105f + lift), Offset(0.445f, 0.115f + lift),
                    Offset(0.555f, 0.115f + lift), Offset(0.485f, 0.138f + lift),
                    Offset(0.515f, 0.138f + lift),
                    Offset(0.380f - sway * 0.012f, 0.225f + breathe),
                    Offset(0.620f + sway * 0.012f, 0.225f + breathe),
                    Offset(0.320f - sway * 0.025f, 0.360f),
                    Offset(0.680f + sway * 0.025f, 0.360f),
                    Offset(0.340f - sway * 0.035f, 0.480f),
                    Offset(0.660f + sway * 0.035f, 0.480f),
                    Offset(0.330f - sway * 0.038f, 0.505f), Offset(0.670f + sway * 0.038f, 0.505f),
                    Offset(0.345f - sway * 0.038f, 0.500f), Offset(0.655f + sway * 0.038f, 0.500f),
                    Offset(0.358f - sway * 0.032f, 0.492f), Offset(0.642f + sway * 0.032f, 0.492f),
                    Offset(0.430f - sway * 0.005f, 0.520f), Offset(0.570f + sway * 0.005f, 0.520f),
                    Offset(0.420f - sway * 0.008f, 0.700f), Offset(0.580f + sway * 0.008f, 0.700f),
                    Offset(0.415f + sway * 0.010f, 0.870f), Offset(0.585f - sway * 0.010f, 0.870f),
                    Offset(0.405f + sway * 0.010f, 0.890f), Offset(0.595f - sway * 0.010f, 0.890f),
                    Offset(0.425f + sway * 0.012f, 0.900f), Offset(0.575f - sway * 0.012f, 0.900f),
                )
            }

            fun px(idx: Int): Offset {
                val lx = j[idx].x; val ly = j[idx].y
                return if (imageWidth > 0 && imageHeight > 0) {
                    val scale = kotlin.math.max(size.width / imageWidth, size.height / imageHeight) * 0.86f
                    val dispW = imageWidth * scale; val dispH = imageHeight * scale
                    val offX = (dispW - size.width) / 2f
                    val offY = (dispH - size.height) / 2f - size.height * 0.03f
                    Offset(lx * dispW - offX, ly * dispH - offY)
                } else Offset(lx * size.width, ly * size.height)
            }

            // ── Puppet character colors ──
            val avOutfit  = Color(0xFF3C37AF)   // deep blue trousers/jacket
            val avShirt   = Color(0xFF5A46C3)   // slightly lighter shirt
            val avSkin    = Color(0xFFD2B48C)   // warm skin
            val avHair    = Color(0xFF231C12)   // near-black hair
            val avShoe    = Color(0xFF1A1A1A)   // black shoes
            val avOutline = Color(0xFF0C0A08)   // near-black outline
            val avEye     = Color(0xFF0C0A08)
            val ow = 3f                         // outline stroke width

            fun limbStroke(a: Int, b: Int, col: Color, ratioW: Float, lo: Float = 10f, hi: Float = 44f) {
                val va = poseVisibility?.get(a) ?: 0.8f
                val vb = poseVisibility?.get(b) ?: 0.8f
                if (va > 0.2f && vb > 0.2f) {
                    val pa = px(a); val pb = px(b)
                    val len = kotlin.math.sqrt(
                        ((pb.x - pa.x).let { it * it } + (pb.y - pa.y).let { it * it }).toDouble()
                    ).toFloat()
                    val thick = (len * ratioW).coerceIn(lo, hi)
                    drawLine(col, pa, pb, strokeWidth = thick, cap = StrokeCap.Round)
                    drawLine(avOutline, pa, pb, strokeWidth = thick + ow * 2, cap = StrokeCap.Round,
                        blendMode = BlendMode.Darken)
                }
            }

            // ── 1. Lower legs (drawn first) ──
            limbStroke(25, 27, avOutfit, 0.20f, 8f, 30f)
            limbStroke(26, 28, avOutfit, 0.20f, 8f, 30f)

            // ── 2. Upper legs ──
            limbStroke(23, 25, avOutfit, 0.26f, 12f, 40f)
            limbStroke(24, 26, avOutfit, 0.26f, 12f, 40f)

            // ── 3. Torso ──
            run {
                val ls = px(11); val rs = px(12); val lh = px(23); val rh = px(24)
                val pad = (rs.x - ls.x) * 0.06f
                val torsoPath = Path().apply {
                    moveTo(ls.x - pad, ls.y); lineTo(rs.x + pad, rs.y)
                    lineTo(rh.x + pad, rh.y); lineTo(lh.x - pad, lh.y); close()
                }
                drawPath(torsoPath, color = avShirt)
                drawPath(torsoPath, color = avOutline, style = Stroke(ow))
            }

            // ── 4. Upper arms ──
            limbStroke(11, 13, avShirt, 0.22f, 8f, 34f)
            limbStroke(12, 14, avShirt, 0.22f, 8f, 34f)

            // ── 5. Forearms ──
            limbStroke(13, 15, avSkin, 0.18f, 7f, 26f)
            limbStroke(14, 16, avSkin, 0.18f, 7f, 26f)

            // ── 6. Hands ──
            for (wi in listOf(15, 16)) {
                val wv = poseVisibility?.get(wi) ?: 0.8f
                if (wv > 0.2f) {
                    val wp = px(wi)
                    drawCircle(avSkin, size.width / 100f, wp)
                    drawCircle(avOutline, size.width / 100f, wp, style = Stroke(ow))
                }
            }

            // ── 7. Shoes ──
            for (ai in listOf(27, 28)) {
                val av = poseVisibility?.get(ai) ?: 0.8f
                if (av > 0.2f) {
                    val ap = px(ai)
                    val sw = size.width / 55f; val sh = size.width / 80f
                    drawOval(avShoe,
                        topLeft = Offset(ap.x - sw + sw * 0.3f, ap.y - sh + sh),
                        size = Size(sw * 2, sh * 2))
                    drawOval(avOutline, style = Stroke(ow),
                        topLeft = Offset(ap.x - sw + sw * 0.3f, ap.y - sh + sh),
                        size = Size(sw * 2, sh * 2))
                }
            }

            // ── 8. Neck ──
            run {
                val neckCx = (px(11).x + px(12).x) / 2f
                val neckCy = (px(11).y + px(12).y) / 2f
                val noseY  = px(0).y
                val nw = size.width / 70f
                drawRect(avSkin,
                    topLeft = Offset(neckCx - nw, noseY),
                    size = Size(nw * 2, neckCy - noseY))
            }

            // ── 9. Head: hair cap + face circle + eyes + mouth ──
            run {
                val nose = px(0); val lEar = px(7); val rEar = px(8)
                val earD = kotlin.math.sqrt(
                    ((rEar.x - lEar.x).let { it * it } + (rEar.y - lEar.y).let { it * it }).toDouble()
                ).toFloat()
                val headR = (earD * 0.60f).coerceIn(18f, 80f)
                val headCx = px(0).x.toFloat()
                val headCy = px(0).y - headR * 0.18f

                // Hair cap (semi-oval behind head)
                val hairPath = Path().apply {
                    addOval(androidx.compose.ui.geometry.Rect(
                        left  = headCx - headR - 3f,
                        top   = headCy - headR * 1.05f,
                        right = headCx + headR + 3f,
                        bottom = headCy + headR * 0.1f
                    ))
                }
                drawPath(hairPath, avHair)

                // Skin face
                drawCircle(avSkin, headR, Offset(headCx, headCy))
                drawCircle(avOutline, headR, Offset(headCx, headCy), style = Stroke(ow))

                // Eyes
                val eyeY  = headCy - headR * 0.08f
                val eyeOff = headR * 0.34f
                val eyeRx  = (headR * 0.14f).coerceAtLeast(3f)
                val eyeRy  = (headR * 0.18f).coerceAtLeast(4f)
                for (ex in listOf(headCx - eyeOff, headCx + eyeOff)) {
                    drawOval(Color.White,
                        topLeft = Offset(ex - eyeRx, eyeY - eyeRy),
                        size = Size(eyeRx * 2, eyeRy * 2))
                    drawOval(avOutline, style = Stroke(ow - 1f),
                        topLeft = Offset(ex - eyeRx, eyeY - eyeRy),
                        size = Size(eyeRx * 2, eyeRy * 2))
                    // Pupil
                    drawCircle(avEye, (eyeRx / 2f).coerceAtLeast(2f),
                        Offset(ex, eyeY + eyeRy / 5f))
                }

                // Mouth arc
                val mouthY = headCy + headR * 0.38f
                val mw = headR * 0.28f; val mh = headR * 0.12f
                val mouthPath = Path().apply {
                    moveTo(headCx - mw, mouthY)
                    cubicTo(headCx - mw, mouthY + mh,
                            headCx + mw, mouthY + mh,
                            headCx + mw, mouthY)
                }
                drawPath(mouthPath, avOutline, style = Stroke((ow - 1f).coerceAtLeast(1f)))
            }
        }

        // Model name badge (bottom-left)
        if (modelName != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(0.55f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                androidx.compose.material3.Text(
                    text = "Avatar \u00b7 $modelName",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = MintBright,
                )
            }
        }
    }
}

// ─── Avatar Ghost Overlay (Polished — pulsing radial gradient + glass info) ──

@Composable
private fun AvatarGhostOverlay() {
    val transition = rememberInfiniteTransition(label = "avatar")
    val pulse by transition.animateFloat(
        initialValue = 0.88f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Pulsing radial gradient circle (background glow)
        Canvas(modifier = Modifier.size(280.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Mint.copy(alpha = 0.16f), Color.Transparent)
                ),
                radius = size.minDimension * 0.5f * pulse,
            )
            drawCircle(
                color = MintBright.copy(alpha = 0.3f),
                radius = size.minDimension * 0.38f * pulse,
                style = Stroke(width = size.minDimension * 0.03f),
            )
        }

        // Central icon circle
        Box(
            modifier = Modifier
                .size(164.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MintDeep.copy(alpha = 0.52f),
                            Mint.copy(alpha = 0.68f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(54.dp),
            )
        }

        // Glass info card at bottom
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 40.dp, vertical = 200.dp),
            shape = RoundedCornerShape(24.dp),
            color = Glass,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Avatar View",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Text(
                    text = "Active model \u00b7 VIPE Hero",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate,
                )
            }
        }
    }
}

// ─── Technical Grid Overlay ──────────────────────────────────────────────────

@Composable
private fun TechnicalGridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cols = 10
        val rows = 16
        val xStep = size.width / cols
        val yStep = size.height / rows
        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))

        for (i in 0..cols) {
            val x = i * xStep
            drawLine(TechnicalGrid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f, pathEffect = dash)
        }
        for (j in 0..rows) {
            val y = j * yStep
            drawLine(TechnicalGrid, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, pathEffect = dash)
        }
    }
}

@Composable
private fun AvatarVrmOverlay(
    poseX: FloatArray?,
    poseY: FloatArray?,
    poseZ: FloatArray?,
    groundY: Float,
    subjectHeightMeters: Float,
    worldTracking: WorldTrackingSnapshot?,
    screenX: FloatArray?,
    screenY: FloatArray?,
    poseVisibility: FloatArray?,
    imageWidth: Int,
    imageHeight: Int,
    preferMetricPose: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val roi = remember(screenX, screenY, poseVisibility) {
            computePoseRoi(screenX, screenY, poseVisibility)
        }
        val roiRect = remember(roi, imageWidth, imageHeight, viewportWidthPx, viewportHeightPx) {
            computePoseOverlayRectPx(
                roi = roi,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                paddingPx = 22f,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (roiRect != null) {
                val widthDp = with(density) { roiRect.width.toDp() }
                val heightDp = with(density) { roiRect.height.toDp() }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(roiRect.left.roundToInt(), roiRect.top.roundToInt()) }
                        .size(widthDp, heightDp)
                        .clip(RoundedCornerShape(18.dp)),
                ) {
                    AvatarOcclusionMask(
                        screenX = screenX,
                        screenY = screenY,
                        poseVisibility = poseVisibility,
                        roi = roi,
                        modifier = Modifier.fillMaxSize(),
                    )
                    VrmSceneView(
                        poseX = poseX,
                        poseY = poseY,
                        poseZ = poseZ,
                        groundY = groundY,
                        subjectHeightMeters = subjectHeightMeters,
                        worldTracking = worldTracking,
                        screenX = screenX,
                        screenY = screenY,
                        visibility = poseVisibility,
                        useLateralOffset = false,
                        preferMetricPose = preferMetricPose,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = SkeletonMint.copy(alpha = 0.78f),
                        topLeft = Offset(roiRect.left, roiRect.top),
                        size = Size(roiRect.width, roiRect.height),
                        cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                        style = Stroke(
                            width = 2.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f)),
                        ),
                    )
                }
            } else {
                VrmSceneView(
                    poseX = poseX,
                    poseY = poseY,
                    poseZ = poseZ,
                    groundY = groundY,
                    subjectHeightMeters = subjectHeightMeters,
                    worldTracking = worldTracking,
                    screenX = screenX,
                    screenY = screenY,
                    visibility = poseVisibility,
                    useLateralOffset = false,
                    preferMetricPose = preferMetricPose,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun AvatarOcclusionMask(
    screenX: FloatArray?,
    screenY: FloatArray?,
    poseVisibility: FloatArray?,
    roi: PoseRoi?,
    modifier: Modifier = Modifier,
) {
    if (screenX == null || screenY == null || screenX.size < 33 || screenY.size < 33 || roi == null) {
        return
    }

    Canvas(modifier = modifier) {
        val roiWidth = roi.width.coerceAtLeast(0.05f)
        val roiHeight = roi.height.coerceAtLeast(0.05f)
        val matteSoft = Color(0xFF20404E).copy(alpha = 0.22f)
        val matteFill = Color(0xFF081218).copy(alpha = 0.86f)

        fun lp(idx: Int): Offset {
            val nx = ((screenX[idx] - roi.minX) / roiWidth).coerceIn(0f, 1f)
            val ny = ((screenY[idx] - roi.minY) / roiHeight).coerceIn(0f, 1f)
            return Offset(nx * size.width, ny * size.height)
        }

        fun limb(a: Int, b: Int, widthFactor: Float, minPx: Float, maxPx: Float) {
            val va = poseVisibility?.getOrNull(a) ?: 0.8f
            val vb = poseVisibility?.getOrNull(b) ?: 0.8f
            if (va <= 0.2f || vb <= 0.2f) return
            val pa = lp(a)
            val pb = lp(b)
            val length = kotlin.math.sqrt(
                (pb.x - pa.x) * (pb.x - pa.x) +
                    (pb.y - pa.y) * (pb.y - pa.y)
            )
            val width = (length * widthFactor).coerceIn(minPx, maxPx)
            drawLine(
                color = matteSoft,
                start = pa,
                end = pb,
                strokeWidth = width * 1.45f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = matteFill,
                start = pa,
                end = pb,
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }

        drawRoundRect(
            color = Color(0x11081118),
            cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
        )

        limb(23, 25, 0.24f, 10f, 28f)
        limb(25, 27, 0.18f, 8f, 22f)
        limb(24, 26, 0.24f, 10f, 28f)
        limb(26, 28, 0.18f, 8f, 22f)
        limb(11, 13, 0.20f, 8f, 22f)
        limb(13, 15, 0.15f, 7f, 18f)
        limb(12, 14, 0.20f, 8f, 22f)
        limb(14, 16, 0.15f, 7f, 18f)

        val shoulderLeft = lp(11)
        val shoulderRight = lp(12)
        val hipLeft = lp(23)
        val hipRight = lp(24)
        val torsoPad = (shoulderRight.x - shoulderLeft.x).coerceAtLeast(size.width * 0.04f) * 0.10f
        val minCanvasDim = minOf(size.width, size.height)
        val torsoPath = Path().apply {
            moveTo(shoulderLeft.x - torsoPad, shoulderLeft.y)
            lineTo(shoulderRight.x + torsoPad, shoulderRight.y)
            lineTo(hipRight.x + torsoPad, hipRight.y)
            lineTo(hipLeft.x - torsoPad, hipLeft.y)
            close()
        }
        drawPath(torsoPath, color = matteSoft)
        drawPath(torsoPath, color = matteFill)

        val leftEar = lp(7)
        val rightEar = lp(8)
        val nose = lp(0)
        val earSpan = kotlin.math.sqrt(
            (rightEar.x - leftEar.x) * (rightEar.x - leftEar.x) +
                (rightEar.y - leftEar.y) * (rightEar.y - leftEar.y)
        ).coerceAtLeast(minCanvasDim * 0.10f)
        val headRadius = (earSpan * 0.58f).coerceIn(16f, minCanvasDim * 0.18f)
        val headCenter = Offset(nose.x, nose.y - headRadius * 0.18f)
        drawCircle(color = matteSoft, radius = headRadius * 1.08f, center = headCenter)
        drawCircle(color = matteFill, radius = headRadius, center = headCenter)
    }
}

// ─── Technical 3D Scene View (full mocap-studio perspective) ─────────────────

@Composable
private fun Technical3DSceneView(
    // Priority 1: DLT-triangulated server 3D pose in the display-upright client frame.
    serverPoseX: FloatArray?,
    serverPoseY: FloatArray?,
    serverPoseZ: FloatArray?,
    serverGroundY: Float,
    serverPoseVisibility: FloatArray?,
    clientMetricPoseX: FloatArray?,
    clientMetricPoseY: FloatArray?,
    clientMetricPoseZ: FloatArray?,
    clientMetricPoseVisibility: FloatArray?,
    subjectDistanceMeters: Float,
    subjectHeightMeters: Float,
    worldTracking: WorldTrackingSnapshot?,
    sceneMetrics: SceneMetricSnapshot?,
    // Raw screen landmarks are intentionally not used for physical Technical view.
    screenX: FloatArray?,
    screenY: FloatArray?,
    screenZ: FloatArray?,
    poseVisibility: FloatArray?,
    visibleLandmarkCount: Int,
    uiState: PocketMocapViewModel.UiState,
) {
    val jointX = remember { FloatArray(33) }
    val jointY = remember { FloatArray(33) }
    val jointZ = remember { FloatArray(33) }
    val projPx = remember { FloatArray(33) }
    val projPy = remember { FloatArray(33) }
    var orbitYawRadians by remember { mutableFloatStateOf(0f) }
    var orbitPitchOffsetRadians by remember { mutableFloatStateOf(0f) }
    var orbitZoomFactor by remember { mutableFloatStateOf(1f) }
    var stableDistanceMeters by remember { mutableFloatStateOf(2.4f) }
    var stableBodyHeightMeters by remember { mutableFloatStateOf(1.68f) }
    var stableCameraHeightMeters by remember { mutableFloatStateOf(1.35f) }
    var stableFloorPitchDegrees by remember { mutableFloatStateOf(12f) }
    var stableLateralOffsetMeters by remember { mutableFloatStateOf(0f) }
    var pendingCameraMoveFrames by remember { mutableIntStateOf(0) }
    var hasCommittedAnchor by remember { mutableStateOf(false) }
    var metricsExpanded by remember { mutableStateOf(false) }

    val hasCurrentArFloor =
        worldTracking?.hasGroundPlane == true || worldTracking?.source == "arcore_floor"
    val arSceneMetrics = sceneMetrics?.takeIf {
        it.source == "arcore_floor" &&
            it.distanceMeters.isFinite()
    }
    val hasArFloorScene = arSceneMetrics != null
    val hasServerTechnicalPose =
        serverPoseX != null &&
            serverPoseY != null &&
            serverPoseZ != null &&
            serverPoseX.size == 33 &&
            serverPoseY.size == 33 &&
            serverPoseZ.size == 33
    val serverHealth = evaluateServerPoseHealth(
        poseX = serverPoseX,
        poseY = serverPoseY,
        poseZ = serverPoseZ,
        poseVisibility = serverPoseVisibility,
    )
    val serverDebug = remember(uiState.latestPose3D, arSceneMetrics) {
        ServerPoseDebugSnapshot.fromJson(
            json = uiState.latestPose3D?.optJSONObject("server_debug"),
            fallbackScene = arSceneMetrics,
            mlEvidenceJson = uiState.latestPose3D?.optJSONObject("ml_evidence"),
        )
    }
    val hasCanonicalMetricPose = serverDebug?.hasCanonicalMetricPose() == true
    val canonicalStatus = serverDebug?.metricPoseStatus.orEmpty()
    val canonicalJointsReady = serverDebug?.let {
        it.poseJointsFrame == "display_floor_metric_v1" &&
            it.poseJointsNormalized.isFinite() &&
            kotlin.math.abs(it.poseJointsNormalized - 1f) <= 1e-3f
    } == true
    val canonicalAllowsServerPose =
        hasCanonicalMetricPose &&
            canonicalStatus in setOf("ok", "hold_previous") &&
            canonicalJointsReady
    val hasUsableServerTechnicalPose = hasServerTechnicalPose && serverHealth.usable && canonicalAllowsServerPose
    val serverRenderVisibility = if (hasUsableServerTechnicalPose) {
        technicalServerRenderVisibility(
            poseX = serverPoseX,
            poseY = serverPoseY,
            poseZ = serverPoseZ,
            poseVisibility = serverPoseVisibility,
        )
    } else {
        null
    }
    val hasClientTechnicalPose =
        screenX != null &&
            screenY != null &&
            screenX.size == 33 &&
            screenY.size == 33
    val hasClientMetricTechnicalPose =
        clientMetricPoseX != null &&
            clientMetricPoseY != null &&
            clientMetricPoseZ != null &&
            clientMetricPoseX.size == 33 &&
            clientMetricPoseY.size == 33 &&
            clientMetricPoseZ.size == 33

    val serverAuthoritativeDistance = serverDebug?.authoritativeDistanceMetersOrNull(
        arSceneMetrics?.correctedDistanceMeters ?: Float.NaN,
    )
    val subjectDistanceForView = serverAuthoritativeDistance
        ?: arSceneMetrics?.correctedDistanceMeters?.takeIf { it.isFinite() }
        ?: arSceneMetrics?.distanceMeters?.takeIf { it.isFinite() }
        ?: subjectDistanceMeters.takeIf { it.isFinite() }
    val serverAuthoritativeHeight = serverDebug?.authoritativeHeightMetersOrNull(
        arSceneMetrics?.correctedHeightMeters ?: Float.NaN,
    )
    val subjectHeightForView = serverAuthoritativeHeight
        ?: arSceneMetrics?.correctedHeightMeters?.takeIf { it.isFinite() }
        ?: arSceneMetrics?.bodyHeightMeters?.takeIf { it.isFinite() }
        ?: subjectHeightMeters.takeIf { it.isFinite() }

    LaunchedEffect(arSceneMetrics, subjectDistanceForView, subjectHeightForView, hasCanonicalMetricPose) {
        val nextDistance = subjectDistanceForView ?: return@LaunchedEffect
        val nextHeight = subjectHeightForView ?: return@LaunchedEffect
        val nextCameraHeight = arSceneMetrics?.cameraHeightMeters?.takeIf { it.isFinite() } ?: stableCameraHeightMeters
        val nextFloorPitch = arSceneMetrics?.floorPitchDegrees?.takeIf { it.isFinite() } ?: stableFloorPitchDegrees
        val nextLateralOffset = arSceneMetrics?.lateralOffsetMeters?.takeIf { it.isFinite() } ?: stableLateralOffsetMeters
        if (!hasCommittedAnchor) {
            stableDistanceMeters = nextDistance
            stableBodyHeightMeters = nextHeight
            stableCameraHeightMeters = nextCameraHeight
            stableFloorPitchDegrees = nextFloorPitch
            stableLateralOffsetMeters = nextLateralOffset
            hasCommittedAnchor = true
            pendingCameraMoveFrames = 0
            return@LaunchedEffect
        }

        if (hasCanonicalMetricPose) {
            stableDistanceMeters = stableDistanceMeters * 0.82f + nextDistance * 0.18f
            stableBodyHeightMeters = stableBodyHeightMeters * 0.90f + nextHeight * 0.10f
            stableCameraHeightMeters = stableCameraHeightMeters * 0.86f + nextCameraHeight * 0.14f
            stableFloorPitchDegrees = stableFloorPitchDegrees * 0.88f + nextFloorPitch * 0.12f
            stableLateralOffsetMeters = stableLateralOffsetMeters * 0.82f + nextLateralOffset * 0.18f
            pendingCameraMoveFrames = 0
            return@LaunchedEffect
        }

        val distanceDelta = kotlin.math.abs(nextDistance - stableDistanceMeters)
        val heightDelta = kotlin.math.abs(nextHeight - stableBodyHeightMeters)
        val cameraHeightDelta = kotlin.math.abs(nextCameraHeight - stableCameraHeightMeters)
        val pitchDelta = kotlin.math.abs(nextFloorPitch - stableFloorPitchDegrees)
        val lateralDelta = kotlin.math.abs(nextLateralOffset - stableLateralOffsetMeters)
        val meaningfulChange =
            distanceDelta >= 0.18f ||
                heightDelta >= 0.08f ||
                cameraHeightDelta >= 0.08f ||
                pitchDelta >= 1.5f ||
                lateralDelta >= 0.08f

        pendingCameraMoveFrames = if (meaningfulChange) pendingCameraMoveFrames + 1 else 0
        if (pendingCameraMoveFrames < 3) {
            return@LaunchedEffect
        }

        stableDistanceMeters = stableDistanceMeters * 0.72f + nextDistance * 0.28f
        stableBodyHeightMeters = stableBodyHeightMeters * 0.88f + nextHeight * 0.12f
        stableCameraHeightMeters = stableCameraHeightMeters * 0.72f + nextCameraHeight * 0.28f
        stableFloorPitchDegrees = stableFloorPitchDegrees * 0.72f + nextFloorPitch * 0.28f
        stableLateralOffsetMeters = stableLateralOffsetMeters * 0.68f + nextLateralOffset * 0.32f
        pendingCameraMoveFrames = 0
    }

    val displayDistanceMeters = subjectDistanceForView ?: Float.NaN
    val displayBodyHeightMeters = subjectHeightForView ?: Float.NaN
    val displayCameraHeightMeters = arSceneMetrics?.cameraHeightMeters?.takeIf { it.isFinite() } ?: Float.NaN
    val displayFloorPitchDegrees = arSceneMetrics?.floorPitchDegrees?.takeIf { it.isFinite() } ?: Float.NaN
    val displayLateralOffsetMeters = arSceneMetrics?.lateralOffsetMeters?.takeIf { it.isFinite() } ?: Float.NaN
    val bodyScaleMetrics = if (hasUsableServerTechnicalPose) {
        computeTechnicalBodyScaleMetrics(
            poseX = serverPoseX,
            poseY = serverPoseY,
            poseZ = serverPoseZ,
            groundY = serverGroundY,
            poseVisibility = serverRenderVisibility ?: serverPoseVisibility,
            targetHeightMeters = displayBodyHeightMeters,
        )
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    orbitYawRadians = (orbitYawRadians - pan.x * 0.0065f).coerceIn(-3.14159f, 3.14159f)
                    orbitPitchOffsetRadians = (orbitPitchOffsetRadians + pan.y * 0.0050f).coerceIn(-0.95f, 0.95f)
                    orbitZoomFactor = (orbitZoomFactor / zoom).coerceIn(0.60f, 1.80f)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val W = size.width
            val H = size.height
            val cx = W * 0.5f
            val cy = H * 0.5f

            // Keep the Technical view in phone/world coordinates.  The old
            // camera target followed the subject distance, which made distance
            // changes look like zoom/framing changes instead of the body moving
            // away from the phone origin.
            val cameraTargetX = 0f
            val cameraTargetY = maxOf(
                stableCameraHeightMeters.coerceIn(0.75f, 1.90f),
                (stableBodyHeightMeters * 0.50f).coerceIn(0.70f, 1.20f),
            )
            val hasInspectablePose =
                hasUsableServerTechnicalPose ||
                    hasClientMetricTechnicalPose ||
                    (hasArFloorScene && hasClientTechnicalPose)
            val cameraTargetZ = if (hasInspectablePose) {
                (-stableDistanceMeters * 0.42f).coerceIn(-5.0f, -0.65f)
            } else {
                0f
            }

            val effectiveOrbitDistance = (7.6f * orbitZoomFactor).coerceIn(4.2f, 14f)
            val yawR = orbitYawRadians
            val pitchR = (
                Math.toRadians(24.0).toFloat() + orbitPitchOffsetRadians
            ).coerceIn(-1.18f, 1.18f)
            val cameraEyeX = cameraTargetX + effectiveOrbitDistance * kotlin.math.sin(yawR) * kotlin.math.cos(pitchR)
            val cameraEyeY = cameraTargetY + effectiveOrbitDistance * kotlin.math.sin(pitchR)
            val cameraEyeZ = cameraTargetZ + effectiveOrbitDistance * kotlin.math.cos(yawR) * kotlin.math.cos(pitchR)

            fun normalize3(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
                val length = kotlin.math.sqrt(x * x + y * y + z * z)
                return if (length > 1e-4f) {
                    Triple(x / length, y / length, z / length)
                } else {
                    Triple(0f, 0f, 0f)
                }
            }

            fun cross3(
                ax: Float, ay: Float, az: Float,
                bx: Float, by: Float, bz: Float,
            ): Triple<Float, Float, Float> = Triple(
                ay * bz - az * by,
                az * bx - ax * bz,
                ax * by - ay * bx,
            )

            fun dot3(
                ax: Float, ay: Float, az: Float,
                bx: Float, by: Float, bz: Float,
            ): Float = ax * bx + ay * by + az * bz

            val (forwardX, forwardY, forwardZ) = normalize3(
                cameraTargetX - cameraEyeX,
                cameraTargetY - cameraEyeY,
                cameraTargetZ - cameraEyeZ,
            )
            val (rightRawX, rightRawY, rightRawZ) = cross3(forwardX, forwardY, forwardZ, 0f, 1f, 0f)
            val (rightX, rightY, rightZ) = normalize3(rightRawX, rightRawY, rightRawZ)
            val (upRawX, upRawY, upRawZ) = cross3(rightX, rightY, rightZ, forwardX, forwardY, forwardZ)
            val (upX, upY, upZ) = normalize3(upRawX, upRawY, upRawZ)
            val focalPx = (H * 0.5f) / kotlin.math.tan(Math.toRadians(34.0)).toFloat()

            val hasLandmarks: Boolean
            when {
                hasUsableServerTechnicalPose -> {
                    val sx = serverPoseX ?: return@Canvas
                    val sy = serverPoseY ?: return@Canvas
                    val sz = serverPoseZ ?: return@Canvas
                    for (i in 0 until 33) {
                        jointX[i] = sx[i]
                        jointY[i] = sy[i]
                        jointZ[i] = sz[i]
                    }
                    hasLandmarks = true
                }
                hasClientMetricTechnicalPose -> {
                    val sx = clientMetricPoseX ?: return@Canvas
                    val sy = clientMetricPoseY ?: return@Canvas
                    val sz = clientMetricPoseZ ?: return@Canvas
                    val drawVisibility = clientMetricPoseVisibility ?: poseVisibility
                    var floorY = technicalSkeletonFloorY(sy, drawVisibility, 0f)
                    if (!floorY.isFinite()) {
                        floorY = 0f
                    }
                    var rawRootX = 0f
                    var rawRootY = 0f
                    var rawRootZ = 0f
                    var rootCount = 0
                    for (idx in intArrayOf(23, 24)) {
                        val visible = drawVisibility?.getOrNull(idx) ?: 1f
                        if (visible <= 0.2f) continue
                        rawRootX += sx[idx]
                        rawRootY += sy[idx]
                        rawRootZ += sz[idx]
                        rootCount += 1
                    }
                    if (rootCount > 0) {
                        rawRootX /= rootCount
                        rawRootY /= rootCount
                        rawRootZ /= rootCount
                    }
                    val bodyTopY = technicalSkeletonTopY(sy, drawVisibility)
                    val rawHeight = when {
                        bodyTopY.isFinite() -> (bodyTopY - floorY).coerceAtLeast(0.60f)
                        else -> stableBodyHeightMeters
                    }
                    val scale = (stableBodyHeightMeters / rawHeight.coerceAtLeast(0.60f)).coerceIn(0.88f, 1.12f)
                    val anchorX = stableLateralOffsetMeters
                    val anchorZ = -stableDistanceMeters
                    for (i in 0 until 33) {
                        jointX[i] = anchorX + (sx[i] - rawRootX) * scale
                        jointY[i] = (sy[i] - floorY) * scale
                        jointZ[i] = anchorZ + (sz[i] - rawRootZ) * scale
                    }
                    hasLandmarks = true
                }
                !hasUsableServerTechnicalPose && hasArFloorScene && hasClientTechnicalPose -> {
                    val px = screenX ?: return@Canvas
                    val py = screenY ?: return@Canvas
                    val bodyRange = technicalScreenBodyVerticalRange(py, poseVisibility) ?: return@Canvas
                    val bodyHeightNorm = (bodyRange.second - bodyRange.first).coerceAtLeast(0.25f)
                    val metersPerNorm = (stableBodyHeightMeters / bodyHeightNorm).coerceIn(1.2f, 5.8f)
                    val hipCenterX = ((px[23] + px[24]) * 0.5f)
                    val floorNormY = bodyRange.second
                    for (i in 0 until 33) {
                        jointX[i] = stableLateralOffsetMeters + (px[i] - hipCenterX) * metersPerNorm
                        jointY[i] = (floorNormY - py[i]) * metersPerNorm
                        jointZ[i] = -stableDistanceMeters + (screenZ?.getOrNull(i) ?: 0f) * metersPerNorm * 0.35f
                    }
                    hasLandmarks = true
                }
                else -> {
                    hasLandmarks = false
                }
            }
            val selectedVisibility = if (hasUsableServerTechnicalPose) {
                serverRenderVisibility ?: serverPoseVisibility
            } else if (hasClientMetricTechnicalPose) {
                clientMetricPoseVisibility ?: poseVisibility
            } else {
                poseVisibility
            }

            fun project(xi: Float, yi: Float, zi: Float): Offset {
                val viewX = xi - cameraEyeX
                val viewY = yi - cameraEyeY
                val viewZ = zi - cameraEyeZ
                val camX = dot3(viewX, viewY, viewZ, rightX, rightY, rightZ)
                val camY = dot3(viewX, viewY, viewZ, upX, upY, upZ)
                val camZ = maxOf(0.08f, dot3(viewX, viewY, viewZ, forwardX, forwardY, forwardZ))
                val scale = focalPx / camZ
                return Offset(cx + camX * scale, cy - camY * scale)
            }

            if (hasLandmarks) {
                for (i in 0 until 33) {
                    val p = project(jointX[i], jointY[i], jointZ[i])
                    projPx[i] = p.x; projPy[i] = p.y
                }
            }

            val floorY3 = 0f
            val gridExtent = maxOf(10.0f, (stableDistanceMeters + 2.5f).coerceAtMost(16f))
            val gridStep = 0.5f
            for (i in -20..20) {
                val t = i * gridStep
                val isMajor = i % 2 == 0
                val lineColor = if (isMajor) {
                    Color(0xFFB8C2CC).copy(alpha = 0.92f)
                } else {
                    Color(0xFFE2E8EE).copy(alpha = 0.90f)
                }
                val lineWidth = if (isMajor) 1.4f else 0.8f
                drawLine(lineColor, project(t, floorY3, -gridExtent), project(t, floorY3, gridExtent), lineWidth)
                drawLine(lineColor, project(-gridExtent, floorY3, t), project(gridExtent, floorY3, t), lineWidth)
            }

            val cameraGround = project(0f, floorY3 + 0.012f, 0f)
            val subjectGround = project(stableLateralOffsetMeters, floorY3 + 0.012f, -stableDistanceMeters)
            drawLine(Color(0xFFFF8A00).copy(alpha = 0.72f), cameraGround, subjectGround, 3f)
            drawCircle(Color(0xFFFF8A00).copy(alpha = 0.95f), radius = 6f, center = subjectGround)

            val axisLen = 0.75f
            val axisOriginX = 0f
            val axisOriginY = stableCameraHeightMeters
            val axisOriginZ = 0f
            val oProj = project(axisOriginX, axisOriginY, axisOriginZ)
            drawLine(Color(0xFFFF4444), oProj, project(axisOriginX + axisLen, axisOriginY, axisOriginZ), 3f)
            drawLine(Color(0xFF44FF88), oProj, project(axisOriginX, axisOriginY + axisLen, axisOriginZ), 3f)
            drawLine(Color(0xFF00E5FF), oProj, project(axisOriginX, axisOriginY, axisOriginZ - axisLen), 3f)
            drawCircle(Color(0xFFFFD54F), radius = 9f, center = oProj)
            drawCircle(Color(0xFFF57F17), radius = 12f, center = oProj, style = Stroke(width = 2.5f))

            val accentColor = Color(0xFF0E8A61)
            val jointFillColor = Color(0xFF102027)
            if (hasLandmarks) {
                BoneConnections.forEach { (a, b) ->
                    val va = selectedVisibility?.getOrNull(a) ?: 1f
                    val vb = selectedVisibility?.getOrNull(b) ?: 1f
                    if (va > 0.3f && vb > 0.3f) {
                        val alpha = ((va + vb) * 0.5f).coerceIn(0.3f, 1.0f)
                        drawLine(accentColor.copy(alpha = alpha),
                            Offset(projPx[a], projPy[a]), Offset(projPx[b], projPy[b]),
                            strokeWidth = 4.5f, cap = StrokeCap.Round)
                    }
                }
                FaceConnections.forEach { (a, b) ->
                    val va = selectedVisibility?.getOrNull(a) ?: 1f
                    val vb = selectedVisibility?.getOrNull(b) ?: 1f
                    if (va > 0.25f && vb > 0.25f)
                        drawLine(accentColor.copy(alpha = 0.40f),
                            Offset(projPx[a], projPy[a]), Offset(projPx[b], projPy[b]),
                            strokeWidth = 1.5f, cap = StrokeCap.Round)
                }
                HandConnections.forEach { (a, b) ->
                    val va = selectedVisibility?.getOrNull(a) ?: 1f
                    val vb = selectedVisibility?.getOrNull(b) ?: 1f
                    if (va > 0.25f && vb > 0.25f)
                        drawLine(accentColor.copy(alpha = 0.55f),
                            Offset(projPx[a], projPy[a]), Offset(projPx[b], projPy[b]),
                            strokeWidth = 2f, cap = StrokeCap.Round)
                }

                val majorJoints = setOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
                for (i in 0 until 33) {
                    val v = selectedVisibility?.getOrNull(i) ?: 1f
                    if (v > 0.2f) {
                        val px = Offset(projPx[i], projPy[i])
                        if (i in majorJoints) {
                            drawCircle(accentColor.copy(alpha = v * 0.35f), 14f, px, style = Stroke(2f))
                            drawCircle(jointFillColor.copy(alpha = v * 0.92f), 6f, px)
                        } else {
                            drawCircle(jointFillColor.copy(alpha = v * 0.68f), 3.5f, px)
                        }
                    }
                }

                for (idx in listOf(27, 28)) {
                    val v = selectedVisibility?.getOrNull(idx) ?: 1f
                    if (v > 0.3f) {
                        val shadow = project(jointX[idx], floorY3 + 0.01f, jointZ[idx])
                        drawOval(Color(0xFF90A4AE).copy(alpha = 0.18f),
                            topLeft = Offset(shadow.x - 18f, shadow.y - 5f),
                            size = Size(36f, 10f))
                    }
                }
            }

            drawLine(Color(0xFFCFD8DC).copy(alpha = 0.55f), Offset(0f, cy), Offset(W, cy), 1f)
        }

        if (!hasArFloorScene) {
            val waitingTitle = if (hasCurrentArFloor) {
                "Waiting for AR pose scale"
            } else {
                "Waiting for AR floor"
            }
            val waitingHelp = if (hasCurrentArFloor) {
                "Keep the person visible so the feet and ROI can anchor to the AR floor."
            } else {
                "Point at a textured floor and move the phone slowly."
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp)
                    .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        waitingTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF0E8A61),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        waitingHelp,
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        val sourceLabel = when {
            hasArFloorScene && hasUsableServerTechnicalPose -> "ARCore floor \u00b7 constrained DLT server 3D"
            hasUsableServerTechnicalPose -> "DLT server 3D"
            hasArFloorScene && hasServerTechnicalPose && hasClientTechnicalPose ->
                "ARCore floor \u00b7 client 33pt (server fallback: ${serverHealth.reason})"
            hasArFloorScene && hasClientTechnicalPose -> "ARCore floor \u00b7 client 33pt"
            hasArFloorScene -> "ARCore floor \u00b7 waiting for server"
            hasCurrentArFloor -> "ARCore floor \u00b7 waiting for pose"
            else -> "Waiting for AR floor"
        }
        TechnicalMetricsOverlay(
            sourceLabel = sourceLabel,
            uiState = uiState,
            visibleLandmarkCount = visibleLandmarkCount,
            subjectDistanceMeters = displayDistanceMeters,
            subjectHeightMeters = displayBodyHeightMeters,
            cameraHeightMeters = displayCameraHeightMeters,
            floorPitchDegrees = displayFloorPitchDegrees,
            lateralOffsetMeters = displayLateralOffsetMeters,
            bodyScaleMetrics = bodyScaleMetrics,
            serverHealth = serverHealth,
            serverDebug = serverDebug,
            worldTracking = worldTracking,
            sceneMetrics = arSceneMetrics,
            expanded = metricsExpanded,
            onToggle = { metricsExpanded = !metricsExpanded },
        )
    }
}

// ─── Technical Metrics Panel ─────────────────────────────────────────────────

private data class TechnicalBodyScaleMetrics(
    val heightMeters: Float?,
    val shoulderWidthMeters: Float?,
    val hipWidthMeters: Float?,
    val torsoMeters: Float?,
    val leftArmMeters: Float?,
    val rightArmMeters: Float?,
    val leftHandMeters: Float?,
    val rightHandMeters: Float?,
    val leftLegMeters: Float?,
    val rightLegMeters: Float?,
)

private fun technicalSkeletonFloorY(
    poseY: FloatArray,
    poseVisibility: FloatArray?,
    groundY: Float,
): Float {
    if (groundY.isFinite()) return groundY
    var floorY = Float.POSITIVE_INFINITY
    for (idx in intArrayOf(27, 28, 29, 30, 31, 32)) {
        val visible = poseVisibility?.getOrNull(idx) ?: 1f
        val y = poseY.getOrNull(idx) ?: continue
        if (visible > 0.18f && y.isFinite()) floorY = minOf(floorY, y)
    }
    if (floorY.isFinite()) return floorY

    for (idx in intArrayOf(23, 24, 25, 26, 27, 28)) {
        val visible = poseVisibility?.getOrNull(idx) ?: 1f
        val y = poseY.getOrNull(idx) ?: continue
        if (visible > 0.18f && y.isFinite()) floorY = minOf(floorY, y)
    }
    return floorY
}

private fun technicalSkeletonTopY(
    poseY: FloatArray,
    poseVisibility: FloatArray?,
): Float {
    var topY = Float.NEGATIVE_INFINITY
    for (idx in intArrayOf(0, 7, 8, 9, 10)) {
        val visible = poseVisibility?.getOrNull(idx) ?: 1f
        val y = poseY.getOrNull(idx) ?: continue
        if (visible > 0.18f && y.isFinite()) topY = maxOf(topY, y)
    }
    if (topY.isFinite()) return topY

    // Fallback for face dropout: exclude hands/feet so a raised wrist or foot
    // cannot shrink the whole Technical skeleton.
    for (idx in intArrayOf(11, 12, 13, 14, 23, 24, 25, 26)) {
        val visible = poseVisibility?.getOrNull(idx) ?: 1f
        val y = poseY.getOrNull(idx) ?: continue
        if (visible > 0.18f && y.isFinite()) topY = maxOf(topY, y)
    }
    return topY
}

private fun technicalScreenBodyVerticalRange(
    screenY: FloatArray,
    poseVisibility: FloatArray?,
): Pair<Float, Float>? {
    fun minY(indices: IntArray, minVisibility: Float): Float {
        var value = Float.POSITIVE_INFINITY
        for (idx in indices) {
            val visible = poseVisibility?.getOrNull(idx) ?: 1f
            val y = screenY.getOrNull(idx) ?: continue
            if (visible > minVisibility && y.isFinite()) value = minOf(value, y)
        }
        return value
    }

    fun maxY(indices: IntArray, minVisibility: Float): Float {
        var value = Float.NEGATIVE_INFINITY
        for (idx in indices) {
            val visible = poseVisibility?.getOrNull(idx) ?: 1f
            val y = screenY.getOrNull(idx) ?: continue
            if (visible > minVisibility && y.isFinite()) value = maxOf(value, y)
        }
        return value
    }

