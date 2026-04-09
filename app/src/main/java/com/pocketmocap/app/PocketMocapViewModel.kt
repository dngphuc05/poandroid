package com.pocketmocap.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import kotlin.math.min
import kotlin.math.sqrt
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketmocap.app.capture.CaptureSessionRecorder
import com.pocketmocap.app.network.LandmarkData
import com.pocketmocap.app.network.MocapServerClient
import com.pocketmocap.app.pipeline.BoneConstraintEngine
import com.pocketmocap.app.pipeline.HybridPosePipeline
import com.pocketmocap.app.pipeline.LandmarkFallbackEngine
import com.pocketmocap.app.pipeline.LandmarkKalman2D
import com.pocketmocap.app.tracking.CameraIntrinsics
import com.pocketmocap.app.tracking.BodyTurnTransitionDetector
import com.pocketmocap.app.tracking.enforceCanonicalLimbEndpoints
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.ServerPoseDebugSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import com.pocketmocap.app.tracking.authoritativeDistanceMetersOrNull
import com.pocketmocap.app.tracking.authoritativeHeightMetersOrNull
import com.pocketmocap.app.tracking.hasCanonicalMetricPose
import com.pocketmocap.app.tracking.hasV2MetricAuthority
import com.pocketmocap.app.ui.PhysicalSceneBias
import com.pocketmocap.app.ui.PhysicalSceneFactorGraph
import com.pocketmocap.app.ui.computePoseRoi
import com.pocketmocap.app.ui.deriveOverlayPoseEstimate
import com.pocketmocap.app.ui.evaluateServerPoseHealth
import com.pocketmocap.app.ui.classifyServerPoseMissingReason
import com.pocketmocap.bridge.PocketMocapBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class PocketMocapViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PocketMocapVM"
        private const val DEFAULT_SERVER_URL = "http://192.168.100.146:8090"
        // Joints moving faster than this per frame are likely MediaPipe glitches (outlier gate)
        private const val MAX_JOINT_DELTA = 0.15f  // ~48px at 320w
        // Below this visibility a joint is treated as fully occluded
        private const val VIS_OCCLUDE = 0.20f
        // Below this visibility a joint is uncertain (conservative EMA)
        private const val VIS_UNCERTAIN = 0.50f
        private const val MIN_STABLE_SERVER_FRAMES = 6
        private const val MAX_AR_SCENE_HOLD_FRAMES = 18
        private const val MAX_TECHNICAL_SCENE_MISSING_FRAMES = 15
        private const val MAX_VISIBLE_BODY_SCENE_HOLD_FRAMES = 150
        private const val NO_POSE_GRACE_FRAMES = 6
        private const val MIN_VISIBLE_JOINTS_FOR_SCENE_HOLD = 24
        private const val MIN_AR_SCENE_CONFIDENCE_FOR_SERVER = 0.35f
        private const val MIN_TECHNICAL_SCENE_CONFIDENCE_FOR_SERVER = 0.10f
        private const val MAX_HELD_CANONICAL_SERVER_POSE_FRAMES = 240
        private const val ENABLE_SERVER_METRIC_CLIENT_MOTION_OVERLAY = false
        private const val PHYSICAL_SCENE_BIAS_PREFS = "physical_scene_bias"
        private const val PREF_MANUAL_CAMERA_HEIGHT_M = "manual_camera_height_m"
        private const val PREF_SUBJECT_HEIGHT_ENABLED = "subject_height_enabled"
        private const val PREF_SUBJECT_HEIGHT_M = "subject_height_m"
    }

    // ── VRM Model ──
    data class VrmModel(val name: String, val uri: Uri)

    // ── UI State ──
    data class UiState(
        val serverUrl: String = DEFAULT_SERVER_URL,
        val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
        val sessionId: String = "",
        val pipelineState: HybridPosePipeline.PipelineState = HybridPosePipeline.PipelineState.IDLE,
        val calibrationStep: CalibrationStep = CalibrationStep.PENDING,
        val bootstrapProgress: Float = 0f,
        val latestPose3D: JSONObject? = null,
        val frameCount: Int = 0,
        val lastPipelineMs: Float = 0f,
        val mirrorDistance: Float = 1.0f,
        val manualCameraHeightMeters: Float = 1.17f,
        val manualSubjectHeightMeters: Float = Float.NaN,
        val errorMessage: String? = null,
        val shouldNavigateToCapture: Boolean = false,
        val vrmModels: List<VrmModel> = emptyList(),
