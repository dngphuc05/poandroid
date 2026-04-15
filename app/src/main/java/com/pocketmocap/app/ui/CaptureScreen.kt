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
