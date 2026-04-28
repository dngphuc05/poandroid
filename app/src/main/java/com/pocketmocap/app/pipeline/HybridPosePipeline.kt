package com.pocketmocap.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.pocketmocap.app.CapturedCameraFrame
import com.pocketmocap.app.network.LandmarkData
import com.pocketmocap.app.network.MocapServerClient
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Hybrid pipeline: runs MediaPipe on-device, sends 2D landmarks to server,
 * receives 3D pose back via Socket.IO.
 *
 * Flow: Camera → MediaPipe → LandmarkData → MocapServerClient → pose_3d
 */
class HybridPosePipeline(
    private val context: Context,
    private val intrinsicsJsonProvider: () -> String,
    private val serverClient: MocapServerClient,
    private val listener: Listener,
) {
    private data class PendingServerFrame(
        val frameIndex: Int,
        val timestampUs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val rotationDegrees: Int,
        val landmarks: List<LandmarkData>,
        val worldTracking: WorldTrackingSnapshot?,
        val sceneMetrics: SceneMetricSnapshot?,
        val mlImage: MlImagePayload?,
    )

    private data class MlImagePayload(
        val jpegBase64: String,
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val cropWidth: Int,
        val cropHeight: Int,
        val jpegQuality: Int,
        val cropPadRatio: Float,
    )

    data class MlCropLandmarks(
        val xNorm: FloatArray,
        val yNorm: FloatArray,
        val visibility: FloatArray,
    )

    data class VisualTopScan(
        val yNorm: Float,
        val confidence: Float,
    )

    companion object {
        private const val TAG = "HybridPosePipeline"
        private const val MODEL_ASSET_PATH = "pose_landmarker_lite.task"  // 8ms vs full 25ms; fresher results beat marginal accuracy gain for fast motion
        private const val MAX_TRACKED_POSES = 1
        private const val JOINT_COUNT = 33
        private const val ML_TRANSPORT_IMAGE_SIZE = 320
        private const val ML_JPEG_QUALITY = 92
        private const val ML_CROP_PAD_RATIO = 0.18f
        private const val ML_CROP_MIN_VISIBILITY = 0.18f
        private const val ML_CROP_CLAMP_EDGE_EPS = 0.006f
        private const val ML_CROP_MIN_BODY_HEIGHT_NORM = 0.30f
        private const val SYNTHETIC_FALLBACK_VISIBILITY = 0.18f
        private val ML_CROP_UPPER_CORE = intArrayOf(0, 7, 8, 11, 12)
        private val ML_CROP_LOWER_CORE = intArrayOf(23, 24, 25, 26, 27, 28, 31, 32)
    }

