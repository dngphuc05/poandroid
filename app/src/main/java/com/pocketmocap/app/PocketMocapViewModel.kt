package com.pocketmocap.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import kotlin.math.min
import kotlin.math.sqrt
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketmocap.app.network.LandmarkData
import com.pocketmocap.app.network.MocapServerClient
import com.pocketmocap.app.network.ServerLinkParser
import com.pocketmocap.app.calibration.CloudAnchorEngine
import com.pocketmocap.app.calibration.CloudAnchorResult
import com.pocketmocap.app.pipeline.BoneConstraintEngine
import com.pocketmocap.app.pipeline.HybridPosePipeline
import com.pocketmocap.app.pipeline.LandmarkFallbackEngine
import com.pocketmocap.app.pipeline.LandmarkKalman2D
import com.pocketmocap.app.pipeline.ObservedJointDisplayFilter
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
        // Joints moving faster than this per frame are likely MediaPipe glitches (outlier gate)
        private const val MAX_JOINT_DELTA = 0.15f  // ~48px at 320w
        // Below this visibility a joint is treated as fully occluded
        private const val VIS_OCCLUDE = 0.20f
        // Below this visibility a joint is uncertain (conservative EMA)
        private const val VIS_UNCERTAIN = 0.50f
        // Predicted hidden joints stay visibly marked on the phone overlay without
        // raising the confidence sent to the server reconstruction pipeline.
        private const val DISPLAY_PREDICTED_VIS = 0.34f
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

    // Phone capture-node UI state.
    data class UiState(
        val serverUrl: String = PhoneConnectionDefaults.INITIAL_SERVER_URL,
        val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
        val sessionId: String = "",
        val lobbyCodeInput: String = "",
        val joinedLobbyCode: String = "",
        val joinedLobbyName: String = "",
        val joinedLobbyPreset: String = "single_live",
        val lobbyJoinState: LobbyJoinState = LobbyJoinState.IDLE,
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
        val anchorRole: String = "",
        val sharedAnchorId: String = "",
        val anchorState: String = "not_started",
        val calibrationGate: String = "",
    )

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
    enum class LobbyJoinState { IDLE, JOINING, JOINED }
    enum class CalibrationStep { PENDING, INTRINSIC_CALC, EXTRINSIC_ANCHOR, SYNC_WAIT, BOOTSTRAP, COMPLETE, FAILED }

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
    var isScreenEvidenceRecording by mutableStateOf(false)
        private set
    var screenEvidenceRecordingStatus by mutableStateOf("REC off")
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
    private val sceneBiasPrefs by lazy {
        getApplication<Application>().getSharedPreferences(PHYSICAL_SCENE_BIAS_PREFS, Context.MODE_PRIVATE)
    }
    private val physicalSceneGraph by lazy { PhysicalSceneFactorGraph(loadPhysicalSceneBias()) }
    private var sceneBiasSaveCountdown = 0
    private var lastPose3DReceivedAtMs = 0L
    private var serverMetricClientMotionOverlayActive = false
    private var lastCanonicalAuthoritativeHeightMeters = Float.NaN
    private var lastCanonicalAuthoritativeDistanceMeters = Float.NaN
    // Lobby extrinsic reporting — throttle the PC-facing extrinsic_update stream to ~2 Hz.
    private var extrinsicEmitCountdown = 0
    private var deviceReadyReported = false
    private var anchorHostedReported = false
    private var anchorResolvedReported = false
    private var pendingAnchorIdToResolve = ""
    private var cloudAnchorEngine: CloudAnchorEngine? = null

    init {
        loadManualMetricProfile()
    }

    val hasStableServerPose: Boolean
        get() = serverPoseStableFrames >= MIN_STABLE_SERVER_FRAMES &&
            serverPoseX != null &&
            serverPoseY != null &&
            serverPoseZ != null

    /**
     * Direct-draw callback fired from the analysis thread with the completed
     * 33-point MediaPipe pose prepared for display.
     * Bypasses Compose recomposition and Vsync coalescing — set by CaptureScreen,
     * cleared on dispose. Signature: (xNorm, yNorm, visibility, imageWidth, imageHeight)
     */
    var directLandmarkCallback: ((FloatArray, FloatArray, FloatArray, Int, Int) -> Unit)? = null

    // ── EMA + Kalman hybrid landmark stabilization ──
    // Visible joints (vis≥0.50): outlier-gated EMA, α=0.55–0.92; Kalman tracks velocity.
    // Uncertain joints (0.20≤vis<0.50): conservative EMA, outlier rejection.
    // Occluded joints (vis<0.20): Kalman predicts forward, then geometric fallback
    // rebuilds a usable 33-point pose before UI/server output.
    private val _kalman = Array(33) { LandmarkKalman2D(fps = 30f) }
    private val _smoothedX = FloatArray(33)
    private val _smoothedY = FloatArray(33)
    private val _completedX = FloatArray(33)
    private val _completedY = FloatArray(33)
    private val _completedVis = FloatArray(33)
    private val _displayFullVis = FloatArray(33)
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
    private val _observedDisplayFilter = ObservedJointDisplayFilter()
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
        // Caps are applied after EMA — they must be high enough for fast motion
        // (~6 m/s arm swing = 0.20 m/frame at 30 fps) while still blocking
        // reconstruction error teleports (> 0.5 m jumps).
        if (turnFastUpdateActive) {
            return when (index) {
                17, 18, 19, 20, 21, 22 -> 0.30f
                15, 16 -> 0.28f
                13, 14 -> 0.25f
                11, 12, 23, 24 -> 0.18f
                25, 26, 27, 28, 29, 30, 31, 32 -> 0.22f
                else -> 0.20f
            }
        }
        val normal = when (index) {
            17, 18, 19, 20, 21, 22 -> 0.22f
            15, 16 -> 0.22f
            13, 14 -> 0.20f
            11, 12, 23, 24 -> 0.12f
            25, 26, 27, 28, 29, 30, 31, 32 -> 0.16f
            else -> 0.12f
        }
        if (!recoveringFromStalePose) return normal
        // Recovering from stale: ease in slowly to hide reconstruction snaps.
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
    private val serverClient: MocapServerClient = MocapServerClient(getApplication(), object : MocapServerClient.Listener {
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
                joinedLobbyCode = "",
                joinedLobbyName = "",
                lobbyJoinState = LobbyJoinState.IDLE,
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

        override fun onLobbyJoined(code: String, name: String, preset: String) {
            val normalizedCode = code.filter(Char::isDigit).take(6)
            val normalizedPreset = preset.takeIf { it == "single_live" || it == "multi_live" } ?: "single_live"
            val isSingleCamera = normalizedPreset == "single_live"
            Log.w(TAG, "JOINED LOBBY PRESET => rawCode=$code normalizedCode=$normalizedCode name=$name preset=$normalizedPreset")
            if (normalizedCode.length != 6) {
                _uiState.update { it.copy(
                    lobbyJoinState = LobbyJoinState.IDLE,
                    errorMessage = "PC returned an invalid session code",
                ) }
                return
            }
            deviceReadyReported = false
            anchorHostedReported = false
            anchorResolvedReported = false
            pendingAnchorIdToResolve = ""
            _uiState.update { it.copy(
                lobbyCodeInput = normalizedCode,
                joinedLobbyCode = normalizedCode,
                joinedLobbyName = name,
                joinedLobbyPreset = normalizedPreset,
                lobbyJoinState = LobbyJoinState.JOINED,
                calibrationStep = CalibrationStep.PENDING,
                bootstrapProgress = 0f,
                anchorRole = "",
                sharedAnchorId = "",
                anchorState = if (isSingleCamera) "not_used" else "not_started",
                calibrationGate = if (isSingleCamera) "single_camera" else "",
                errorMessage = null,
            )}
        }

        override fun onLobbyClosed(code: String) {
            Log.i(TAG, "onLobbyClosed: code=$code")
            deviceReadyReported = false
            autoResumeServerAfterReconnect = false
            clearServerPoseArrays()
            pipeline?.stop()
            _uiState.update { it.copy(
                joinedLobbyCode = "",
                joinedLobbyName = "",
                joinedLobbyPreset = "single_live",
                lobbyJoinState = LobbyJoinState.IDLE,
                calibrationStep = CalibrationStep.PENDING,
                bootstrapProgress = 0f,
                errorMessage = "The PC operator closed this session.",
            )}
        }

        override fun onAnchorStatus(lobby: JSONObject) {
            viewModelScope.launch {
                val lobbyEnvelope = JSONObject().put("lobby", lobby)
                val myDeviceId = deviceId()
                val devices = lobby.optJSONArray("devices")
                var role = ""
                var deviceAnchorState = lobby.optString("anchor_state", "not_started")
                for (i in 0 until (devices?.length() ?: 0)) {
                    val d = devices?.optJSONObject(i) ?: continue
                    if (d.optString("device_id") == myDeviceId) {
                        role = d.optString("anchor_role", "")
                        deviceAnchorState = d.optString("anchor_state", deviceAnchorState)
                    }
                }
                _uiState.update { state ->
                    val activePreset = com.pocketmocap.app.network.normalizeLobbyPreset(
                        lobbyEnvelope,
                        fallback = state.joinedLobbyPreset,
                    )
                    val gate = lobby.optString("calibration_gate", state.calibrationGate)
                    val nextStep = when {
                        activePreset != "multi_live" -> state.calibrationStep
                        gate == "ready" && state.calibrationStep == CalibrationStep.EXTRINSIC_ANCHOR -> CalibrationStep.BOOTSTRAP
                        gate == "ready" && state.calibrationStep == CalibrationStep.SYNC_WAIT -> CalibrationStep.BOOTSTRAP
                        gate == "anchor_quality_low" -> CalibrationStep.FAILED
                        else -> state.calibrationStep
                    }
                    state.copy(
                        joinedLobbyPreset = activePreset,
                        anchorRole = role,
                        sharedAnchorId = lobby.optString("shared_anchor_id", state.sharedAnchorId),
                        anchorState = deviceAnchorState,
                        calibrationGate = gate,
                        calibrationStep = nextStep,
                        errorMessage = if (gate == "anchor_quality_low") "Cloud Anchor quality is too low. Rescan the room and try again." else state.errorMessage,
                    )
                }
            }
            maybeRunCloudAnchorCalibration()
        }

        override fun onAnchorResolveRequest(sharedAnchorId: String) {
            if (_uiState.value.joinedLobbyPreset != "multi_live") {
                Log.i(TAG, "Ignoring Cloud Anchor resolve request for single-camera session")
                return
            }
            pendingAnchorIdToResolve = sharedAnchorId
            anchorResolvedReported = false
            _uiState.update { state ->
                state.copy(
                    sharedAnchorId = sharedAnchorId,
                    anchorRole = "resolver",
                    anchorState = "resolve_requested",
                )
            }
            maybeRunCloudAnchorCalibration()
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
            _uiState.update { it.copy(
                connectionState = ConnectionState.DISCONNECTED,
                joinedLobbyCode = "",
                joinedLobbyName = "",
                joinedLobbyPreset = "single_live",
                lobbyJoinState = LobbyJoinState.IDLE,
            ) }
        }

        override fun onCalibrationAck(success: Boolean, state: String) {
            Log.i(TAG, "onCalibrationAck: success=$success state=$state")
            if (success) {
                val isSingle = _uiState.value.joinedLobbyPreset == "single_live"
                val nextStep = if (isSingle) CalibrationStep.BOOTSTRAP else CalibrationStep.EXTRINSIC_ANCHOR
                _uiState.update { it.copy(calibrationStep = nextStep, errorMessage = null) }
                pipeline?.onCalibrationConfirmed()
                if (!isSingle) {
                    maybeRunCloudAnchorCalibration()
                }
            } else {
                _uiState.update { it.copy(calibrationStep = CalibrationStep.FAILED, errorMessage = "Calibration failed") }
            }
        }

        override fun onBootstrapProgress(collected: Int, target: Int, complete: Boolean) {
            Log.i(TAG, "onBootstrapProgress: $collected/$target complete=$complete")
            val progress = if (target > 0) collected.toFloat() / target else 0f
            _uiState.update { it.copy(bootstrapProgress = progress) }
            if (complete) {
                _uiState.update { it.copy(calibrationStep = CalibrationStep.COMPLETE, errorMessage = null) }
                pipeline?.onBootstrapComplete()
                reportDeviceReadyToLobby()
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
            _uiState.update { it.copy(
                lobbyJoinState = if (it.lobbyJoinState == LobbyJoinState.JOINING) {
                    LobbyJoinState.IDLE
                } else {
                    it.lobbyJoinState
                },
                errorMessage = message,
            ) }
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
                            val stabilizedZ = when {
                                rawConfidence >= VIS_OCCLUDE -> raw.z
                                _hasLastGoodRelativeZ[i] -> _lastGoodRelativeZ[i]
                                else -> raw.z
                            }

                            // Do NOT send the synthetically boosted visibility (_completedVis) to the server.
                            // The server's ML PoseLifter needs the true raw visibility to know which joints
                            // are actually hidden so it can accurately reconstruct them.
                            prepared.add(
                                raw.copy(
                                    x = sensorX.coerceIn(0f, 1f) * outboundWidth,
                                    y = sensorY.coerceIn(0f, 1f) * outboundHeight,
                                    z = stabilizedZ,
                                    visibility = raw.visibility,
                                    presence = raw.presence,
                                    confidence = raw.confidence,
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
                        val observedDisplayFrame = _observedDisplayFilter.update(xNorm, yNorm, visibility)
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
                                        // Soft-ramp alpha for fast movements to prevent hard snapping on noise
                                        val fastMotionBoost = maxOf(0f, dist - 0.04f) * 5.0f
                                        val baseAlpha = if (reappearing) {
                                            (0.58f + normSpeed * 0.30f).coerceIn(0.58f, 0.88f)
                                        } else {
                                            (0.18f + normSpeed * 0.67f).coerceIn(0.18f, 0.85f)
                                        }
                                        val alpha = minOf(1.0f, baseAlpha + fastMotionBoost)
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
                        val visible = observedDisplayFrame.visibility.count { it >= 0.28f }
                        for (i in 0 until 33) {
                            val hasPrediction = _completedX[i].isFinite() && _completedY[i].isFinite()
                            _displayFullVis[i] = when {
                                _completedVis[i] >= VIS_OCCLUDE -> _completedVis[i].coerceIn(0f, 1f)
                                hasPrediction -> DISPLAY_PREDICTED_VIS
                                else -> 0f
                            }
                        }
                        val displayX = observedDisplayFrame.x
                        val displayY = observedDisplayFrame.y
                        val displayVis = observedDisplayFrame.visibility
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                            directLandmarkCallback?.invoke(
                                displayX,
                                displayY,
                                displayVis,
                                imageWidth,
                                imageHeight,
                            )
                            // mutableStateOf writes for warning banner, joint count, server fallback
                            poseLandmarksX = displayX
                            poseLandmarksY = displayY
                            poseLandmarksZ = zWorld
                            worldLandmarksX = xWorld
                            worldLandmarksY = yWorld
                            worldLandmarksZ = zWorld
                            poseVisibility = displayVis
                            updateSceneMetrics(worldTracking, visualTopYNorm, visualTopConfidence)
                            maybeSendExtrinsicUpdate(worldTracking)
                            updateClientTechnicalPose()
                            updateServerMetricPoseWithClientMotion()
                            // Guard constant/rarely-changing values — avoids spurious Compose recompositions
                            if (visibleLandmarkCount != visible) visibleLandmarkCount = visible
                            if (cameraImageWidth != imageWidth) cameraImageWidth = imageWidth
                            if (cameraImageHeight != imageHeight) cameraImageHeight = imageHeight
                        }
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
                        _observedDisplayFilter.reset()
                        _displayFullVis.fill(0f)
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
                                isServerSceneMetricSource(it.source) &&
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

    /**
     * Report this phone's ARCore camera extrinsic to the PC lobby (~2 Hz) so the
     * operator can place the device in the multi-camera scene. Throttled to avoid
     * flooding the Socket.IO control channel; the heavy pose stream stays separate.
     */
    /** Tell the PC operator this phone has finished calibration and is ready to capture (once). */
    private fun reportDeviceReadyToLobby() {
        if (deviceReadyReported) return
        deviceReadyReported = true
        serverClient.sendDeviceReady(true)
    }

    fun bindCloudAnchorEngine(engine: CloudAnchorEngine?) {
        cloudAnchorEngine = engine
        if (engine != null) {
            maybeRunCloudAnchorCalibration()
        }
    }

    private fun maybeRunCloudAnchorCalibration() {
        val state = _uiState.value
        if (state.joinedLobbyPreset != "multi_live") return
        if (state.joinedLobbyCode.length != 6) return
        val engine = cloudAnchorEngine ?: return
        when {
            state.anchorRole == "host" && !anchorHostedReported -> {
                anchorHostedReported = true
                _uiState.update { it.copy(anchorState = "hosting", calibrationGate = "waiting_for_cloud_anchor_host") }
                engine.hostSharedAnchor(::handleHostedCloudAnchorResult)
            }
            state.anchorRole == "resolver" &&
                pendingAnchorIdToResolve.isNotBlank() &&
                !anchorResolvedReported -> {
                anchorResolvedReported = true
                _uiState.update { it.copy(anchorState = "resolving", sharedAnchorId = pendingAnchorIdToResolve) }
                engine.resolveSharedAnchor(pendingAnchorIdToResolve, ::handleResolvedCloudAnchorResult)
            }
        }
    }

    private fun handleHostedCloudAnchorResult(result: CloudAnchorResult) {
        viewModelScope.launch {
            when (result.state) {
                "hosting" -> _uiState.update {
                    it.copy(anchorState = "hosting", errorMessage = null)
                }
                "hosted" -> {
                    _uiState.update {
                        it.copy(
                            anchorState = "hosted",
                            sharedAnchorId = result.sharedAnchorId,
                            errorMessage = null,
                        )
                    }
                    serverClient.sendAnchorHosted(result.sharedAnchorId, result.poseJson(), result.quality)
                }
                else -> {
                    anchorHostedReported = false
                    _uiState.update {
                        it.copy(
                            calibrationStep = CalibrationStep.FAILED,
                            anchorState = "failed",
                            errorMessage = result.errorMessage.ifBlank { "Cloud Anchor hosting failed" },
                        )
                    }
                }
            }
        }
    }

    private fun handleResolvedCloudAnchorResult(result: CloudAnchorResult) {
        viewModelScope.launch {
            when (result.state) {
                "resolving" -> _uiState.update {
                    it.copy(anchorState = "resolving", sharedAnchorId = result.sharedAnchorId, errorMessage = null)
                }
                "resolved" -> {
                    _uiState.update {
                        it.copy(
                            anchorState = "resolved",
                            sharedAnchorId = result.sharedAnchorId,
                            errorMessage = null,
                        )
                    }
                    serverClient.sendAnchorResolved(result.sharedAnchorId, result.poseJson(), result.quality)
                }
                else -> {
                    anchorResolvedReported = false
                    _uiState.update {
                        it.copy(
                            calibrationStep = CalibrationStep.FAILED,
                            anchorState = "failed",
                            errorMessage = result.errorMessage.ifBlank { "Cloud Anchor resolve failed" },
                        )
                    }
                }
            }
        }
    }

    private fun maybeSendExtrinsicUpdate(worldTracking: WorldTrackingSnapshot?) {
        if (_uiState.value.joinedLobbyCode.length != 6) return
        val pos = worldTracking?.cameraPosition?.takeIf { it.size >= 3 } ?: return
        if (extrinsicEmitCountdown > 0) {
            extrinsicEmitCountdown -= 1
            return
        }
        extrinsicEmitCountdown = 15  // ~2 Hz at 30 fps capture
        val rotDeg = quaternionToEulerDegrees(worldTracking?.cameraRotation)
        serverClient.sendExtrinsicUpdate(
            pos = floatArrayOf(pos[0], pos[1], pos[2]),
            rotDegrees = rotDeg,
            calibrationMode = if (_uiState.value.joinedLobbyPreset == "single_live") {
                "single_camera_ar"
            } else {
                "multi_camera_cloud_anchor"
            },
            sharedAnchorId = _uiState.value.sharedAnchorId,
            anchorState = _uiState.value.anchorState,
            quality = worldTracking.confidence,
        )
        maybeRunCloudAnchorCalibration()
    }

    /** Quaternion (x, y, z, w) → intrinsic XYZ euler angles in degrees. Returns zeros if absent. */
    private fun quaternionToEulerDegrees(quat: FloatArray?): FloatArray {
        if (quat == null || quat.size < 4) return floatArrayOf(0f, 0f, 0f)
        val (x, y, z, w) = listOf(quat[0], quat[1], quat[2], quat[3])
        val rad = 180.0 / Math.PI
        // roll (x-axis)
        val sinrCosp = 2.0 * (w * x + y * z)
        val cosrCosp = 1.0 - 2.0 * (x * x + y * y)
        val roll = Math.atan2(sinrCosp, cosrCosp)
        // pitch (y-axis), clamped to avoid NaN at the poles
        val sinp = 2.0 * (w * y - z * x)
        val pitch = if (kotlin.math.abs(sinp) >= 1.0) Math.copySign(Math.PI / 2.0, sinp) else Math.asin(sinp)
        // yaw (z-axis)
        val sinyCosp = 2.0 * (w * z + x * y)
        val cosyCosp = 1.0 - 2.0 * (y * y + z * z)
        val yaw = Math.atan2(sinyCosp, cosyCosp)
        return floatArrayOf((roll * rad).toFloat(), (pitch * rad).toFloat(), (yaw * rad).toFloat())
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
            if (a !in 0 until 33 || b !in 0 until 33 || slot !in _clientTechnicalTorsoLengthMeters.indices) return
            if (visibility.getOrNull(a) ?: 0f < 0.34f) return
            if (visibility.getOrNull(b) ?: 0f < 0.34f) return
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val dz = z[b] - z[a]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (!length.isFinite() || length < 1e-5f) return

            var target = _clientTechnicalTorsoLengthMeters[slot]
            if (learn && length in minMeters..maxMeters) {
                target = if (target.isFinite() && target in minMeters..maxMeters) {
                    val low = target * 0.72f
                    val high = target * 1.34f
                    if (length in low..high) {
                        target * 0.995f + length * 0.005f
                    } else {
                        target
                    }
                } else {
                    length
                }
                _clientTechnicalTorsoLengthMeters[slot] = target
            }
            if (!target.isFinite() || target !in minMeters..maxMeters) return

            val scale = target / length
            val centerX = (x[a] + x[b]) * 0.5f
            val centerY = (y[a] + y[b]) * 0.5f
            val centerZ = (z[a] + z[b]) * 0.5f
            val desiredAX = centerX - dx * scale * 0.5f
            val desiredAY = centerY - dy * scale * 0.5f
            val desiredAZ = centerZ - dz * scale * 0.5f
            val desiredBX = centerX + dx * scale * 0.5f
            val desiredBY = centerY + dy * scale * 0.5f
            val desiredBZ = centerZ + dz * scale * 0.5f
            x[a] += (desiredAX - x[a]) * blend
            y[a] += (desiredAY - y[a]) * blend
            z[a] += (desiredAZ - z[a]) * blend
            x[b] += (desiredBX - x[b]) * blend
            y[b] += (desiredBY - y[b]) * blend
            z[b] += (desiredBZ - z[b]) * blend
        }

        symmetricSegment(0, 11, 12, 0.18f, 0.70f, 0.64f)
        symmetricSegment(1, 23, 24, 0.12f, 0.55f, 0.64f)
        symmetricSegment(2, 11, 23, 0.22f, 0.95f, 0.46f)
        symmetricSegment(3, 12, 24, 0.22f, 0.95f, 0.46f)
        symmetricSegment(4, 11, 24, 0.25f, 1.10f, 0.34f)
        symmetricSegment(5, 12, 23, 0.25f, 1.10f, 0.34f)
    }

    private fun stabilizeClientTechnicalArmBones(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        visibility: FloatArray,
        learn: Boolean,
    ) {
        fun constrain(parent: Int, child: Int, minMeters: Float, maxMeters: Float, blend: Float) {
            if (parent !in 0 until 33 || child !in 0 until 33) return
            if (visibility.getOrNull(parent) ?: 0f < 0.24f) return
            if (visibility.getOrNull(child) ?: 0f < 0.24f) return
            val dx = x[child] - x[parent]
            val dy = y[child] - y[parent]
            val dz = z[child] - z[parent]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (!length.isFinite() || length < 1e-5f) return

            var target = _clientTechnicalBoneLengthMeters[child]
            if (learn && length in minMeters..maxMeters) {
                target = if (target.isFinite() && target in minMeters..maxMeters) {
                    val low = target * 0.68f
                    val high = target * 1.42f
                    if (length in low..high) {
                        target * 0.985f + length * 0.015f
                    } else {
                        target
                    }
                } else {
                    length
                }
                _clientTechnicalBoneLengthMeters[child] = target
            }
            if (!target.isFinite() || target !in minMeters..maxMeters) return

            val scale = target / length
            val desiredX = x[parent] + dx * scale
            val desiredY = y[parent] + dy * scale
            val desiredZ = z[parent] + dz * scale
            x[child] += (desiredX - x[child]) * blend
            y[child] += (desiredY - y[child]) * blend
            z[child] += (desiredZ - z[child]) * blend
        }

        constrain(11, 13, 0.12f, 0.58f, 0.70f)
        constrain(13, 15, 0.12f, 0.58f, 0.76f)
        constrain(12, 14, 0.12f, 0.58f, 0.70f)
        constrain(14, 16, 0.12f, 0.58f, 0.76f)
        constrain(15, 17, 0.02f, 0.24f, 0.58f)
        constrain(15, 19, 0.02f, 0.24f, 0.58f)
        constrain(15, 21, 0.02f, 0.24f, 0.58f)
        constrain(16, 18, 0.02f, 0.24f, 0.58f)
        constrain(16, 20, 0.02f, 0.24f, 0.58f)
        constrain(16, 22, 0.02f, 0.24f, 0.58f)
    }

    private fun updateServerMetricPoseWithClientMotion() {
        serverMetricClientMotionOverlayActive = false
        // The 2D-to-3D client overlay was useful as a latency experiment, but live
        // captures show it can distort canonical server limbs and produce the
        // "big/small/twisted" skeleton failure. Keep Technical/Avatar display on
        // canonical server joints only until a proper local 3D limb overlay exists.
        if (!ENABLE_SERVER_METRIC_CLIENT_MOTION_OVERLAY) return
        val serverDebug = latestServerPoseDebug ?: return
        if (!serverMetricPoseDisplayReady) return
        if (serverDebug.metricPoseStatus == "hold_previous" || heldCanonicalServerPoseFrames > 0) return
        if (lastPose3DAgeMs?.let { it > 45L } == true) return
        if (serverDebug.poseLifterAppliedJointCount.isFinite() && serverDebug.poseLifterAppliedJointCount > 0.5f) return
        val currentServerX = serverPoseX ?: return
        val currentServerY = serverPoseY ?: return
        val currentServerZ = serverPoseZ ?: return
        if (currentServerX.size < 33 || currentServerY.size < 33 || currentServerZ.size < 33) return

        val localPose = latestClientTechnicalPose() ?: return
        val localX = localPose.first.copyOf()
        val localY = localPose.second.copyOf()
        val localZ = localPose.third.copyOf()
        val valid = BooleanArray(33) { idx ->
            idx < _completedVis.size &&
                _completedVis[idx] >= 0.18f &&
                localX[idx].isFinite() &&
                localY[idx].isFinite() &&
                localZ[idx].isFinite()
        }
        if (valid.count { it } < 18) return

        val authoritativeHeight = serverDebug.authoritativeHeightMetersOrNull(
            latestTechnicalSceneMetrics?.correctedHeightMeters ?: Float.NaN,
        )
            ?: lastCanonicalAuthoritativeHeightMeters.takeIf { it.isFinite() && it in 1.05f..2.35f }
            ?: return
        val authoritativeDistance = serverDebug.authoritativeDistanceMetersOrNull(
            latestTechnicalSceneMetrics?.correctedDistanceMeters ?: Float.NaN,
        )
            ?: lastCanonicalAuthoritativeDistanceMeters.takeIf { it.isFinite() && it in 0.35f..12.0f }
        normalizeCanonicalServerDisplayPose(
            x = localX,
            y = localY,
            z = localZ,
            valid = valid,
            targetHeightMeters = authoritativeHeight,
            targetDistanceMeters = authoritativeDistance,
        )

        fun rootOf(x: FloatArray, y: FloatArray, z: FloatArray): Triple<Float, Float, Float>? {
            var rx = 0f
            var ry = 0f
            var rz = 0f
            var count = 0
            for (idx in intArrayOf(23, 24)) {
                if (idx !in 0 until 33) continue
                if (!x[idx].isFinite() || !y[idx].isFinite() || !z[idx].isFinite()) continue
                rx += x[idx]
                ry += y[idx]
                rz += z[idx]
                count += 1
            }
            return if (count >= 2) {
                Triple(rx / count, ry / count, rz / count)
            } else {
                null
            }
        }

        val localRoot = rootOf(localX, localY, localZ) ?: return
        val serverRoot = rootOf(currentServerX, currentServerY, currentServerZ) ?: localRoot
        val outX = currentServerX.copyOf()
        val outY = currentServerY.copyOf()
        val outZ = currentServerZ.copyOf()
        val outConf = (serverPoseConf ?: FloatArray(33)).copyOf()
        var applied = 0

        fun overlayAlpha(index: Int, visibility: Float): Float {
            val base = when (index) {
                13, 14 -> 0.24f
                15, 16 -> 0.30f
                else -> 0.0f
            }
            val confidenceScale = when {
                visibility >= 0.80f -> 1.0f
                visibility >= 0.55f -> 0.72f
                visibility >= 0.35f -> 0.42f
                else -> 0.0f
            }
            return (base * confidenceScale).coerceIn(0f, 0.36f)
        }

        fun overlayMaxStep(index: Int): Float = when (index) {
            15, 16 -> 0.035f
            13, 14 -> 0.030f
            else -> 0.0f
        }

        for (i in 0 until 33) {
            if (!valid[i]) continue
            val visibility = _completedVis.getOrNull(i) ?: 0f
            val alpha = overlayAlpha(i, visibility)
            if (alpha <= 0f) continue

            val candidateX = serverRoot.first + (localX[i] - localRoot.first)
            val candidateY = serverRoot.second + (localY[i] - localRoot.second)
            val candidateZ = serverRoot.third + (localZ[i] - localRoot.third)
            if (!candidateX.isFinite() || !candidateY.isFinite() || !candidateZ.isFinite()) continue

            val rawDeltaX = candidateX - outX[i]
            val rawDeltaY = candidateY - outY[i]
            val rawDeltaZ = candidateZ - outZ[i]
            val rawDelta = sqrt(rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY + rawDeltaZ * rawDeltaZ)
            val rawDeltaLimit = when (i) {
                15, 16 -> 0.32f
                13, 14 -> 0.28f
                else -> 0.0f
            }
            if (rawDeltaLimit > 0f && rawDelta > rawDeltaLimit && visibility < 0.92f) continue

            var dx = (candidateX - outX[i]) * alpha
            var dy = (candidateY - outY[i]) * alpha
            var dz = (candidateZ - outZ[i]) * alpha
            val step = sqrt(dx * dx + dy * dy + dz * dz)
            val maxStep = overlayMaxStep(i)
            if (maxStep <= 0f) continue
            if (step > maxStep && step > 1e-5f) {
                val scale = maxStep / step
                dx *= scale
                dy *= scale
                dz *= scale
            }
            outX[i] += dx
            outY[i] += dy
            outZ[i] += dz
            _serverPoseSmoothX[i] = outX[i]
            _serverPoseSmoothY[i] = outY[i]
            _serverPoseSmoothZ[i] = outZ[i]
            outConf[i] = maxOf(outConf[i], visibility.coerceIn(0f, 1f))
            applied += 1
        }

        if (applied <= 0) return
        stabilizeServerOverlayArmBones(
            x = outX,
            y = outY,
            z = outZ,
            visibility = outConf,
            targetHeightMeters = authoritativeHeight,
        )

        serverPoseX = outX
        serverPoseY = outY
        serverPoseZ = outZ
        serverPoseConf = outConf
        technicalPoseX = outX.copyOf()
        technicalPoseY = outY.copyOf()
        technicalPoseZ = outZ.copyOf()
        technicalGroundY = 0f
        serverMetricClientMotionOverlayActive = true
    }

    private fun stabilizeServerOverlayArmBones(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        visibility: FloatArray,
        targetHeightMeters: Float,
    ) {
        if (!targetHeightMeters.isFinite() || targetHeightMeters !in 1.05f..2.35f) return

        fun constrain(parent: Int, child: Int, ratio: Float, minVisibility: Float, blend: Float) {
            if (parent !in 0 until 33 || child !in 0 until 33) return
            if ((visibility.getOrNull(parent) ?: 0f) < minVisibility) return
            if ((visibility.getOrNull(child) ?: 0f) < minVisibility) return
            val dx = x[child] - x[parent]
            val dy = y[child] - y[parent]
            val dz = z[child] - z[parent]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (!length.isFinite() || length < 1e-5f) return
            val target = targetHeightMeters * ratio
            val scale = target / length
            val desiredX = x[parent] + dx * scale
            val desiredY = y[parent] + dy * scale
            val desiredZ = z[parent] + dz * scale
            x[child] += (desiredX - x[child]) * blend
            y[child] += (desiredY - y[child]) * blend
            z[child] += (desiredZ - z[child]) * blend
        }

        constrain(11, 13, 0.185f, 0.25f, 0.92f)
        constrain(13, 15, 0.160f, 0.22f, 0.94f)
        constrain(12, 14, 0.185f, 0.25f, 0.92f)
        constrain(14, 16, 0.160f, 0.22f, 0.94f)
        for (child in intArrayOf(17, 19, 21)) {
            constrain(15, child, 0.050f, 0.18f, 0.86f)
        }
        for (child in intArrayOf(18, 20, 22)) {
            constrain(16, child, 0.050f, 0.18f, 0.86f)
        }
    }

    private fun canonicalServerDistanceDisagreesWithLocal(serverDebug: ServerPoseDebugSnapshot): Boolean {
        val metricDistance = serverDebug.metricRootDistanceMeters
            .takeIf { it.isFinite() && it in 0.35f..12.0f }
            ?: return false
        val scene = latestTechnicalSceneMetrics ?: latestSceneMetrics ?: return false
        val candidates = listOf(
            scene.correctedDistanceMeters,
            scene.footPlaneDistanceMeters,
            scene.nearestFootFloorDistanceMeters,
            scene.feetMidpointFloorDistanceMeters,
            scene.distanceMeters,
        ).filter { it.isFinite() && it in 0.35f..12.0f }
        if (candidates.size < 2) return false
        val sorted = candidates.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
        } else {
            sorted[sorted.size / 2]
        }
        val spread = sorted.last() - sorted.first()
        if (spread > 0.70f) return false
        return kotlin.math.abs(metricDistance - median) > maxOf(0.75f, median * 0.30f)
    }

    private fun canonicalServerHeightDisagreesWithLocal(serverDebug: ServerPoseDebugSnapshot): Boolean {
        val metricHeight = serverDebug.metricBodyHeightMeters
            .takeIf { it.isFinite() && it in 1.05f..2.35f }
            ?: return false
        val scene = latestTechnicalSceneMetrics ?: latestSceneMetrics ?: return false
        val candidates = listOf(
            scene.correctedHeightMeters,
            scene.localHeightCandidateMeters,
            scene.topRayFloorHeightMeters,
            scene.topRayHeightMeters,
            scene.hipGeometryHeightMeters,
            scene.bodyHeightMeters,
        ).filter { it.isFinite() && it in 1.05f..2.35f }
        if (candidates.size < 2) return false
        val sorted = candidates.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
        } else {
            sorted[sorted.size / 2]
        }
        val spread = sorted.last() - sorted.first()
        if (spread > 0.24f) return false
        return kotlin.math.abs(metricHeight - median) > maxOf(0.22f, median * 0.12f)
    }

    private fun clientBodyVerticalRangeNorm(): Pair<Float, Float>? {
        fun minY(indices: IntArray, minVisibility: Float): Float {
            var value = Float.POSITIVE_INFINITY
            for (idx in indices) {
                if (_completedVis.getOrNull(idx) ?: 0f <= minVisibility) continue
                val y = _completedY.getOrNull(idx) ?: continue
                if (y.isFinite()) value = minOf(value, y)
            }
            return value
        }

        fun maxY(indices: IntArray, minVisibility: Float): Float {
            var value = Float.NEGATIVE_INFINITY
            for (idx in indices) {
                if (_completedVis.getOrNull(idx) ?: 0f <= minVisibility) continue
                val y = _completedY.getOrNull(idx) ?: continue
                if (y.isFinite()) value = maxOf(value, y)
            }
            return value
        }

        val topY = minY(intArrayOf(0, 7, 8, 9, 10), 0.18f)
            .takeIf { it.isFinite() }
            ?: minY(intArrayOf(11, 12, 13, 14, 23, 24), 0.22f).takeIf { it.isFinite() }
            ?: return null
        val floorY = maxY(intArrayOf(27, 28, 29, 30, 31, 32), 0.18f)
            .takeIf { it.isFinite() }
            ?: maxY(intArrayOf(25, 26, 23, 24), 0.22f).takeIf { it.isFinite() }
            ?: return null
        return if (floorY > topY + 0.12f) {
            Pair(topY, floorY)
        } else {
            null
        }
    }

    private fun cameraToDisplayWorld(
        x: Float,
        y: Float,
        z: Float,
        rotationDegrees: Int,
    ): Triple<Float, Float, Float> {
        val rx: Float
        val ryDown: Float
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> {
                rx = -y
                ryDown = x
            }
            180 -> {
                rx = -x
                ryDown = -y
            }
            270 -> {
                rx = y
                ryDown = -x
            }
            else -> {
                rx = x
                ryDown = y
            }
        }
        return Triple(rx, -ryDown, -z)
    }

    private fun normalizeCanonicalServerDisplayPose(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        valid: BooleanArray,
        targetHeightMeters: Float?,
        targetDistanceMeters: Float?,
    ): Boolean {
        val targetHeight = targetHeightMeters?.takeIf { it.isFinite() && it in 1.05f..2.35f } ?: return false

        fun minY(indices: IntArray, minValid: Boolean = true): Float {
            var value = Float.POSITIVE_INFINITY
            for (idx in indices) {
                if (idx !in 0 until 33) continue
                if (minValid && !valid[idx]) continue
                val yi = y[idx]
                if (yi.isFinite()) value = minOf(value, yi)
            }
            return value
        }

        fun maxY(indices: IntArray, minValid: Boolean = true): Float {
            var value = Float.NEGATIVE_INFINITY
            for (idx in indices) {
                if (idx !in 0 until 33) continue
                if (minValid && !valid[idx]) continue
                val yi = y[idx]
                if (yi.isFinite()) value = maxOf(value, yi)
            }
            return value
        }

        val floor = minY(intArrayOf(27, 28, 29, 30, 31, 32))
            .takeIf { it.isFinite() }
            ?: minY(intArrayOf(25, 26, 23, 24)).takeIf { it.isFinite() }
            ?: return false
        val top = maxY(intArrayOf(0, 7, 8, 9, 10))
            .takeIf { it.isFinite() }
            ?: maxY(intArrayOf(11, 12, 23, 24)).takeIf { it.isFinite() }
            ?: return false
        val rawHeight = top - floor
        if (!rawHeight.isFinite() || rawHeight < 0.45f) return false

        val rootIndices = intArrayOf(23, 24)
        var rootX = 0f
        var rootY = 0f
        var rootZ = 0f
        var rootCount = 0
        for (idx in rootIndices) {
            if (!valid[idx]) continue
            rootX += x[idx]
            rootY += y[idx]
            rootZ += z[idx]
            rootCount += 1
        }
        if (rootCount <= 0) return false
        rootX /= rootCount
        rootY /= rootCount
        rootZ /= rootCount

        val scale = (targetHeight / rawHeight).coerceIn(0.08f, 2.75f)
        val targetRootX = latestTechnicalSceneMetrics
            ?.lateralOffsetMeters
            ?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 2.5f }
            ?: 0f
        val targetRootY = latestServerPoseDebug
            ?.metricRootYMeters
            ?.takeIf { it.isFinite() && it in -0.10f..2.35f }
            ?: (rootY - floor) * scale
        val targetRootZ = latestServerPoseDebug
            ?.metricRootZMeters
            ?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 12f }
            ?: targetDistanceMeters
                ?.takeIf { it.isFinite() && it in 0.35f..12f }
                ?.let { -it }
            ?: rootZ * scale

        for (i in 0 until 33) {
            if (!valid[i]) continue
            x[i] = targetRootX + (x[i] - rootX) * scale
            y[i] = targetRootY + (y[i] - rootY) * scale
            z[i] = targetRootZ + (z[i] - rootZ) * scale
        }
        return true
    }

    private fun updateServerPoseArrays(pose3dJson: JSONObject): String {
        val jointsArr = pose3dJson.optJSONArray("joints")
        if (jointsArr == null || jointsArr.length() < 33) {
            lastServerJointsCount = jointsArr?.length() ?: 0
            return holdPreviousServerPoseOrClear("pose3d_without_joints")
        }

        val rawX = FloatArray(33)
        val rawY = FloatArray(33)
        val rawZ = FloatArray(33)
        val conf = FloatArray(33)
        val valid = BooleanArray(33)
        var validCount = 0

        for (i in 0 until 33) {
            val joint = jointsArr.optJSONObject(i) ?: continue
            val confidence = joint.optDouble("confidence", 0.0).toFloat()
            val hasXYZ =
                joint.has("x") && !joint.isNull("x") &&
                joint.has("y") && !joint.isNull("y") &&
                joint.has("z") && !joint.isNull("z")
            val isValid = joint.optBoolean("valid", hasXYZ && confidence > 0f)
            if (!hasXYZ || !isValid) continue

            rawX[i] = joint.optDouble("x", 0.0).toFloat()
            rawY[i] = joint.optDouble("y", 0.0).toFloat()
            rawZ[i] = joint.optDouble("z", 0.0).toFloat()
            conf[i] = confidence
            valid[i] = true
            validCount++
        }
        lastServerJointsCount = validCount

        if (validCount < 4) {
            return holdPreviousServerPoseOrClear("too_few_valid_server_joints")
        }

        val serverDebug = latestServerPoseDebug
        val metricPoseStatus = serverDebug?.metricPoseStatus.orEmpty()
        if (metricPoseStatus.isNotBlank() && metricPoseStatus !in setOf("ok", "hold_previous")) {
            val reason = serverDebug?.metricPoseRejectReason?.takeIf { it.isNotBlank() } ?: metricPoseStatus
            return holdPreviousServerPoseOrClear("metric_pose_$reason")
        }
        if (serverDebug?.hasCanonicalMetricPose() == true) {
            val normalized = serverDebug.poseJointsNormalized
            if (
                serverDebug.poseJointsFrame == "display_floor_metric_v1" &&
                (!normalized.isFinite() || kotlin.math.abs(normalized - 1f) > 1e-3f)
            ) {
                return holdPreviousServerPoseOrClear("canonical_joints_not_normalized")
            }
            if (canonicalServerDistanceDisagreesWithLocal(serverDebug)) {
                return holdPreviousServerPoseOrClear("canonical_distance_local_disagreement")
            }
            if (canonicalServerHeightDisagreesWithLocal(serverDebug)) {
                return holdPreviousServerPoseOrClear("canonical_height_local_disagreement")
            }
        }
        val heightAdmission = serverDebug?.heightTargetAdmission.orEmpty()
        val constrainedHeight = serverDebug?.constrainedHeightMeters ?: Float.NaN
        val targetHeight = serverDebug?.heightTargetAfterGateMeters?.takeIf { it.isFinite() }
            ?: serverDebug?.arTargetHeightMeters?.takeIf { it.isFinite() }
            ?: Float.NaN
        val canonicalHeightTrusted = serverDebug?.hasCanonicalMetricPose() == true
        val trustedConstrainedHeight =
            canonicalHeightTrusted ||
                constrainedHeight.isFinite() &&
                constrainedHeight in 1.05f..2.35f &&
                heightAdmission in setOf("accepted", "soft_accept", "hold_previous") &&
                (!targetHeight.isFinite() || kotlin.math.abs(constrainedHeight - targetHeight) <= 0.18f)
        val rawHeightAcceptable =
            serverDebug?.rawHeightMeters?.takeIf { it.isFinite() }?.let { it in 1.25f..2.25f } == true &&
                serverDebug.correctionReason !in setOf("scene_metrics_unusable", "raw_height_unusable", "no_scene_metrics")
        if (!trustedConstrainedHeight && !rawHeightAcceptable) {
            return holdPreviousServerPoseOrClear("server_height_target_untrusted")
        }

        val sx = FloatArray(33)
        val sy = FloatArray(33)
        val sz = FloatArray(33)
        var floorY = Float.POSITIVE_INFINITY
        val serverJointsAlreadyDisplayFrame =
            latestServerPoseDebug?.poseJointsFrame == "display_floor_metric_v1"

        for (i in 0 until 33) {
            if (!valid[i]) continue
            if (serverJointsAlreadyDisplayFrame) {
                sx[i] = rawX[i]
                sy[i] = rawY[i]
                sz[i] = rawZ[i]
            } else {
                val (displayX, displayY, displayZ) = cameraToDisplayWorld(
                    x = rawX[i],
                    y = rawY[i],
                    z = rawZ[i],
                    rotationDegrees = latestServerRotationDegrees,
                )
                sx[i] = displayX
                sy[i] = displayY
                sz[i] = displayZ
            }
        }

        for (idx in intArrayOf(27, 28, 29, 30, 31, 32)) {
            if (valid[idx]) floorY = min(floorY, sy[idx])
        }
        if (!floorY.isFinite()) {
            for (i in 0 until 33) {
                if (valid[i]) floorY = min(floorY, sy[i])
            }
        }

        val rawHeightMeters = if (floorY.isFinite()) {
            var maxY = Float.NEGATIVE_INFINITY
            for (i in 0 until 33) {
                if (valid[i]) maxY = maxOf(maxY, sy[i])
            }
            if (maxY.isFinite()) {
                (maxY - floorY).coerceAtLeast(0f)
            } else {
                Float.NaN
            }
        } else {
            Float.NaN
        }

        val rootIndices = intArrayOf(23, 24, 11, 12)
        var rootX = 0f
        var rootY = 0f
        var rootZ = 0f
        var rootCount = 0
        for (idx in rootIndices) {
            if (!valid[idx]) continue
            rootX += sx[idx]
            rootY += sy[idx]
            rootZ += sz[idx]
            rootCount += 1
        }
        val rawDistanceMeters = if (rootCount > 0) {
            rootX /= rootCount
            rootY /= rootCount
            rootZ /= rootCount
            sqrt(rootX * rootX + rootY * rootY + rootZ * rootZ)
        } else {
            Float.NaN
        }

        val authoritativeHeight = latestServerPoseDebug?.authoritativeHeightMetersOrNull(
            latestTechnicalSceneMetrics?.correctedHeightMeters ?: Float.NaN,
        )
        val authoritativeDistance = latestServerPoseDebug?.authoritativeDistanceMetersOrNull(
            latestTechnicalSceneMetrics?.correctedDistanceMeters ?: Float.NaN,
        )
        technicalHeightMeters = authoritativeHeight
            ?: latestTechnicalSceneMetrics?.correctedHeightMeters?.takeIf { it.isFinite() }
            ?: latestTechnicalSceneMetrics?.bodyHeightMeters?.takeIf { it.isFinite() }
            ?: if (floorY.isFinite()) rawHeightMeters else Float.NaN
        technicalDistanceMeters = authoritativeDistance
            ?: latestTechnicalSceneMetrics?.correctedDistanceMeters?.takeIf { it.isFinite() }
            ?: latestTechnicalSceneMetrics?.distanceMeters?.takeIf { it.isFinite() }
            ?: if (rawDistanceMeters.isFinite()) rawDistanceMeters else Float.NaN

        if (floorY.isFinite()) {
            for (i in 0 until 33) {
                if (valid[i]) sy[i] -= floorY
            }
        }
        val canonicalMetricAuthority = latestServerPoseDebug?.hasCanonicalMetricPose() == true
        var canonicalDisplayReady =
            canonicalMetricAuthority &&
                latestServerPoseDebug?.poseJointsFrame == "display_floor_metric_v1" &&
                latestServerPoseDebug?.poseJointsNormalized?.takeIf { it.isFinite() }?.let {
                    kotlin.math.abs(it - 1f) <= 1e-3f
                } == true
        if (canonicalMetricAuthority && !canonicalDisplayReady) {
            canonicalDisplayReady = normalizeCanonicalServerDisplayPose(
                x = sx,
                y = sy,
                z = sz,
                valid = valid,
                targetHeightMeters = authoritativeHeight,
                targetDistanceMeters = authoritativeDistance,
            )
        }

        if (canonicalDisplayReady) {
            technicalPoseX = sx.copyOf()
            technicalPoseY = sy.copyOf()
            technicalPoseZ = sz.copyOf()
            technicalGroundY = 0f
        } else {
            technicalPoseX = null
            technicalPoseY = null
            technicalPoseZ = null
            technicalGroundY = Float.NaN
        }

        val outX = FloatArray(33)
        val outY = FloatArray(33)
        val outZ = FloatArray(33)
        val serverMetricAuthority = canonicalMetricAuthority || latestServerPoseDebug?.hasV2MetricAuthority() == true
        val recoveringFromStalePose =
            lastPose3DInterarrivalMs > 115L ||
                heldCanonicalServerPoseFrames > 0 ||
                lastPose3DAgeMs?.let { it > 85L } == true
        val turnTransitionState = bodyTurnTransitionDetector.update(
            xNorm = _completedX,
            yNorm = _completedY,
            visibility = _completedVis,
        )
        val turnFastUpdateActive = canonicalMetricAuthority && turnTransitionState.fastUpdateActive
        for (i in 0 until 33) {
            if (!valid[i]) {
                if (_hasServerPose) {
                    outX[i] = _serverPoseSmoothX[i]
                    outY[i] = _serverPoseSmoothY[i]
                    outZ[i] = _serverPoseSmoothZ[i]
                }
                continue
            }

            val alpha = if (_hasServerPose) {
                val dx = sx[i] - _serverPoseSmoothX[i]
                val dy = sy[i] - _serverPoseSmoothY[i]
                val dz = sz[i] - _serverPoseSmoothZ[i]
                val motion = sqrt(dx * dx + dy * dy + dz * dz)
                val fastLimb = canonicalMetricAuthority && isServerFastLimbJoint(i)
                val bodyAnchor = canonicalMetricAuthority && isServerRootOrBodyAnchor(i)
                val baseAlphaRaw = when {
                    fastLimb && conf[i] >= 0.80f -> 0.68f
                    fastLimb && conf[i] >= 0.55f -> 0.56f
                    fastLimb -> 0.42f
                    bodyAnchor && conf[i] >= 0.80f -> 0.34f
                    bodyAnchor && conf[i] >= 0.55f -> 0.28f
                    bodyAnchor -> 0.20f
                    canonicalMetricAuthority && conf[i] >= 0.80f -> 0.50f
                    canonicalMetricAuthority && conf[i] >= 0.55f -> 0.40f
                    canonicalMetricAuthority -> 0.28f
                    conf[i] >= 0.80f -> 0.28f
                    conf[i] >= 0.55f -> 0.22f
                    else -> 0.16f
                }
                val baseAlpha = if (turnFastUpdateActive) {
                    when {
                        fastLimb && conf[i] >= 0.55f -> maxOf(baseAlphaRaw, 0.78f)
                        fastLimb -> maxOf(baseAlphaRaw, 0.62f)
                        bodyAnchor && conf[i] >= 0.55f -> maxOf(baseAlphaRaw, 0.58f)
                        bodyAnchor -> maxOf(baseAlphaRaw, 0.46f)
                        canonicalMetricAuthority && conf[i] >= 0.55f -> maxOf(baseAlphaRaw, 0.66f)
                        canonicalMetricAuthority -> maxOf(baseAlphaRaw, 0.52f)
                        else -> baseAlphaRaw
                    }
                } else {
                    baseAlphaRaw
                }
                val motionDenominator = when {
                    turnFastUpdateActive && fastLimb -> 0.070f
                    turnFastUpdateActive && bodyAnchor -> 0.110f
                    turnFastUpdateActive && canonicalMetricAuthority -> 0.120f
                    fastLimb -> 0.10f
                    canonicalMetricAuthority -> 0.18f
                    serverMetricAuthority -> 0.30f
                    else -> 0.18f
                }
                val motionBoostMax = when {
                    turnFastUpdateActive && fastLimb -> 0.28f
                    turnFastUpdateActive && bodyAnchor -> 0.20f
                    turnFastUpdateActive && canonicalMetricAuthority -> 0.24f
                    fastLimb -> 0.24f
                    bodyAnchor -> 0.12f
                    serverMetricAuthority -> 0.22f
                    else -> 0.56f
                }
                val maxAlpha = when {
                    turnFastUpdateActive && fastLimb -> 0.98f
                    turnFastUpdateActive && bodyAnchor -> 0.80f
                    turnFastUpdateActive && canonicalMetricAuthority -> 0.88f
                    fastLimb -> 0.92f
                    bodyAnchor -> 0.52f
                    canonicalMetricAuthority -> 0.74f
                    serverMetricAuthority -> 0.42f
                    else -> 0.80f
                }
                val motionBoost = (motion / motionDenominator).coerceIn(0f, 1f) * motionBoostMax
                (baseAlpha + motionBoost).coerceIn(baseAlpha, maxAlpha)
            } else {
                1f
            }

            var dx = (sx[i] - _serverPoseSmoothX[i]) * alpha
            var dy = (sy[i] - _serverPoseSmoothY[i]) * alpha
            var dz = (sz[i] - _serverPoseSmoothZ[i]) * alpha
            if (_hasServerPose && canonicalMetricAuthority) {
                val step = sqrt(dx * dx + dy * dy + dz * dz)
                val maxStep = serverDisplayMaxStepMeters(
                    index = i,
                    recoveringFromStalePose = recoveringFromStalePose,
                    turnFastUpdateActive = turnFastUpdateActive,
                )
                if (step > maxStep && step > 1e-5f) {
                    val scale = maxStep / step
                    dx *= scale
                    dy *= scale
                    dz *= scale
                }
            }
            _serverPoseSmoothX[i] += dx
            _serverPoseSmoothY[i] += dy
            _serverPoseSmoothZ[i] += dz
            outX[i] = _serverPoseSmoothX[i]
            outY[i] = _serverPoseSmoothY[i]
            outZ[i] = _serverPoseSmoothZ[i]
        }

        if (canonicalMetricAuthority) {
            val canonicalHeight = authoritativeHeight
                ?: latestServerPoseDebug?.metricBodyHeightMeters?.takeIf { it.isFinite() && it in 1.05f..2.35f }
            if (canonicalHeight != null) {
                enforceCanonicalLimbEndpoints(
                    x = outX,
                    y = outY,
                    z = outZ,
                    confidence = conf,
                    targetHeightMeters = canonicalHeight,
                )
                for (idx in intArrayOf(13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 25, 26, 27, 28, 29, 30, 31, 32)) {
                    _serverPoseSmoothX[idx] = outX[idx]
                    _serverPoseSmoothY[idx] = outY[idx]
                    _serverPoseSmoothZ[idx] = outZ[idx]
                }
            }
        }

        _hasServerPose = true
        serverPoseStableFrames = (serverPoseStableFrames + 1).coerceAtMost(120)
        serverMetricPoseDisplayReady = canonicalDisplayReady
        if (canonicalDisplayReady) {
            heldCanonicalServerPoseFrames = 0
            val canonicalHeight = authoritativeHeight
                ?: latestServerPoseDebug?.metricBodyHeightMeters?.takeIf { it.isFinite() && it in 1.05f..2.35f }
            val canonicalDistance = authoritativeDistance
                ?: latestServerPoseDebug?.metricRootDistanceMeters?.takeIf { it.isFinite() && it in 0.35f..12.0f }
            if (canonicalHeight != null) {
                lastCanonicalAuthoritativeHeightMeters = canonicalHeight
            }
            if (canonicalDistance != null) {
                lastCanonicalAuthoritativeDistanceMeters = canonicalDistance
            }
        }
        serverPoseX = outX
        serverPoseY = outY
        serverPoseZ = outZ
        serverPoseConf = conf
        return "ok"
    }

    // ── Connection ──
    fun connect(url: String = _uiState.value.serverUrl) {
        val serverUrl = ServerLinkParser.parseServerUrl(url)
        if (serverUrl == null) {
            _uiState.update { it.copy(errorMessage = "Invalid Pocap link or server URL") }
            return
        }
        userInitiatedDisconnect = false
        autoResumeServerAfterReconnect = false
        clearServerPoseArrays()
        resetServerTransportDiagnostics()
        _uiState.update { it.copy(
            serverUrl = serverUrl,
            connectionState = ConnectionState.CONNECTING,
            joinedLobbyCode = "",
            joinedLobbyName = "",
            joinedLobbyPreset = "single_live",
            lobbyJoinState = LobbyJoinState.IDLE,
            errorMessage = null,
        )}
        serverClient.connect(serverUrl)
    }

    fun updateLobbyCode(code: String) {
        _uiState.update { it.copy(
            lobbyCodeInput = code.filter(Char::isDigit).take(6),
            errorMessage = null,
        ) }
    }


    fun leaveSessionCodeEntry() {
        userInitiatedDisconnect = true
        autoResumeServerAfterReconnect = false
        clearServerPoseArrays()
        resetServerTransportDiagnostics()
        serverClient.disconnect()
        pipeline?.stop()
        _uiState.update { it.copy(
            connectionState = ConnectionState.DISCONNECTED,
            joinedLobbyCode = "",
            joinedLobbyName = "",
            joinedLobbyPreset = "single_live",
            lobbyJoinState = LobbyJoinState.IDLE,
            calibrationStep = CalibrationStep.PENDING,
            bootstrapProgress = 0f,
            errorMessage = null,
        ) }
    }

    fun leaveToJoinCode() {
        userInitiatedDisconnect = false
        autoResumeServerAfterReconnect = false
        clearServerPoseArrays()
        resetServerTransportDiagnostics()
        pipeline?.stop()
        _uiState.update { it.copy(
            joinedLobbyCode = "",
            joinedLobbyName = "",
            joinedLobbyPreset = "single_live",
            lobbyJoinState = LobbyJoinState.IDLE,
            calibrationStep = CalibrationStep.PENDING,
            bootstrapProgress = 0f,
            errorMessage = null,
        ) }
    }

    fun leaveJoinedSession() {
        userInitiatedDisconnect = false
        autoResumeServerAfterReconnect = false
        clearServerPoseArrays()
        resetServerTransportDiagnostics()
        serverClient.disconnect()
        pipeline?.stop()
        _uiState.update { it.copy(
            connectionState = ConnectionState.CONNECTED,
            joinedLobbyCode = "",
            joinedLobbyName = "",
            joinedLobbyPreset = "single_live",
            lobbyJoinState = LobbyJoinState.IDLE,
            calibrationStep = CalibrationStep.PENDING,
            bootstrapProgress = 0f,
            errorMessage = null,
        ) }
    }

    fun joinSession(code: String = _uiState.value.lobbyCodeInput) {
        val normalizedCode = code.filter(Char::isDigit).take(6)
        if (normalizedCode.length != 6) {
            _uiState.update { it.copy(
                lobbyCodeInput = normalizedCode,
                lobbyJoinState = LobbyJoinState.IDLE,
                errorMessage = "Enter the 6-digit session code from the PC app",
            ) }
            return
        }
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) {
            _uiState.update { it.copy(
                lobbyJoinState = LobbyJoinState.IDLE,
                errorMessage = "Link this phone to the Pocap PC server first",
            ) }
            return
        }
        _uiState.update { it.copy(
            lobbyCodeInput = normalizedCode,
            lobbyJoinState = LobbyJoinState.JOINING,
            errorMessage = null,
        ) }
        serverClient.joinLobby(
            code = normalizedCode,
            deviceId = deviceId(),
            label = deviceLabel(),
        )
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        autoResumeServerAfterReconnect = false
        clearServerPoseArrays()
        resetServerTransportDiagnostics()
        serverClient.disconnect()
        pipeline?.stop()
    }

    // ── Calibration ──
    fun beginCalibration(imageWidth: Int, imageHeight: Int) {
        Log.i(TAG, "beginCalibration: ${imageWidth}x${imageHeight}")
        clearServerPoseArrays()
        resetServerTransportDiagnostics()
        resetClientTrackingState()
        serverCalibrationWidth = imageWidth
        serverCalibrationHeight = imageHeight
        latestCameraIntrinsics = readCameraIntrinsics(imageWidth, imageHeight)
        ensurePipeline()
        viewModelScope.launch {
            // Step 1: Intrinsic — read from Camera2 API via bridge
            _uiState.update { it.copy(calibrationStep = CalibrationStep.INTRINSIC_CALC) }
            Log.i(TAG, "calibration step: INTRINSIC_CALC")
            delay(600)

            // Step 2: Extrinsic = identity (phone is origin)
            _uiState.update { it.copy(calibrationStep = CalibrationStep.EXTRINSIC_ANCHOR) }
            Log.i(TAG, "calibration step: EXTRINSIC_ANCHOR")
            delay(600)

            // Step 3: Send calibration to server → triggers bootstrap
            Log.i(TAG, "Sending calibration to server: ${imageWidth}x${imageHeight}")
            val p = pipeline
            if (p == null) {
                Log.e(TAG, "Pipeline is null after ensurePipeline — cannot calibrate")
                _uiState.update { it.copy(errorMessage = "Pipeline not ready") }
                return@launch
            }
            p.beginCalibration(imageWidth, imageHeight)
            Log.i(TAG, "calibration sent; capture-device screen remains active")
        }
    }

    private fun readCameraIntrinsics(imageWidth: Int, imageHeight: Int): CameraIntrinsics? {
        val json = runCatching {
            JSONObject(PocketMocapBridge.getInstance().getCameraIntrinsicsJson(getApplication()))
        }.getOrNull() ?: return null
        return CameraIntrinsics(
            fx = json.optDouble("fx", imageWidth * 1.2).toFloat(),
            fy = json.optDouble("fy", imageWidth * 1.2).toFloat(),
            cx = json.optDouble("cx", imageWidth * 0.5).toFloat(),
            cy = json.optDouble("cy", imageHeight * 0.5).toFloat(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun setMirrorDistance(distance: Float) {
        _uiState.update { it.copy(mirrorDistance = distance) }
        pipeline?.setMirrorDistance(distance)
    }

    fun setManualCameraHeightMeters(heightMeters: Float) {
        val calibrated = heightMeters.coerceIn(0.20f, 2.50f)
        sceneBiasPrefs.edit()
            .putFloat(PREF_MANUAL_CAMERA_HEIGHT_M, calibrated)
            .apply()
        _uiState.update { it.copy(manualCameraHeightMeters = calibrated) }
    }

    fun setManualSubjectHeightMeters(heightMeters: Float) {
        val calibrated = heightMeters.takeIf { it.isFinite() && it in 1.05f..2.35f } ?: Float.NaN
        sceneBiasPrefs.edit().apply {
            putBoolean(PREF_SUBJECT_HEIGHT_ENABLED, calibrated.isFinite())
            if (calibrated.isFinite()) {
                putFloat(PREF_SUBJECT_HEIGHT_M, calibrated)
            }
        }.apply()
        _uiState.update { it.copy(manualSubjectHeightMeters = calibrated) }
    }

    /** Start phone capture calibration after the device has joined the server. */
    fun startCalibration() {
        val state = _uiState.value
        if (state.connectionState != ConnectionState.CONNECTED) {
            _uiState.update { it.copy(errorMessage = "Not connected to server") }
            return
        }
        if (state.lobbyJoinState != LobbyJoinState.JOINED || state.joinedLobbyCode.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Join the PC session code before starting capture") }
            return
        }
        beginCalibration(1920, 1080)
    }

    /** One-tap: connect (if needed) → calibrate → navigate to capture. */
    fun connectAndCalibrate() {
        val state = _uiState.value
        if (state.connectionState == ConnectionState.CONNECTED) {
            startCalibration()
        } else {
            connect()
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(
            serverUrl = url,
            joinedLobbyCode = "",
            joinedLobbyName = "",
            joinedLobbyPreset = "single_live",
            lobbyJoinState = LobbyJoinState.IDLE,
            errorMessage = null,
        ) }
    }

    fun applyServerLink(raw: String): Boolean {
        val serverUrl = ServerLinkParser.parseServerUrl(raw)
        if (serverUrl == null) {
            _uiState.update { it.copy(errorMessage = "Invalid Pocap link or server URL") }
            return false
        }
        _uiState.update { it.copy(
            serverUrl = serverUrl,
            joinedLobbyCode = "",
            joinedLobbyName = "",
            joinedLobbyPreset = "single_live",
            lobbyJoinState = LobbyJoinState.IDLE,
            errorMessage = null,
        ) }
        return true
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun markScreenEvidenceRecordingStarted(phoneVideoPath: String = "") {
        isScreenEvidenceRecording = true
        screenEvidenceRecordingStatus = "Recording overlay MP4"
        serverClient.sendCaptureStart(phoneVideoPath)
        clearError()
    }

    fun markScreenEvidenceRecordingStopped(reason: String = "REC off") {
        val wasRecording = isScreenEvidenceRecording
        isScreenEvidenceRecording = false
        screenEvidenceRecordingStatus = reason
        if (wasRecording) {
            serverClient.sendCaptureStop()
        }
    }

    private fun deviceId(): String {
        val app = getApplication<Application>()
        return Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
                .lowercase()
                .replace(Regex("[^a-z0-9_-]"), "-")
                .ifBlank { "android-phone" }
    }

    private fun deviceLabel(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Pocap Phone" }
}

internal fun isServerSceneMetricSource(source: String): Boolean {
    val normalized = source.trim()
    return normalized == "imu_roi_fallback" ||
        normalized == "roi_fallback" ||
        normalized.startsWith("arcore_floor")
}
