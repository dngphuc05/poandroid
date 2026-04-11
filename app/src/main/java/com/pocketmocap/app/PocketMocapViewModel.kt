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

        override fun onDisconnected() {
            Log.i(TAG, "onDisconnected")
            autoResumeServerAfterReconnect =
                !userInitiatedDisconnect &&
                    (
                        _uiState.value.pipelineState == HybridPosePipeline.PipelineState.CAPTURING ||
                            framesSentToServer > 0 ||
                            pose3DReceivedCount > 0
                    )
            clearServerPoseArrays()
            resetServerTransportDiagnostics()
            _uiState.update { it.copy(connectionState = ConnectionState.DISCONNECTED) }
        }

        override fun onCalibrationAck(success: Boolean, state: String) {
            Log.i(TAG, "onCalibrationAck: success=$success state=$state")
            if (success) {
                _uiState.update { it.copy(calibrationStep = CalibrationStep.BOOTSTRAP) }
                pipeline?.onCalibrationConfirmed()
            } else {
                _uiState.update { it.copy(errorMessage = "Calibration failed") }
            }
        }

        override fun onBootstrapProgress(collected: Int, target: Int, complete: Boolean) {
            Log.i(TAG, "onBootstrapProgress: $collected/$target complete=$complete")
            val progress = if (target > 0) collected.toFloat() / target else 0f
            _uiState.update { it.copy(bootstrapProgress = progress) }
            if (complete) {
                _uiState.update { it.copy(calibrationStep = CalibrationStep.COMPLETE) }
                pipeline?.onBootstrapComplete()
            }
        }

        override fun onPose3DReceived(pose3dJson: JSONObject) {
            val ms = pose3dJson.optDouble("pipeline_ms", 0.0).toFloat()
            val serverDebugJson = pose3dJson.optJSONObject("server_debug")
            val mlEvidenceJson = pose3dJson.optJSONObject("ml_evidence")
            val nowMs = System.currentTimeMillis()
            pose3DReceivedCount += 1
            lastPose3DInterarrivalMs = if (lastPose3DReceivedAtMs > 0L) {
                (nowMs - lastPose3DReceivedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }
            lastPose3DReceivedAtMs = nowMs
            lastPose3DAgeMs = 0L
            _uiState.update { it.copy(
                latestPose3D = pose3dJson,
                frameCount = it.frameCount + 1,
                lastPipelineMs = ms,
            )}
            updateServerPoseDebug(
                json = serverDebugJson,
                mlEvidenceJson = mlEvidenceJson,
            )
            updateSceneMetricsFromServer(pose3dJson.optJSONObject("scene_metrics"), latestServerPoseDebug)
            lastServerMissingReason = updateServerPoseArrays(pose3dJson)
            externalPipelineListener?.onPose3DReceived(pose3dJson)
        }

        override fun onError(message: String) {
            Log.e(TAG, "Server error: $message")
            _uiState.update { it.copy(errorMessage = message) }
        }

        override fun onRtcChannelReady() {
            Log.i(TAG, "WebRTC DataChannel ready — low-latency transport active")
        }
    })

    // ── Pipeline ──
    var pipeline: HybridPosePipeline? = null
        private set
    private var externalPipelineListener: HybridPosePipeline.Listener? = null

    private fun ensurePipeline() {
        if (pipeline != null) return
        Log.i(TAG, "ensurePipeline: creating HybridPosePipeline")
        try {
            val bridge = PocketMocapBridge.getInstance()
            pipeline = HybridPosePipeline(
                context = getApplication(),
                intrinsicsJsonProvider = { bridge.getCameraIntrinsicsJson(getApplication()) },
                serverClient = serverClient,
                listener = object : HybridPosePipeline.Listener {
                    override fun onStateChanged(state: HybridPosePipeline.PipelineState) {
                        if (state == HybridPosePipeline.PipelineState.CAPTURING) {
                            _boneConstraints.build()  // bootstrap complete — lock in bone lengths
                        }
                        _uiState.update { it.copy(pipelineState = state) }
                        externalPipelineListener?.onStateChanged(state)
                    }

                    override fun onPose3DReceived(pose3dJson: JSONObject) {
                        externalPipelineListener?.onPose3DReceived(pose3dJson)
                    }

                    override fun prepareServerLandmarks(
                        rawLandmarks: List<LandmarkData>,
                        imageWidth: Int,
                        imageHeight: Int,
                        rotationDegrees: Int,
                    ): List<LandmarkData>? {
                        if (!_hasSmoothedLandmarks) return rawLandmarks

                        latestServerRotationDegrees = rotationDegrees
                        val outboundWidth = if (serverCalibrationWidth > 0) serverCalibrationWidth else imageWidth
                        val outboundHeight = if (serverCalibrationHeight > 0) serverCalibrationHeight else imageHeight
                        val prepared = ArrayList<LandmarkData>(33)
                        for (i in 0 until 33) {
                            val raw = rawLandmarks.getOrNull(i) ?: LandmarkData(0f, 0f)
                            val rawConfidence = maxOf(raw.visibility, raw.presence, raw.confidence)
                            if (rawConfidence >= VIS_UNCERTAIN) {
                                _lastGoodRelativeZ[i] = raw.z
                                _hasLastGoodRelativeZ[i] = true
                            }
                            val (sensorX, sensorY) = displayToSensorSpace(
                                _completedX[i].coerceIn(0f, 1f),
                                _completedY[i].coerceIn(0f, 1f),
                                rotationDegrees,
                            )
                            val stabilizedConfidence = _completedVis[i].coerceIn(0f, 1f)
                            val stabilizedZ = when {
                                rawConfidence >= VIS_OCCLUDE -> raw.z
                                _hasLastGoodRelativeZ[i] -> _lastGoodRelativeZ[i]
                                else -> raw.z
                            }

                            prepared.add(
                                raw.copy(
                                    x = sensorX.coerceIn(0f, 1f) * outboundWidth,
                                    y = sensorY.coerceIn(0f, 1f) * outboundHeight,
                                    z = stabilizedZ,
                                    visibility = stabilizedConfidence,
                                    presence = stabilizedConfidence,
                                    confidence = stabilizedConfidence,
                                )
                            )
                        }
                        return prepared
                    }

                    override fun prepareMlImageCropLandmarks(
                        rawXNorm: FloatArray,
                        rawYNorm: FloatArray,
                        rawVisibility: FloatArray,
                    ): HybridPosePipeline.MlCropLandmarks? {
                        if (!_hasSmoothedLandmarks ||
                            rawXNorm.size < 33 ||
                            rawYNorm.size < 33 ||
                            rawVisibility.size < 33
                        ) {
                            return null
                        }
                        return HybridPosePipeline.MlCropLandmarks(
                            xNorm = _smoothedX.copyOf(),
                            yNorm = _smoothedY.copyOf(),
                            visibility = _smoothedVis.copyOf(),
                        )
                    }

                    override fun onLandmarksDetected(
                        xNorm: FloatArray,
                        yNorm: FloatArray,
                        visibility: FloatArray,
                        zWorld: FloatArray?,
                        xWorld: FloatArray?,
                        yWorld: FloatArray?,
                        imageWidth: Int,
                        imageHeight: Int,
                        worldTracking: WorldTrackingSnapshot?,
                        visualTopYNorm: Float,
                        visualTopConfidence: Float,
                    ) {
                        noPoseFrames = 0
                        val state = _uiState.value.pipelineState
                        // Collect raw MediaPipe landmarks for bone-length learning
                        if (state == HybridPosePipeline.PipelineState.BOOTSTRAPPING ||
                            (!_boneConstraints.isReady && state == HybridPosePipeline.PipelineState.CAPTURING)) {
                            _boneConstraints.collectFrame(xNorm, yNorm, visibility)
                            if (!_boneConstraints.isReady) _boneConstraints.tryBuild()
                        }
                        if (!_hasSmoothedLandmarks) {
                            // First frame: seed positions AND smoothed visibility from raw MediaPipe
                            xNorm.copyInto(_smoothedX)
                            yNorm.copyInto(_smoothedY)
                            for (i in 0 until 33) {
                                _smoothedVis[i] = visibility[i].coerceIn(0f, 1f)
                                _lowConfidenceFrames[i] = if (_smoothedVis[i] < VIS_UNCERTAIN) 1 else 0
                                if (_smoothedVis[i] >= VIS_UNCERTAIN) {
                                    _lastReliable2DX[i] = _smoothedX[i]
                                    _lastReliable2DY[i] = _smoothedY[i]
                                    _hasLastReliable2D[i] = true
                                }
                                _kalman[i].update(_smoothedX[i], _smoothedY[i], visible = true)
                            }
                            _hasSmoothedLandmarks = true
                        } else {
                            // Smooth MediaPipe visibility — prevents rapid band-switching and bone/joint
                            // flicker when vis oscillates near 0.20/0.30/0.50 threshold boundaries.
                            // α=0.25 → time constant ~3.5 frames (58ms fade), eliminates single-frame pops.
                            for (i in 0 until 33) {
                                _smoothedVis[i] = 0.25f * visibility[i].coerceIn(0f, 1f) + 0.75f * _smoothedVis[i]
                            }
                            for (i in 0 until 33) {
                                val vis = _smoothedVis[i]  // smoothed — avoids mid-frame band switching
                                val rawVis = visibility[i].coerceIn(0f, 1f)
                                val reappearing = _lowConfidenceFrames[i] >= 2 && rawVis >= VIS_UNCERTAIN
                                val effectiveVis = if (reappearing) rawVis else vis
                                if (reappearing) {
                                    _smoothedVis[i] = maxOf(_smoothedVis[i], rawVis)
                                }
                                val dx = xNorm[i] - _smoothedX[i]
                                val dy = yNorm[i] - _smoothedY[i]
                                val dist = sqrt(dx * dx + dy * dy)
                                when {
                                    effectiveVis >= VIS_UNCERTAIN -> {
                                        // ── Clearly visible: speed-adaptive EMA with outlier gate ──────
                                        val maxDelta = if (reappearing) 0.32f else MAX_JOINT_DELTA
                                        val safeX = if (dist > maxDelta)
                                            _smoothedX[i] + dx * (maxDelta / dist) else xNorm[i]
                                        val safeY = if (dist > maxDelta)
                                            _smoothedY[i] + dy * (maxDelta / dist) else yNorm[i]
                                        // Saturate at 0.04 (~13px at 320): α=0.18 when still → 0.85 when fast.
                                        // Low floor = heavy smoothing for stationary joints (3× noise reduction),
                                        // high ceiling = near-zero lag during genuine fast movements.
                                        val normSpeed = (dist / 0.04f).coerceIn(0f, 1f)
                                        val alpha = if (reappearing) {
                                            (0.58f + normSpeed * 0.30f).coerceIn(0.58f, 0.88f)
                                        } else {
                                            (0.18f + normSpeed * 0.67f).coerceIn(0.18f, 0.85f)
                                        }
                                        _smoothedX[i] = alpha * safeX + (1f - alpha) * _smoothedX[i]
                                        _smoothedY[i] = alpha * safeY + (1f - alpha) * _smoothedY[i]
                                        _kalman[i].update(_smoothedX[i], _smoothedY[i], visible = true)
                                    }
                                    effectiveVis >= VIS_OCCLUDE -> {
                                        // ── Uncertain (0.20–0.50): conservative EMA, reject outliers ────
                                        if (dist < MAX_JOINT_DELTA && !wouldFlipLimb(i, xNorm[i], yNorm[i])) {
                                            val normSpeed = (dist / 0.04f).coerceIn(0f, 1f)
                                            val alpha = (0.12f + normSpeed * 0.28f).coerceIn(0.12f, 0.40f)
                                            _smoothedX[i] = alpha * xNorm[i] + (1f - alpha) * _smoothedX[i]
                                            _smoothedY[i] = alpha * yNorm[i] + (1f - alpha) * _smoothedY[i]
                                        } else if (_hasLastReliable2D[i]) {
                                            _smoothedX[i] = _lastReliable2DX[i]
                                            _smoothedY[i] = _lastReliable2DY[i]
                                        }
                                        _kalman[i].update(_smoothedX[i], _smoothedY[i], visible = true)
                                    }
                                    else -> {
                                        val predicted = _kalman[i].update(_smoothedX[i], _smoothedY[i], visible = false)
                                        _smoothedX[i] = predicted.first
                                        _smoothedY[i] = predicted.second
                                        if (_hasLastReliable2D[i]) {
                                            _smoothedX[i] = _lastReliable2DX[i]
                                            _smoothedY[i] = _lastReliable2DY[i]
                                        }
                                    }
                                }
                                if (effectiveVis >= VIS_UNCERTAIN) {
                                    _lastReliable2DX[i] = _smoothedX[i]
                                    _lastReliable2DY[i] = _smoothedY[i]
                                    _hasLastReliable2D[i] = true
                                }
                                _lowConfidenceFrames[i] = if (rawVis < VIS_UNCERTAIN) {
                                    (_lowConfidenceFrames[i] + 1).coerceAtMost(120)
                                } else {
                                    0
                                }
                            }
                            // Apply constraints using smoothed vis — avoids oscillation at threshold
                            _boneConstraints.apply(_smoothedX, _smoothedY, _smoothedVis, targetThreshold = VIS_UNCERTAIN)
                            for (i in 0 until 33) {
                                if (_smoothedVis[i] < VIS_OCCLUDE) {
                                    _kalman[i].setPosition(_smoothedX[i], _smoothedY[i])
                                }
                            }
                        }
                        _landmarkFallback.complete(
                            _smoothedX,
                            _smoothedY,
                            _smoothedVis,
                            _completedX,
                            _completedY,
                            _completedVis,
                        )
                        val visible = _completedVis.count { it > 0.5f }
                        // Drive UI and outbound server packets from the completed 33-point set so a
                        // whole missing limb can still be reconstructed client-side in the same frame.
                        directLandmarkCallback?.invoke(_completedX, _completedY, _completedVis, imageWidth, imageHeight)
                        // mutableStateOf writes for warning banner, joint count, server fallback
                        poseLandmarksX = _completedX.copyOf()
                        poseLandmarksY = _completedY.copyOf()
                        poseLandmarksZ = zWorld
                        worldLandmarksX = xWorld
                        worldLandmarksY = yWorld
                        worldLandmarksZ = zWorld
                        poseVisibility = _completedVis.copyOf()
                        updateSceneMetrics(worldTracking, visualTopYNorm, visualTopConfidence)
                        updateClientTechnicalPose()
                        updateServerMetricPoseWithClientMotion()
                        recordCaptureFrameIfNeeded()
                        // Guard constant/rarely-changing values — avoids spurious Compose recompositions
                        if (visibleLandmarkCount != visible) visibleLandmarkCount = visible
                        if (cameraImageWidth != imageWidth) cameraImageWidth = imageWidth
                        if (cameraImageHeight != imageHeight) cameraImageHeight = imageHeight
                    }

                    override fun onNoPoseDetected() {
                        noPoseFrames += 1
                        if (_hasSmoothedLandmarks && noPoseFrames <= NO_POSE_GRACE_FRAMES) {
                            ageTechnicalSceneMetrics(allowVisibleBodyHold = true)
                            return
                        }
                        _hasSmoothedLandmarks = false
                        _smoothedVis.fill(0f)
                        _kalman.forEach { it.reset() }
                        _boneConstraints.reset()
                        _landmarkFallback.reset()
                        _hasLastGoodRelativeZ.fill(false)
                        poseLandmarksX = null
                        poseLandmarksY = null
                        poseLandmarksZ = null
                        worldLandmarksX = null
                        worldLandmarksY = null
                        worldLandmarksZ = null
                        poseVisibility = null
                        clearClientTechnicalPoseArrays()
                        clearServerPoseArrays()
                        latestWorldTracking = null
                        latestSceneMetrics = null
                        latestTechnicalSceneMetrics = null
                        visibleLandmarkCount = 0
                        _hasLastReliable2D.fill(false)
                    }

                    override fun prepareServerSceneMetrics(worldTracking: WorldTrackingSnapshot?): SceneMetricSnapshot? {
                        latestTechnicalSceneMetrics
                            ?.takeIf {
                                it.source == "arcore_floor" &&
                                    it.confidence >= MIN_TECHNICAL_SCENE_CONFIDENCE_FOR_SERVER &&
                                    (
                                        it.distanceMeters.isFinite() ||
                                            it.correctedDistanceMeters.isFinite() ||
                                            it.bodyHeightMeters.isFinite() ||
                                            it.correctedHeightMeters.isFinite()
                                    )
                            }
                            ?.let { return it.withManualSubjectHeightProfile() }
                        return worldTracking?.takeIf {
                            it.source == "arcore_floor" &&
                                it.confidence >= MIN_AR_SCENE_CONFIDENCE_FOR_SERVER &&
                                (it.subjectDistanceMeters.isFinite() || it.subjectHeightMeters.isFinite())
                        }?.let {
                            SceneMetricSnapshot(
                                source = it.source,
                                confidence = it.confidence,
                                distanceMeters = it.subjectDistanceMeters,
                                bodyHeightMeters = it.subjectHeightMeters,
                                cameraHeightMeters = it.cameraHeightMeters,
                                floorPitchDegrees = it.floorPitchDegrees,
                                lateralOffsetMeters = it.lateralOffsetMeters,
                            ).withManualSubjectHeightProfile()
                        }
                    }

                    override fun onServerFrameQueued(frameIndex: Int, timestampUs: Long, transportHint: String) {
                        framesSentToServer += 1
                        lastServerTransport = transportHint
                        lastPose3DAgeMs = if (pose3DReceivedCount > 0) {
                            System.currentTimeMillis() - lastPose3DReceivedAtMs
                        } else {
                            null
                        }
                    }
                },
            )
            pipeline?.start()
            Log.i(TAG, "ensurePipeline: pipeline created OK")
        } catch (e: Exception) {
            Log.e(TAG, "ensurePipeline: FAILED", e)
            _uiState.update { it.copy(errorMessage = "Pipeline init failed: ${e.message}") }
        }
    }

    fun initPipeline(listener: HybridPosePipeline.Listener) {
        externalPipelineListener = listener
        ensurePipeline()
    }

    /** Feed a camera frame into the pipeline (called from CaptureScreen). */
    fun onCameraFrame(frame: CapturedCameraFrame) {
        pipeline?.onCameraFrame(frame)
    }

    private fun displayToSensorSpace(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (rotationDegrees) {
            90  -> Pair(y, 1f - x)
            180 -> Pair(1f - x, 1f - y)
            270 -> Pair(1f - y, x)
            else -> Pair(x, y)
        }

    private fun clearServerPoseArrays() {
        clearDisplayedServerPoseArrays()
        clearClientTechnicalPoseArrays()
        technicalDistanceMeters = Float.NaN
        technicalHeightMeters = Float.NaN
        technicalGroundY = Float.NaN
        latestWorldTracking = null
        latestSceneMetrics = null
        latestTechnicalSceneMetrics = null
        latestServerPoseDebug = null
        sceneHoldFrames = 0
        technicalSceneMissingFrames = 0
        noPoseFrames = 0
        learnedHipVectorXNorm = Float.NaN
        learnedHipVectorYNorm = Float.NaN
        lastCanonicalAuthoritativeHeightMeters = Float.NaN
        lastCanonicalAuthoritativeDistanceMeters = Float.NaN
        bodyTurnTransitionDetector.reset()
    }

    private fun clearDisplayedServerPoseArrays() {
        serverPoseX = null
        serverPoseY = null
        serverPoseZ = null
        serverPoseConf = null
        serverMetricPoseDisplayReady = false
        serverMetricClientMotionOverlayActive = false
        serverPoseStableFrames = 0
        technicalPoseX = null
        technicalPoseY = null
        technicalPoseZ = null
        technicalGroundY = Float.NaN
        _hasServerPose = false
        heldCanonicalServerPoseFrames = 0
        _serverPoseSmoothX.fill(0f)
        _serverPoseSmoothY.fill(0f)
        _serverPoseSmoothZ.fill(0f)
        lastPose3DInterarrivalMs = 0L
    }

    private fun holdPreviousServerPoseOrClear(reason: String): String {
        serverMetricClientMotionOverlayActive = false
        val canHoldCanonicalPose =
            _hasServerPose &&
            serverPoseX != null &&
            serverPoseY != null &&
            serverPoseZ != null &&
            serverMetricPoseDisplayReady &&
            heldCanonicalServerPoseFrames < MAX_HELD_CANONICAL_SERVER_POSE_FRAMES
        return if (canHoldCanonicalPose) {
            heldCanonicalServerPoseFrames += 1
            serverMetricPoseDisplayReady = true
            reason
        } else {
            serverMetricPoseDisplayReady = false
            clearDisplayedServerPoseArrays()
            reason
        }
    }

    private fun clearClientTechnicalPoseArrays() {
        clientTechnicalPoseX = null
        clientTechnicalPoseY = null
        clientTechnicalPoseZ = null
        clientTechnicalPoseConf = null
        _hasClientTechnicalPose = false
        _clientTechnicalSmoothX.fill(0f)
        _clientTechnicalSmoothY.fill(0f)
        _clientTechnicalSmoothZ.fill(0f)
        _clientTechnicalTorsoLengthMeters.fill(Float.NaN)
    }

    private fun resetServerTransportDiagnostics() {
        framesSentToServer = 0
        pose3DReceivedCount = 0
        lastPose3DReceivedAtMs = 0L
        lastPose3DAgeMs = null
        lastServerJointsCount = 0
        lastServerMissingReason = "no_pose3d_received"
        lastServerTransport = "unknown"
    }

    private fun updateServerPoseDebug(json: JSONObject?, mlEvidenceJson: JSONObject? = null) {
        latestServerPoseDebug = ServerPoseDebugSnapshot.fromJson(
            json = json,
            fallbackScene = latestTechnicalSceneMetrics ?: latestSceneMetrics,
            mlEvidenceJson = mlEvidenceJson,
        )
    }

    private fun updateSceneMetrics(
        worldTracking: WorldTrackingSnapshot?,
        visualTopYNorm: Float = Float.NaN,
        visualTopConfidence: Float = Float.NaN,
    ) {
        latestWorldTracking = worldTracking
        worldTracking?.intrinsics?.let { latestCameraIntrinsics = it }
        val roi = computePoseRoi(_completedX, _completedY, _completedVis)
        val previousSceneMetrics = latestTechnicalSceneMetrics ?: latestSceneMetrics
        val previousHeightLockState = previousSceneMetrics?.heightLockState.orEmpty()
        val previousHeightIsConstraint =
            previousHeightLockState == "locked" ||
                (previousHeightLockState.startsWith("holding") && "untrusted" !in previousHeightLockState)
        val previousPhysicalHeight = previousSceneMetrics
            ?.correctedHeightMeters
            ?.takeIf { previousHeightIsConstraint && it.isFinite() }
            ?: previousSceneMetrics
                ?.bodyHeightMeters
                ?.takeIf { previousHeightIsConstraint && it.isFinite() }
        val estimate = deriveOverlayPoseEstimate(
            roi = roi,
            screenX = _completedX,
            screenY = _completedY,
            visibility = _completedVis,
            rawSubjectHeightMeters = previousPhysicalHeight ?: Float.NaN,
            worldTracking = worldTracking,
            intrinsics = worldTracking?.intrinsics ?: latestCameraIntrinsics,
            previousHipVectorXNorm = learnedHipVectorXNorm,
            previousHipVectorYNorm = learnedHipVectorYNorm,
            visualTopYNorm = visualTopYNorm,
            visualTopConfidence = visualTopConfidence,
        )
        if (estimate == null) {
            ageTechnicalSceneMetrics(allowVisibleBodyHold = true)
            return
        }
        if (estimate.learnedHipVectorXNorm.isFinite() && estimate.learnedHipVectorYNorm.isFinite()) {
            learnedHipVectorXNorm = estimate.learnedHipVectorXNorm
            learnedHipVectorYNorm = estimate.learnedHipVectorYNorm
        }
        val metrics = estimate.toSceneMetricSnapshot().withManualSubjectHeightProfile()
        val solvedMetrics = if (metrics.source == "arcore_floor") {
            physicalSceneGraph.solve(metrics).also { maybePersistPhysicalSceneBias() }
        } else {
            metrics
        }
        val technicalMetrics = solvedMetrics
            .takeIf { it.source == "arcore_floor" }
            ?.let { smoothTechnicalSceneMetrics(it) }
        if (technicalMetrics != null) {
            latestTechnicalSceneMetrics = technicalMetrics
            technicalSceneMissingFrames = 0
        } else {
            ageTechnicalSceneMetrics(allowVisibleBodyHold = true)
        }
        val previous = latestSceneMetrics
        val stickyMetrics =
            if (technicalMetrics != null) {
                technicalMetrics
            } else if (
                solvedMetrics.source == "roi_fallback" &&
                previous?.source == "arcore_floor" &&
                sceneHoldFrames < MAX_AR_SCENE_HOLD_FRAMES &&
                solvedMetrics.distanceMeters.isFinite() &&
                previous.distanceMeters.isFinite() &&
                kotlin.math.abs(solvedMetrics.distanceMeters - previous.distanceMeters) < 0.16f
            ) {
                sceneHoldFrames += 1
                previous.copy(
                    distanceMeters = previous.distanceMeters * 0.78f + solvedMetrics.distanceMeters * 0.22f,
                    bodyHeightMeters = previous.bodyHeightMeters.takeIf { it.isFinite() } ?: solvedMetrics.bodyHeightMeters,
                    cameraHeightMeters = previous.cameraHeightMeters.takeIf { it.isFinite() } ?: solvedMetrics.cameraHeightMeters,
                    lateralOffsetMeters = previous.lateralOffsetMeters.takeIf { it.isFinite() } ?: solvedMetrics.lateralOffsetMeters,
                )
            } else {
                sceneHoldFrames = if (solvedMetrics.source == "arcore_floor") 0 else sceneHoldFrames
                solvedMetrics
        }
        latestSceneMetrics = stickyMetrics
        latestTechnicalSceneMetrics?.let { technicalMetrics ->
            if (technicalMetrics.distanceMeters.isFinite()) {
                technicalDistanceMeters = technicalMetrics.distanceMeters
            }
            if (technicalMetrics.bodyHeightMeters.isFinite()) {
                technicalHeightMeters = technicalMetrics.bodyHeightMeters
            }
        }
    }

    private fun updateSceneMetricsFromServer(
        json: JSONObject?,
        serverDebug: ServerPoseDebugSnapshot? = latestServerPoseDebug,
    ) {
        json ?: return
        val jsonCorrectedDistance = json.optDouble("corrected_distance_m", Double.NaN).toFloat()
        val serverConstrainedDistance = serverDebug?.authoritativeDistanceMetersOrNull(jsonCorrectedDistance)
        val jsonCorrectedHeight = json.optDouble("corrected_height_m", Double.NaN).toFloat()
        val serverConstrainedHeight = serverDebug?.authoritativeHeightMetersOrNull(jsonCorrectedHeight)
        val metrics = SceneMetricSnapshot(
            source = json.optString("source", latestSceneMetrics?.source ?: "server"),
            confidence = json.optDouble("confidence", latestSceneMetrics?.confidence?.toDouble() ?: 0.0).toFloat(),
            distanceMeters = serverConstrainedDistance
                ?: json.optDouble("distance_m", latestSceneMetrics?.distanceMeters?.toDouble() ?: Double.NaN).toFloat(),
            bodyHeightMeters = serverConstrainedHeight
                ?: json.optDouble("body_height_m", latestSceneMetrics?.bodyHeightMeters?.toDouble() ?: Double.NaN).toFloat(),
            cameraHeightMeters = json.optDouble("camera_height_m", latestSceneMetrics?.cameraHeightMeters?.toDouble() ?: Double.NaN).toFloat(),
            floorPitchDegrees = json.optDouble("floor_pitch_deg", latestSceneMetrics?.floorPitchDegrees?.toDouble() ?: Double.NaN).toFloat(),
            lateralOffsetMeters = json.optDouble("lateral_offset_m", latestSceneMetrics?.lateralOffsetMeters?.toDouble() ?: Double.NaN).toFloat(),
            correctedDistanceMeters = serverConstrainedDistance
                ?: jsonCorrectedDistance,
            correctedHeightMeters = serverConstrainedHeight
                ?: jsonCorrectedHeight,
            correctedCameraHeightMeters = json.optDouble("corrected_camera_height_m", Double.NaN).toFloat(),
            localHeightCandidateMeters = json.optDouble("local_height_candidate_m", Double.NaN).toFloat(),
            localHeightCandidateConfidence = json.optDouble("local_height_candidate_confidence", Double.NaN).toFloat(),
            localHeightCandidateSource = json.optString("local_height_candidate_source", ""),
            profileSubjectHeightMeters = json.optDouble("profile_subject_height_m", Double.NaN).toFloat(),
            profileSubjectHeightConfidence = json.optDouble("profile_subject_height_confidence", Double.NaN).toFloat(),
            profileSubjectHeightSource = json.optString("profile_subject_height_source", ""),
            floorSource = json.optString("floor_source", ""),
            solverConfidence = json.optDouble("solver_confidence", Double.NaN).toFloat(),
            solverResidualMeters = json.optDouble("solver_residual_m", Double.NaN).toFloat(),
            floorHeightBiasMeters = json.optDouble("floor_height_bias_m", Double.NaN).toFloat(),
            depthScale = json.optDouble("depth_scale", Double.NaN).toFloat(),
            depthOffsetMeters = json.optDouble("depth_offset_m", Double.NaN).toFloat(),
            heightEndpointBiasMeters = json.optDouble("height_endpoint_bias_m", Double.NaN).toFloat(),
            heightLockState = json.optString("height_lock_state", ""),
            distanceCandidateSpreadMeters = json.optDouble("distance_candidate_spread_m", Double.NaN).toFloat(),
            heightCandidateSpreadMeters = json.optDouble("height_candidate_spread_m", Double.NaN).toFloat(),
            rawHipDepthDistanceMeters = json.optDouble("raw_hip_depth_distance_m", Double.NaN).toFloat(),
            footPlaneDistanceMeters = json.optDouble("foot_plane_distance_m", Double.NaN).toFloat(),
            roiDistanceMeters = json.optDouble("roi_distance_m", Double.NaN).toFloat(),
            topRayHeightMeters = json.optDouble("top_ray_height_m", Double.NaN).toFloat(),
            leftFootRayFloorDistanceMeters = json.optDouble("left_foot_ray_floor_distance_m", Double.NaN).toFloat(),
            rightFootRayFloorDistanceMeters = json.optDouble("right_foot_ray_floor_distance_m", Double.NaN).toFloat(),
            feetMidpointFloorDistanceMeters = json.optDouble("feet_midpoint_floor_distance_m", Double.NaN).toFloat(),
            nearestFootFloorDistanceMeters = json.optDouble("nearest_foot_floor_distance_m", Double.NaN).toFloat(),
            footRayFloorSpreadMeters = json.optDouble("foot_ray_floor_spread_m", Double.NaN).toFloat(),
            topRayFloorHeightMeters = json.optDouble("top_ray_floor_height_m", Double.NaN).toFloat(),
            visualTopScanYNorm = json.optDouble("visual_top_scan_y_norm", Double.NaN).toFloat(),
            visualTopScanConfidence = json.optDouble("visual_top_scan_confidence", Double.NaN).toFloat(),
            visualTopLiftNorm = json.optDouble("visual_top_lift_norm", Double.NaN).toFloat(),
            distanceHeightGeometryResidualMeters = json.optDouble("distance_height_geometry_residual_m", Double.NaN).toFloat(),
            rootHipRayFloorDistanceMeters = json.optDouble("root_hip_ray_floor_distance_m", Double.NaN).toFloat(),
            pixelSpanHeightMeters = json.optDouble("pixel_span_height_m", Double.NaN).toFloat(),
            hipGeometryDistanceMeters = json.optDouble("hip_geometry_distance_m", Double.NaN).toFloat(),
            hipGeometryHeightMeters = json.optDouble("hip_geometry_height_m", Double.NaN).toFloat(),
            torsoHeightMeters = json.optDouble("torso_height_m", Double.NaN).toFloat(),
            torsoResidualMeters = json.optDouble("torso_residual_m", Double.NaN).toFloat(),
            groundedFootDistanceMeters = json.optDouble("grounded_foot_distance_m", Double.NaN).toFloat(),
            footContactState = json.optString("foot_contact_state", ""),
            boneLengthSpreadMeters = json.optDouble("bone_length_spread_m", Double.NaN).toFloat(),
            bodyScaleConfidence = json.optDouble("body_scale_confidence", Double.NaN).toFloat(),
            distanceConfidence = json.optDouble("distance_confidence", Double.NaN).toFloat(),
            heightConfidence = json.optDouble("height_confidence", Double.NaN).toFloat(),
            floorConfidence = json.optDouble("floor_confidence", Double.NaN).toFloat(),
            distanceState = json.optString("distance_state", ""),
            weightHip = json.optDouble("w_hip", Double.NaN).toFloat(),
            weightHead = json.optDouble("w_head", Double.NaN).toFloat(),
            weightFoot = json.optDouble("w_foot", Double.NaN).toFloat(),
            weightTorso = json.optDouble("w_torso", Double.NaN).toFloat(),
            weightBone = json.optDouble("w_bone", Double.NaN).toFloat(),
            weightDepth = json.optDouble("w_depth", Double.NaN).toFloat(),
            weightRoi = json.optDouble("w_roi", Double.NaN).toFloat(),
            weightTemporal = json.optDouble("w_temporal", Double.NaN).toFloat(),
            activeFactors = json.optString("active_factors", ""),
        )
        if (metrics.source == "arcore_floor") {
            val smoothed = smoothTechnicalSceneMetrics(metrics)
            latestSceneMetrics = smoothed
            latestTechnicalSceneMetrics = smoothed
            technicalSceneMissingFrames = 0
        } else if (latestTechnicalSceneMetrics == null) {
            latestSceneMetrics = metrics
        }
    }

    private fun SceneMetricSnapshot.withManualSubjectHeightProfile(): SceneMetricSnapshot {
        val subjectHeight = _uiState.value.manualSubjectHeightMeters
        if (!subjectHeight.isFinite() || subjectHeight !in 1.05f..2.35f) return this
        return copy(
            profileSubjectHeightMeters = subjectHeight,
            profileSubjectHeightConfidence = 0.98f,
            profileSubjectHeightSource = "manual_profile",
        )
    }

    private fun ageTechnicalSceneMetrics(allowVisibleBodyHold: Boolean = false) {
        technicalSceneMissingFrames += 1
        val visibleJoints = _completedVis.count { it > 0.5f }
        val arTracking = latestWorldTracking?.trackingState == "tracking"
        val serverMetricUsable = latestServerPoseDebug?.hasV2MetricAuthority() == true ||
            serverPoseStableFrames >= MIN_STABLE_SERVER_FRAMES
        val canHoldVisibleScene = allowVisibleBodyHold &&
            latestTechnicalSceneMetrics != null &&
            visibleJoints >= MIN_VISIBLE_JOINTS_FOR_SCENE_HOLD &&
            (arTracking || serverMetricUsable)
        val maxMissingFrames = if (canHoldVisibleScene) {
            MAX_VISIBLE_BODY_SCENE_HOLD_FRAMES
        } else {
            MAX_TECHNICAL_SCENE_MISSING_FRAMES
        }
        if (technicalSceneMissingFrames > maxMissingFrames) {
            latestTechnicalSceneMetrics = null
        }
    }

    private fun smoothTechnicalSceneMetrics(raw: SceneMetricSnapshot): SceneMetricSnapshot {
        val previous = latestTechnicalSceneMetrics
        if (previous?.source != "arcore_floor") return raw

        fun smoothValue(prev: Float, next: Float, alpha: Float, maxStep: Float): Float {
            if (!prev.isFinite()) return next
            if (!next.isFinite()) return prev
            val delta = (next - prev).coerceIn(-maxStep, maxStep)
            return prev + delta * alpha
        }
        fun smoothFiniteOrNaN(prev: Float, next: Float, alpha: Float, maxStep: Float): Float {
            if (!next.isFinite()) return Float.NaN
            if (!prev.isFinite()) return next
            val delta = (next - prev).coerceIn(-maxStep, maxStep)
            return prev + delta * alpha
        }
        val rawHeightTrusted =
            raw.heightLockState == "locked" ||
                (raw.heightLockState.startsWith("holding") && "untrusted" !in raw.heightLockState) ||
                raw.heightLockState == "recalibrating_untrusted"

        val distanceJump =
            if (previous.distanceMeters.isFinite() && raw.distanceMeters.isFinite()) {
                kotlin.math.abs(raw.distanceMeters - previous.distanceMeters)
            } else {
                0f
            }
        val heightJump =
            if (previous.bodyHeightMeters.isFinite() && raw.bodyHeightMeters.isFinite()) {
                kotlin.math.abs(raw.bodyHeightMeters - previous.bodyHeightMeters)
            } else {
                0f
            }
        val cameraHeightJump =
            if (previous.cameraHeightMeters.isFinite() && raw.cameraHeightMeters.isFinite()) {
                kotlin.math.abs(raw.cameraHeightMeters - previous.cameraHeightMeters)
            } else {
                0f
            }
        val holdHeight =
            distanceJump > 0.20f &&
                heightJump > 0.06f &&
                cameraHeightJump < 0.05f

        val smoothedDistance = smoothValue(previous.distanceMeters, raw.distanceMeters, alpha = 0.20f, maxStep = 0.30f)
        val smoothedHeight = if (rawHeightTrusted) {
            smoothFiniteOrNaN(
                previous.bodyHeightMeters,
                raw.bodyHeightMeters,
                alpha = if (holdHeight) 0.05f else 0.12f,
                maxStep = if (holdHeight) 0.03f else 0.09f,
            )
        } else {
            raw.bodyHeightMeters
        }
        val smoothedCameraHeight = smoothValue(previous.cameraHeightMeters, raw.cameraHeightMeters, alpha = 0.18f, maxStep = 0.10f)

        return raw.copy(
            confidence = (previous.confidence * 0.62f + raw.confidence * 0.38f).coerceIn(0f, 1f),
            distanceMeters = smoothedDistance,
            bodyHeightMeters = smoothedHeight,
            cameraHeightMeters = smoothedCameraHeight,
            floorPitchDegrees = smoothValue(previous.floorPitchDegrees, raw.floorPitchDegrees, alpha = 0.16f, maxStep = 3.0f),
            lateralOffsetMeters = smoothValue(previous.lateralOffsetMeters, raw.lateralOffsetMeters, alpha = 0.22f, maxStep = 0.18f),
            correctedDistanceMeters = smoothedDistance,
            // correctedHeightMeters is a diagnostic value; the trust gate that
            // controls export-as-constraint lives in heightLockState. Surface
            // the optimizer's best estimate so the field is informative even
            // while the lock is still acquiring.
            correctedHeightMeters = raw.correctedHeightMeters.takeIf { it.isFinite() } ?: Float.NaN,
            correctedCameraHeightMeters = smoothedCameraHeight,
        )
    }

    private fun resetClientTrackingState() {
        _hasSmoothedLandmarks = false
        _smoothedVis.fill(0f)
        _kalman.forEach { it.reset() }
        _boneConstraints.reset()
        _landmarkFallback.reset()
        _hasLastGoodRelativeZ.fill(false)
        _hasLastReliable2D.fill(false)
        _lowConfidenceFrames.fill(0)
        learnedHipVectorXNorm = Float.NaN
        learnedHipVectorYNorm = Float.NaN
        physicalSceneGraph.resetRuntimeState()
    }

    private fun loadPhysicalSceneBias(): PhysicalSceneBias =
        PhysicalSceneBias(
            floorHeightBiasMeters = sceneBiasPrefs.getFloat("floor_height_bias_m", 0f),
            depthScale = sceneBiasPrefs.getFloat("depth_scale", 1f),
            depthOffsetMeters = sceneBiasPrefs.getFloat("depth_offset_m", 0f),
            heightEndpointBiasMeters = sceneBiasPrefs.getFloat("height_endpoint_bias_m", 0f),
        )

    private fun loadManualMetricProfile() {
        val cameraHeight = sceneBiasPrefs
            .getFloat(PREF_MANUAL_CAMERA_HEIGHT_M, _uiState.value.manualCameraHeightMeters)
            .takeIf { it.isFinite() && it in 0.20f..2.50f }
            ?: _uiState.value.manualCameraHeightMeters
        val subjectEnabled = sceneBiasPrefs.getBoolean(PREF_SUBJECT_HEIGHT_ENABLED, false)
        val subjectHeight = sceneBiasPrefs
            .getFloat(PREF_SUBJECT_HEIGHT_M, Float.NaN)
            .takeIf { subjectEnabled && it.isFinite() && it in 1.05f..2.35f }
            ?: Float.NaN
        _uiState.update {
            it.copy(
                manualCameraHeightMeters = cameraHeight,
                manualSubjectHeightMeters = subjectHeight,
            )
        }
    }

    private fun maybePersistPhysicalSceneBias() {
        sceneBiasSaveCountdown += 1
        if (sceneBiasSaveCountdown < 30) return
        sceneBiasSaveCountdown = 0
        val bias = physicalSceneGraph.currentBias
        sceneBiasPrefs.edit()
            .putFloat("floor_height_bias_m", bias.floorHeightBiasMeters)
            .putFloat("depth_scale", bias.depthScale)
            .putFloat("depth_offset_m", bias.depthOffsetMeters)
            .putFloat("height_endpoint_bias_m", bias.heightEndpointBiasMeters)
            .apply()
    }

    fun toggleCaptureRecording() {
        if (isCaptureRecording) {
            stopCaptureRecording()
        } else {
            startCaptureRecording()
        }
    }

    fun startCaptureRecording() {
        physicalSceneGraph.resetRuntimeState()
        latestSceneMetrics = null
        latestTechnicalSceneMetrics = null
        technicalSceneMissingFrames = 0
        sceneHoldFrames = 0
        val dir = captureRecorder.start()
        isCaptureRecording = true
        activeCaptureFolderName = dir.name
        Log.i(TAG, "Capture recording started: ${dir.absolutePath}")
    }

    fun stopCaptureRecording() {
        val dir = captureRecorder.stop()
        isCaptureRecording = false
        activeCaptureFolderName = dir?.name
        Log.i(TAG, "Capture recording stopped: ${dir?.absolutePath}")
    }

    private fun recordCaptureFrameIfNeeded() {
        if (!captureRecorder.isRecording) return
        val hasServerPose = serverPoseX != null && serverPoseY != null && serverPoseZ != null
        val serverHealth = evaluateServerPoseHealth(
            poseX = serverPoseX,
            poseY = serverPoseY,
            poseZ = serverPoseZ,
            poseVisibility = serverPoseConf,
        )
        val serverDebug = latestServerPoseDebug
        val technicalSceneMetrics = latestTechnicalSceneMetrics
        val canonicalJointsReady = serverDebug?.let {
            it.hasCanonicalMetricPose() &&
                it.poseJointsFrame == "display_floor_metric_v1" &&
                it.poseJointsNormalized.isFinite() &&
                kotlin.math.abs(it.poseJointsNormalized - 1f) <= 1e-3f
        } == true
        val useServerPose = hasServerPose &&
            serverHealth.usable &&
            serverMetricPoseDisplayReady
        val serverPoseIsStaleForRecording =
            lastPose3DAgeMs?.let { it > 85L } == true
        val allowClientTechnicalFallback = !serverMetricPoseExpected
        val clientTechnicalPose = if (useServerPose || !allowClientTechnicalFallback) {
            null
        } else {
            latestClientTechnicalPose()
        }
        val techX = if (useServerPose) serverPoseX else clientTechnicalPose?.first
        val techY = if (useServerPose) serverPoseY else clientTechnicalPose?.second
        val techZ = if (useServerPose) serverPoseZ else clientTechnicalPose?.third
        val technicalSource = when {
            useServerPose -> if (
                serverDebug?.metricPoseStatus == "hold_previous" ||
                (!canonicalJointsReady && heldCanonicalServerPoseFrames > 0) ||
                serverPoseIsStaleForRecording
            ) {
                "server_metric_canonical_held"
            } else if (serverMetricClientMotionOverlayActive) {
                "server_metric_client_motion"
            } else {
                "server_metric_canonical"
            }
            clientTechnicalPose != null -> "arcore_client_33pt"
            else -> "none"
        }
        val missingReason = classifyServerPoseMissingReason(
            pose3DReceivedCount = pose3DReceivedCount,
            lastServerParseReason = lastServerMissingReason,
            serverHealth = serverHealth,
        )
        val scaleApplied = serverDebug?.scaleApplied
        val scaleEffective = scaleApplied != null && scaleApplied.isFinite() &&
            kotlin.math.abs(scaleApplied - 1f) > 1e-3f
        val rootShift = serverDebug?.rootTranslationMeters
        val rootShiftEffective = rootShift != null && rootShift.isFinite() && rootShift > 0.02f
        val serverConstraintConfidence = serverDebug?.constraintConfidence?.takeIf { it.isFinite() }
        val serverConstraintReason = serverDebug?.correctionReason.orEmpty()
        val serverConstraintActive = serverConstraintConfidence != null &&
            (
                scaleEffective ||
                    rootShiftEffective ||
                    serverDebug?.hasV2MetricAuthority() == true ||
                    serverConstraintReason == "height_and_root_constrained" ||
                    serverConstraintReason == "root_constrained"
            )
        captureRecorder.recordFrame(
            uiState = _uiState.value,
            visibleLandmarkCount = _completedVis.count { it > 0.5f },
            sceneMetrics = technicalSceneMetrics,
            worldTracking = latestWorldTracking,
            skeletonX = _completedX,
            skeletonY = _completedY,
            skeletonZ = poseLandmarksZ,
            skeletonVisibility = _completedVis,
            technicalX = techX,
            technicalY = techY,
            technicalZ = techZ,
            technicalConfidence = if (useServerPose) serverPoseConf else _completedVis,
            technicalSource = technicalSource,
            serverStableFrames = serverPoseStableFrames,
            acceptedPoseSource = technicalSource,
            serverPoseStatus = when {
                useServerPose -> serverDebug?.poseStatus ?: "ok"
                hasServerPose && !serverMetricPoseDisplayReady -> "rejected"
                serverHealth.usable -> "ok"
                else -> "missing_server_pose"
            },
            serverCorrectionReason = serverDebug?.correctionReason,
            rejectedServerReason = if (useServerPose) "none" else missingReason,
            serverTransport = lastServerTransport,
            framesSentToServer = framesSentToServer,
            pose3DReceivedCount = pose3DReceivedCount,
            lastPose3DAgeMs = lastPose3DAgeMs,
            lastServerJointsCount = lastServerJointsCount,
            serverMissingReason = missingReason,
            rawServerHeightMeters = serverDebug?.rawHeightMeters?.takeIf { serverConstraintActive },
            rawServerDistanceMeters = serverDebug?.rawDistanceMeters?.takeIf { serverConstraintActive },
            preSkeletonConstrainedHeightMeters = serverDebug?.preSkeletonConstrainedHeightMeters?.takeIf {
                serverConstraintActive
            },
            preSkeletonConstrainedDistanceMeters = serverDebug?.preSkeletonConstrainedDistanceMeters?.takeIf {
                serverConstraintActive
            },
            constrainedServerHeightMeters = serverDebug?.constrainedHeightMeters?.takeIf { serverConstraintActive },
            constrainedServerDistanceMeters = serverDebug?.constrainedDistanceMeters?.takeIf { serverConstraintActive },
            arTargetHeightMeters = serverDebug?.arTargetHeightMeters ?: technicalSceneMetrics?.bodyHeightMeters,
            arTargetDistanceMeters = serverDebug?.arTargetDistanceMeters ?: technicalSceneMetrics?.distanceMeters,
            serverScaleApplied = serverDebug?.scaleApplied,
            serverRootShiftMeters = serverDebug?.rootTranslationMeters,
            constraintConfidence = serverConstraintConfidence,
            mlVisualUsable = serverDebug?.mlVisualUsable,
            mlDltMetricBad = serverDebug?.mlDltMetricBad,
            mlHeightTargetSource = serverDebug?.mlHeightTargetSource,
            mlDistanceTargetSource = serverDebug?.mlDistanceTargetSource,
            mlDistanceHoldActive = serverDebug?.mlDistanceHoldActive,
            mlDltWeightScale = serverDebug?.mlDltWeightScale,
            heightTargetWeight = serverDebug?.heightTargetWeight,
            heightPriorWeight = serverDebug?.heightPriorWeight,
            heightSmoothWeight = serverDebug?.heightSmoothWeight,
            heightTargetAdmission = serverDebug?.heightTargetAdmission,
            heightTargetAdmissionReason = serverDebug?.heightTargetAdmissionReason,
            heightGeometrySuspicious = serverDebug?.heightGeometrySuspicious,
            heightWitnessCount = serverDebug?.heightWitnessCount,
            heightWitnessMedianMeters = serverDebug?.heightWitnessMedianMeters,
            heightWitnessSpreadMeters = serverDebug?.heightWitnessSpreadMeters,
            heightCorrectedVsTopDeltaMeters = serverDebug?.heightCorrectedVsTopDeltaMeters,
            heightCorrectedVsHipDeltaMeters = serverDebug?.heightCorrectedVsHipDeltaMeters,
            heightCorrectedVsPixelDeltaMeters = serverDebug?.heightCorrectedVsPixelDeltaMeters,
            heightCorrectedVsTorsoDeltaMeters = serverDebug?.heightCorrectedVsTorsoDeltaMeters,
            heightTargetBeforeGateMeters = serverDebug?.heightTargetBeforeGateMeters,
            heightTargetAfterGateMeters = serverDebug?.heightTargetAfterGateMeters,
            heightMemoryUpdate = serverDebug?.heightMemoryUpdate,
            heightMemoryReason = serverDebug?.heightMemoryReason,
            heightMemoryAgeFrames = serverDebug?.heightMemoryAgeFrames,
            heightMemoryTargetMeters = serverDebug?.heightMemoryTargetMeters,
            wlsHeightMeters = serverDebug?.wlsHeightMeters,
            postStableSkeletonHeightMeters = serverDebug?.postStableSkeletonHeightMeters,
            finalSmoothedHeightMeters = serverDebug?.finalSmoothedHeightMeters,
            heightLossStage = serverDebug?.heightLossStage,
            distanceTargetWeight = serverDebug?.distanceTargetWeight,
            distancePriorWeight = serverDebug?.distancePriorWeight,
            distanceSmoothWeight = serverDebug?.distanceSmoothWeight,
            distanceLocalAuthority = serverDebug?.distanceLocalAuthority,
            distanceTargetAdmission = serverDebug?.distanceTargetAdmission,
            distanceTargetAdmissionReason = serverDebug?.distanceTargetAdmissionReason,
            distanceGeometrySuspicious = serverDebug?.distanceGeometrySuspicious,
            distanceWitnessCount = serverDebug?.distanceWitnessCount,
            distanceWitnessMedianMeters = serverDebug?.distanceWitnessMedianMeters,
            distanceWitnessSpreadMeters = serverDebug?.distanceWitnessSpreadMeters,
            distanceVsPreviousTargetDeltaMeters = serverDebug?.distanceVsPreviousTargetDeltaMeters,
            distanceFootRoiDisagreementMeters = serverDebug?.distanceFootRoiDisagreementMeters,
            distanceFootRelativeDisagreementMeters = serverDebug?.distanceFootRelativeDisagreementMeters,
            distanceCorrectedVsFootDeltaMeters = serverDebug?.distanceCorrectedVsFootDeltaMeters,
            distanceTargetBeforeGateMeters = serverDebug?.distanceTargetBeforeGateMeters,
            distanceTargetAfterGateMeters = serverDebug?.distanceTargetAfterGateMeters,
            distanceMemoryUpdate = serverDebug?.distanceMemoryUpdate,
            distanceMemoryReason = serverDebug?.distanceMemoryReason,
            distanceMemoryAgeFrames = serverDebug?.distanceMemoryAgeFrames,
            distanceMemoryTargetMeters = serverDebug?.distanceMemoryTargetMeters,
            factorGraphActive = serverDebug?.factorGraphActive,
            factorGraphStatus = serverDebug?.factorGraphStatus,
            factorGraphCostBefore = serverDebug?.factorGraphCostBefore,
            factorGraphCostAfter = serverDebug?.factorGraphCostAfter,
            factorGraphScaleDelta = serverDebug?.factorGraphScaleDelta,
            factorGraphYawDegrees = serverDebug?.factorGraphYawDegrees,
            factorGraphRootDxMeters = serverDebug?.factorGraphRootDxMeters,
            factorGraphRootDzMeters = serverDebug?.factorGraphRootDzMeters,
            factorGraphLeftFootDzMeters = serverDebug?.factorGraphLeftFootDzMeters,
            factorGraphRightFootDzMeters = serverDebug?.factorGraphRightFootDzMeters,
            factorGraphTargetDistanceMeters = serverDebug?.factorGraphTargetDistanceMeters,
            factorGraphTargetHeightMeters = serverDebug?.factorGraphTargetHeightMeters,
            factorGraphFactorSummary = serverDebug?.factorGraphFactorSummary,
            metricPoseStatus = serverDebug?.metricPoseStatus,
            metricPoseRejectReason = serverDebug?.metricPoseRejectReason,
            metricPoseFrame = serverDebug?.metricPoseFrame,
            metricRootXMeters = serverDebug?.metricRootXMeters,
            metricRootYMeters = serverDebug?.metricRootYMeters,
            metricRootZMeters = serverDebug?.metricRootZMeters,
            metricRootDistanceMeters = serverDebug?.metricRootDistanceMeters,
            metricFootMidpointXMeters = serverDebug?.metricFootMidpointXMeters,
            metricFootMidpointZMeters = serverDebug?.metricFootMidpointZMeters,
            metricBodyHeightMeters = serverDebug?.metricBodyHeightMeters,
            metricBodyScaleLocked = serverDebug?.metricBodyScaleLocked,
            metricBoneScaleSource = serverDebug?.metricBoneScaleSource,
            metricPoseJitterScaleMeters = serverDebug?.metricPoseJitterScaleMeters,
            metricPoseJitterRootMeters = serverDebug?.metricPoseJitterRootMeters,
            poseJointsFrame = serverDebug?.poseJointsFrame,
            poseJointsNormalized = serverDebug?.poseJointsNormalized,
            poseJointsNormalizationScale = serverDebug?.poseJointsNormalizationScale,
            poseLifterStatus = serverDebug?.poseLifterStatus,
            poseLifterModelPath = serverDebug?.poseLifterModelPath,
            poseLifterHiddenJointCount = serverDebug?.poseLifterHiddenJointCount,
            poseLifterMeanConfidence = serverDebug?.poseLifterMeanConfidence,
            poseLifterAppliedJointCount = serverDebug?.poseLifterAppliedJointCount,
            poseLifterRejectReason = serverDebug?.poseLifterRejectReason,
            clientMotionOverlayActive = serverMetricClientMotionOverlayActive,
            stableSkeletonLearningEnabled = serverDebug?.stableSkeletonLearningEnabled,
            stableSkeletonResetReason = serverDebug?.stableSkeletonResetReason,
            mlEvidenceStatus = serverDebug?.mlEvidenceStatus,
            mlEvidenceHeightSigmaMeters = serverDebug?.mlEvidenceHeightSigmaMeters,
            mlEvidenceDistanceSigmaMeters = serverDebug?.mlEvidenceDistanceSigmaMeters,
            mlEvidenceMaskEndpointConfidence = serverDebug?.mlEvidenceMaskEndpointConfidence,
            mlEvidenceVisibleBodyFraction = serverDebug?.mlEvidenceVisibleBodyFraction,
            mlEvidenceFootContactProbability = serverDebug?.mlEvidenceFootContactProbability,
            mlEvidenceImageStatus = serverDebug?.mlEvidenceImageStatus,
            mlEvidenceDebug = serverDebug?.mlEvidenceDebug,
            mlImageWidthPx = serverDebug?.mlImageWidthPx,
            mlImageHeightPx = serverDebug?.mlImageHeightPx,
            mlImageSourceWidthPx = serverDebug?.mlImageSourceWidthPx,
            mlImageSourceHeightPx = serverDebug?.mlImageSourceHeightPx,
            mlImageCropLeftPx = serverDebug?.mlImageCropLeftPx,
            mlImageCropTopPx = serverDebug?.mlImageCropTopPx,
            mlImageCropWidthPx = serverDebug?.mlImageCropWidthPx,
            mlImageCropHeightPx = serverDebug?.mlImageCropHeightPx,
            mlImageJpegQuality = serverDebug?.mlImageJpegQuality,
            mlImageCropPadRatio = serverDebug?.mlImageCropPadRatio,
            mlEvidenceSchema = serverDebug?.mlEvidenceSchema,
            mlEvidenceOutputs = serverDebug?.mlEvidenceOutputs,
            serverSceneMetricsReceived = serverDebug?.serverSceneMetricsReceived,
            serverSceneMetricsAccepted = serverDebug?.serverSceneMetricsAccepted,
            serverSceneMetricsSource = serverDebug?.serverSceneMetricsSource,
            serverSceneMetricsFloorSource = serverDebug?.serverSceneMetricsFloorSource,
            serverSceneMetricsFilterReason = serverDebug?.serverSceneMetricsFilterReason,
            serverSceneMetricsConfidence = serverDebug?.serverSceneMetricsConfidence,
        )
    }

    private fun latestClientTechnicalPose(): Triple<FloatArray, FloatArray, FloatArray>? {
        val x = clientTechnicalPoseX ?: return null
        val y = clientTechnicalPoseY ?: return null
        val z = clientTechnicalPoseZ ?: return null
        if (x.size < 33 || y.size < 33 || z.size < 33) return null
        return Triple(x.copyOf(), y.copyOf(), z.copyOf())
    }

    private fun updateClientTechnicalPose() {
        val rawPose = buildRawClientTechnicalPose() ?: run {
            clearClientTechnicalPoseArrays()
            return
        }
        val x = rawPose.first.copyOf()
        val y = rawPose.second.copyOf()
        val z = rawPose.third.copyOf()
        stabilizeClientTechnicalTorsoFrame(x, y, z, _completedVis, learn = true)
        stabilizeClientTechnicalArmBones(x, y, z, _completedVis, learn = true)

        if (!_hasClientTechnicalPose) {
            for (i in 0 until 33) {
                _clientTechnicalSmoothX[i] = x[i]
                _clientTechnicalSmoothY[i] = y[i]
                _clientTechnicalSmoothZ[i] = z[i]
            }
            _hasClientTechnicalPose = true
        } else {
            for (i in 0 until 33) {
                if (_completedVis.getOrNull(i) ?: 0f < 0.16f) continue
                if (!x[i].isFinite() || !y[i].isFinite() || !z[i].isFinite()) continue
                val dxRaw = x[i] - _clientTechnicalSmoothX[i]
                val dyRaw = y[i] - _clientTechnicalSmoothY[i]
                val dzRaw = z[i] - _clientTechnicalSmoothZ[i]
                val motion = sqrt(dxRaw * dxRaw + dyRaw * dyRaw + dzRaw * dzRaw)
                val alpha = clientTechnicalSmoothingAlpha(i, motion, _completedVis[i])
                var dx = dxRaw * alpha
                var dy = dyRaw * alpha
                var dz = dzRaw * alpha
                val step = sqrt(dx * dx + dy * dy + dz * dz)
                val maxStep = clientTechnicalMaxStepMeters(i)
                if (step > maxStep && step > 1e-5f) {
                    val scale = maxStep / step
                    dx *= scale
                    dy *= scale
                    dz *= scale
                }
                _clientTechnicalSmoothX[i] += dx
                _clientTechnicalSmoothY[i] += dy
                _clientTechnicalSmoothZ[i] += dz
            }
        }

        val outX = _clientTechnicalSmoothX.copyOf()
        val outY = _clientTechnicalSmoothY.copyOf()
        val outZ = _clientTechnicalSmoothZ.copyOf()
        stabilizeClientTechnicalTorsoFrame(outX, outY, outZ, _completedVis, learn = false)
        stabilizeClientTechnicalArmBones(outX, outY, outZ, _completedVis, learn = false)
        for (i in 0 until 33) {
            _clientTechnicalSmoothX[i] = outX[i]
            _clientTechnicalSmoothY[i] = outY[i]
            _clientTechnicalSmoothZ[i] = outZ[i]
        }
        clientTechnicalPoseX = outX
        clientTechnicalPoseY = outY
        clientTechnicalPoseZ = outZ
        clientTechnicalPoseConf = _completedVis.copyOf()
    }

    private fun buildRawClientTechnicalPose(): Triple<FloatArray, FloatArray, FloatArray>? {
        val scene = latestTechnicalSceneMetrics ?: return null
        val distanceMeters = scene.correctedDistanceMeters
            .takeIf { it.isFinite() && it in 0.35f..12f }
            ?: scene.distanceMeters.takeIf { it.isFinite() && it in 0.35f..12f }
            ?: return null
        val bodyHeightMeters = scene.correctedHeightMeters
            .takeIf { it.isFinite() && it in 1.05f..2.35f }
            ?: scene.bodyHeightMeters.takeIf { it.isFinite() && it in 1.05f..2.35f }
            ?: return null

        val verticalRange = clientBodyVerticalRangeNorm() ?: return null
        val bodyHeightNorm = (verticalRange.second - verticalRange.first).coerceAtLeast(0.25f)
        val metersPerNorm = (bodyHeightMeters / bodyHeightNorm).coerceIn(1.2f, 5.8f)
        val hipCenterX = (_completedX[23] + _completedX[24]) * 0.5f
        val floorNormY = verticalRange.second

        val outX = FloatArray(33)
        val outY = FloatArray(33)
        val outZ = FloatArray(33)
        val z = poseLandmarksZ
        for (i in 0 until 33) {
            outX[i] = scene.lateralOffsetMeters + (_completedX[i] - hipCenterX) * metersPerNorm
            outY[i] = (floorNormY - _completedY[i]) * metersPerNorm
            outZ[i] = -distanceMeters + (z?.getOrNull(i) ?: 0f) * metersPerNorm * 0.35f
        }
        return Triple(outX, outY, outZ)
    }

    private fun clientTechnicalSmoothingAlpha(index: Int, motionMeters: Float, visibility: Float): Float {
        val confidenceScale = when {
            visibility >= 0.82f -> 1.0f
            visibility >= 0.55f -> 0.74f
            visibility >= 0.32f -> 0.46f
            else -> 0.25f
        }
        val params = when (index) {
            15, 16, 17, 18, 19, 20, 21, 22 -> floatArrayOf(0.22f, 0.34f, 0.24f, 0.56f)
            13, 14 -> floatArrayOf(0.20f, 0.30f, 0.22f, 0.50f)
            11, 12 -> floatArrayOf(0.11f, 0.14f, 0.14f, 0.25f)
            23, 24, 25, 26, 27, 28, 29, 30, 31, 32 -> floatArrayOf(0.13f, 0.18f, 0.16f, 0.31f)
            else -> floatArrayOf(0.18f, 0.28f, 0.18f, 0.44f)
        }
        val base = params[0]
        val boost = params[1]
        val denom = params[2]
        val maxAlpha = params[3]
        val motionBoost = (motionMeters / denom).coerceIn(0f, 1f) * boost
        return ((base + motionBoost) * confidenceScale).coerceIn(0.08f, maxAlpha)
    }

    private fun clientTechnicalMaxStepMeters(index: Int): Float = when (index) {
        17, 18, 19, 20, 21, 22 -> 0.085f
        15, 16 -> 0.075f
        13, 14 -> 0.060f
        11, 12 -> 0.030f
        23, 24 -> 0.030f
        25, 26, 27, 28, 29, 30, 31, 32 -> 0.055f
        else -> 0.080f
    }

    private fun stabilizeClientTechnicalTorsoFrame(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        visibility: FloatArray,
        learn: Boolean,
    ) {
        fun symmetricSegment(slot: Int, a: Int, b: Int, minMeters: Float, maxMeters: Float, blend: Float) {
