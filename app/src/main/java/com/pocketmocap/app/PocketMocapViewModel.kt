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
        val activeVrmIndex: Int = -1,
    )

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
    enum class CalibrationStep { PENDING, INTRINSIC_CALC, EXTRINSIC_ANCHOR, BOOTSTRAP, COMPLETE }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // High-frequency landmark state — standalone mutableStateOf bypasses StateFlow/coroutine
    // dispatch overhead and avoids UiState.copy() allocation on every camera frame.
    var poseLandmarksX by mutableStateOf<FloatArray?>(null)
        private set
    var poseLandmarksY by mutableStateOf<FloatArray?>(null)
        private set
    var poseLandmarksZ by mutableStateOf<FloatArray?>(null)
        private set
    var worldLandmarksX by mutableStateOf<FloatArray?>(null)
        private set
    var worldLandmarksY by mutableStateOf<FloatArray?>(null)
        private set
    var worldLandmarksZ by mutableStateOf<FloatArray?>(null)
        private set
    var poseVisibility by mutableStateOf<FloatArray?>(null)
        private set
    var visibleLandmarkCount by mutableStateOf(0)
        private set
    var cameraImageWidth by mutableStateOf(0)
        private set
    var cameraImageHeight by mutableStateOf(0)
        private set

    // Server 3D pose — triangulated by DLT from phone + virtual mirror, display-upright
    // and floor-grounded to match the backend/mocap-ui viewer without normalizing body size.
    var serverPoseX by mutableStateOf<FloatArray?>(null)
        private set
    var serverPoseY by mutableStateOf<FloatArray?>(null)
        private set
    var serverPoseZ by mutableStateOf<FloatArray?>(null)
        private set
    var serverPoseConf by mutableStateOf<FloatArray?>(null)
        private set
    var serverPoseStableFrames by mutableStateOf(0)
        private set
    var serverMetricPoseDisplayReady by mutableStateOf(false)
        private set
    var framesSentToServer by mutableStateOf(0)
        private set
    var pose3DReceivedCount by mutableStateOf(0)
        private set
    val serverMetricPoseExpected: Boolean
        get() = framesSentToServer > 0 ||
            pose3DReceivedCount > 0 ||
            serverPoseStableFrames > 0 ||
            serverMetricPoseDisplayReady
    var lastPose3DAgeMs by mutableStateOf<Long?>(null)
        private set
    var lastServerJointsCount by mutableStateOf(0)
        private set
    var lastServerMissingReason by mutableStateOf("no_pose3d_received")
        private set
    var lastServerTransport by mutableStateOf("unknown")
        private set
    var technicalPoseX by mutableStateOf<FloatArray?>(null)
        private set
    var technicalPoseY by mutableStateOf<FloatArray?>(null)
        private set
    var technicalPoseZ by mutableStateOf<FloatArray?>(null)
        private set
    var clientTechnicalPoseX by mutableStateOf<FloatArray?>(null)
        private set
    var clientTechnicalPoseY by mutableStateOf<FloatArray?>(null)
        private set
    var clientTechnicalPoseZ by mutableStateOf<FloatArray?>(null)
        private set
    var clientTechnicalPoseConf by mutableStateOf<FloatArray?>(null)
        private set
    var technicalDistanceMeters by mutableStateOf(Float.NaN)
        private set
    var technicalHeightMeters by mutableStateOf(Float.NaN)
        private set
    var technicalGroundY by mutableStateOf(Float.NaN)
        private set
    var latestWorldTracking by mutableStateOf<WorldTrackingSnapshot?>(null)
        private set
    var latestSceneMetrics by mutableStateOf<SceneMetricSnapshot?>(null)
        private set
    var latestTechnicalSceneMetrics by mutableStateOf<SceneMetricSnapshot?>(null)
        private set
    var latestServerPoseDebug by mutableStateOf<ServerPoseDebugSnapshot?>(null)
        private set
    var latestCameraIntrinsics by mutableStateOf<CameraIntrinsics?>(null)
        private set
    var isCaptureRecording by mutableStateOf(false)
        private set
    var activeCaptureFolderName by mutableStateOf<String?>(null)
        private set

    private val captureRecorder = CaptureSessionRecorder(getApplication())
    private val sceneBiasPrefs by lazy {
        getApplication<Application>().getSharedPreferences(PHYSICAL_SCENE_BIAS_PREFS, Context.MODE_PRIVATE)
    }
    private val physicalSceneGraph by lazy { PhysicalSceneFactorGraph(loadPhysicalSceneBias()) }
    private var sceneBiasSaveCountdown = 0
    private var lastPose3DReceivedAtMs = 0L
    private var serverMetricClientMotionOverlayActive = false
    private var lastCanonicalAuthoritativeHeightMeters = Float.NaN
    private var lastCanonicalAuthoritativeDistanceMeters = Float.NaN

    init {
        loadManualMetricProfile()
    }

    val hasStableServerPose: Boolean
        get() = serverPoseStableFrames >= MIN_STABLE_SERVER_FRAMES &&
            serverPoseX != null &&
            serverPoseY != null &&
            serverPoseZ != null

    /**
     * Direct-draw callback fired from the analysis thread immediately after the
     * client-side 33-point completion pass.
     * Bypasses Compose recomposition and Vsync coalescing — set by CaptureScreen,
     * cleared on dispose. Signature: (xNorm, yNorm, visibility, imageWidth, imageHeight)
     */
    var directLandmarkCallback: ((FloatArray, FloatArray, FloatArray, Int, Int) -> Unit)? = null

    // ── EMA + Kalman hybrid landmark stabilization ──
    // Visible joints (vis≥0.50): outlier-gated EMA, α=0.55–0.92; Kalman tracks velocity.
    // Uncertain joints (0.20≤vis<0.50): conservative EMA, outlier rejection.
    // Occluded joints (vis<0.20): Kalman predicts forward, then geometric fallback
    // rebuilds a usable 33-point pose before UI/server output.
    private val _kalman = Array(33) { LandmarkKalman2D(fps = 60f) }
    private val _smoothedX = FloatArray(33)
    private val _smoothedY = FloatArray(33)
    private val _completedX = FloatArray(33)
    private val _completedY = FloatArray(33)
    private val _completedVis = FloatArray(33)
    private val _lastReliable2DX = FloatArray(33)
    private val _lastReliable2DY = FloatArray(33)
    private val _hasLastReliable2D = BooleanArray(33)
    private val _lowConfidenceFrames = IntArray(33)
    private var _hasSmoothedLandmarks = false
    // EMA-smoothed visibility — prevents bone/joint flicker when MediaPipe vis oscillates near a threshold.
    // A bone at vis=0.28↔0.32 would otherwise flicker on/off each frame with the raw 0.30 draw gate.
    private val _smoothedVis = FloatArray(33) { 0.8f }

    // ── Bone constraint engine ──
    // Learns per-person bone proportions during bootstrap, then repositions
    // low-confidence joints to satisfy those ratios each frame.
    private val _boneConstraints = BoneConstraintEngine(minConfidence = 0.5f)
    private val _landmarkFallback = LandmarkFallbackEngine()
    private var serverCalibrationWidth = 1920
    private var serverCalibrationHeight = 1080
    private var autoResumeServerAfterReconnect = false
    private var userInitiatedDisconnect = false
    private var latestServerRotationDegrees = 0
    private val _lastGoodRelativeZ = FloatArray(33)
    private val _hasLastGoodRelativeZ = BooleanArray(33)
    private val _serverPoseSmoothX = FloatArray(33)
    private val _serverPoseSmoothY = FloatArray(33)
    private val _serverPoseSmoothZ = FloatArray(33)
    private val _clientTechnicalSmoothX = FloatArray(33)
    private val _clientTechnicalSmoothY = FloatArray(33)
    private val _clientTechnicalSmoothZ = FloatArray(33)
    private val _clientTechnicalBoneLengthMeters = FloatArray(33) { Float.NaN }
    private val _clientTechnicalTorsoLengthMeters = FloatArray(8) { Float.NaN }
    private var _hasServerPose = false
    private var _hasClientTechnicalPose = false
    private var lastPose3DInterarrivalMs = 0L
    private var sceneHoldFrames = 0
    private var technicalSceneMissingFrames = 0
    private var noPoseFrames = 0
    private var heldCanonicalServerPoseFrames = 0
    private var learnedHipVectorXNorm = Float.NaN
    private var learnedHipVectorYNorm = Float.NaN
    private val bodyTurnTransitionDetector = BodyTurnTransitionDetector()

    private fun limbParent(index: Int): Int = when (index) {
        13 -> 11
        15, 17, 19, 21 -> 13
        14 -> 12
        16, 18, 20, 22 -> 14
        25 -> 23
        27, 29, 31 -> 25
        26 -> 24
        28, 30, 32 -> 26
        else -> -1
    }

    private fun isServerRootOrBodyAnchor(index: Int): Boolean =
        index in intArrayOf(0, 7, 8, 11, 12, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32)

    private fun isServerFastLimbJoint(index: Int): Boolean =
        index in intArrayOf(13, 14, 15, 16, 17, 18, 19, 20, 21, 22)

    private fun serverDisplayMaxStepMeters(
        index: Int,
        recoveringFromStalePose: Boolean,
        turnFastUpdateActive: Boolean,
    ): Float {
        if (turnFastUpdateActive) {
            return when (index) {
                17, 18, 19, 20, 21, 22 -> 0.140f
                15, 16 -> 0.130f
                13, 14 -> 0.115f
                11, 12, 23, 24 -> 0.075f
                25, 26, 27, 28, 29, 30, 31, 32 -> 0.095f
                else -> 0.100f
            }
        }
        val normal = when (index) {
            17, 18, 19, 20, 21, 22 -> 0.070f
            15, 16 -> 0.085f
            13, 14 -> 0.070f
            11, 12, 23, 24 -> 0.040f
            25, 26, 27, 28, 29, 30, 31, 32 -> 0.055f
            else -> 0.055f
        }
        if (!recoveringFromStalePose) return normal
        return when (index) {
            17, 18, 19, 20, 21, 22 -> 0.040f
            15, 16 -> 0.055f
            13, 14 -> 0.050f
            11, 12, 23, 24 -> 0.026f
            25, 26, 27, 28, 29, 30, 31, 32 -> 0.040f
            else -> 0.040f
        }
    }

    private fun wouldFlipLimb(index: Int, candidateX: Float, candidateY: Float): Boolean {
        val parent = limbParent(index)
        if (parent < 0 || !_hasLastReliable2D[index]) return false
        val prevDx = _lastReliable2DX[index] - _smoothedX[parent]
        val prevDy = _lastReliable2DY[index] - _smoothedY[parent]
        val candDx = candidateX - _smoothedX[parent]
        val candDy = candidateY - _smoothedY[parent]
        val prevLen = sqrt(prevDx * prevDx + prevDy * prevDy)
        val candLen = sqrt(candDx * candDx + candDy * candDy)
        if (prevLen < 0.025f || candLen < 0.025f) return false
        val cosine = (prevDx * candDx + prevDy * candDy) / (prevLen * candLen)
        return cosine < -0.12f
    }

    // ── Server Client ──
    private val serverClient = MocapServerClient(getApplication(), object : MocapServerClient.Listener {
        override fun onConnected(sessionId: String) {
            Log.i(TAG, "onConnected: session=$sessionId")
            val shouldAutoResume =
                autoResumeServerAfterReconnect &&
                    serverCalibrationWidth > 0 &&
                    serverCalibrationHeight > 0 &&
                    pipeline != null
            autoResumeServerAfterReconnect = false
            userInitiatedDisconnect = false
            clearServerPoseArrays()
            resetServerTransportDiagnostics()
            _uiState.update { it.copy(
                connectionState = ConnectionState.CONNECTED,
                sessionId = sessionId,
                errorMessage = null,
            )}
            if (shouldAutoResume) {
                Log.i(TAG, "Auto-resuming server calibration after reconnect")
                resetClientTrackingState()
                latestCameraIntrinsics = readCameraIntrinsics(serverCalibrationWidth, serverCalibrationHeight)
                _uiState.update { it.copy(calibrationStep = CalibrationStep.BOOTSTRAP) }
                pipeline?.beginCalibration(serverCalibrationWidth, serverCalibrationHeight)
            }
        }

