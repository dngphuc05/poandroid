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
