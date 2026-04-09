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
