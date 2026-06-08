package com.pocketmocap.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.pocketmocap.app.BuildConfig
import com.pocketmocap.app.CapturedCameraFrame
import com.pocketmocap.app.network.LandmarkData
import com.pocketmocap.app.network.MocapServerClient
import com.pocketmocap.app.tracking.ImageToViewTransform
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import com.pocketmocap.app.tracking.cameraIntrinsicsFromJsonScaled
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
        private val MODEL_ASSET_PATH: String = BuildConfig.POSE_LANDMARKER_MODEL
        private val OUTPUT_SEGMENTATION_MASKS: Boolean = BuildConfig.POSE_SEGMENTATION_MASKS
        private const val MAX_TRACKED_POSES = 1
        private const val JOINT_COUNT = 33
        // Pixel capture currently runs through ARCore CPU images before MediaPipe.
        // Keeping the input below full preview size is the difference between
        // ~15fps evidence and a usable live cadence on the target phone path.
        private const val MAX_MEDIAPIPE_INPUT_LONG_EDGE = 736
        private const val ML_TRANSPORT_IMAGE_SIZE = 320
        private const val ML_JPEG_QUALITY = 92
        private const val ML_CROP_PAD_RATIO = 0.18f
        private const val ML_CROP_MIN_VISIBILITY = 0.18f
        private const val ML_CROP_CLAMP_EDGE_EPS = 0.006f
        private const val ML_CROP_MIN_BODY_HEIGHT_NORM = 0.30f
        private const val SYNTHETIC_FALLBACK_VISIBILITY = 0.15f
        private val ML_CROP_UPPER_CORE = intArrayOf(0, 7, 8, 11, 12)
        private val ML_CROP_LOWER_CORE = intArrayOf(23, 24, 25, 26, 27, 28, 31, 32)
    }

    interface Listener {
        fun onStateChanged(state: PipelineState)
        fun onPose3DReceived(pose3dJson: JSONObject)
        /** Called on every processed frame with normalized [0..1] coordinates, optional world XYZ in metres, and visibility. */
        fun onLandmarksDetected(
            xNorm: FloatArray, yNorm: FloatArray, visibility: FloatArray,
            zWorld: FloatArray? = null,
            xWorld: FloatArray? = null, yWorld: FloatArray? = null,
            imageWidth: Int = 0, imageHeight: Int = 0,
            worldTracking: WorldTrackingSnapshot? = null,
            visualTopYNorm: Float = Float.NaN,
            visualTopConfidence: Float = Float.NaN,
        ) {}
        /**
         * Gives the client one chance to replace the raw MediaPipe packet before we send it
         * to the server. This is where we can forward the already-smoothed 33-point set
         * instead of making the server repeat the same 2D cleanup work.
         */
        fun prepareServerLandmarks(
            rawLandmarks: List<LandmarkData>,
            imageWidth: Int,
            imageHeight: Int,
            rotationDegrees: Int,
            imageToViewTransform: ImageToViewTransform,
        ): List<LandmarkData>? = null
        /**
         * Supplies the landmark basis for ML image cropping. Prefer observed/smoothed
         * landmarks over template-completed landmarks so synthetic fallback points do
         * not drag the V4 crop into the floor or frame edge.
         */
        fun prepareMlImageCropLandmarks(
            rawXNorm: FloatArray,
            rawYNorm: FloatArray,
            rawVisibility: FloatArray,
        ): MlCropLandmarks? = null
        fun prepareServerSceneMetrics(worldTracking: WorldTrackingSnapshot?): SceneMetricSnapshot? = null
        fun onServerFrameQueued(frameIndex: Int, timestampUs: Long, transportHint: String) {}
        /** Called when no pose is detected in the frame. */
        fun onNoPoseDetected() {}
    }

    enum class PipelineState {
        IDLE, CONNECTING, CALIBRATING, BOOTSTRAPPING, CAPTURING, ERROR
    }

    private var poseLandmarker: PoseLandmarker? = null
    private var state = PipelineState.IDLE
    private var frameIndex = 0
    private var bootstrapFramesSent = 0
    private val bootstrapTarget = 15
    private var sensitivity = 0.55f
    private var mirrorDistance = 1.0f
    private var calibrationSent = false
    private var selectedPoseIndex = 0
    // Send at ~30fps to server — matches camera rate, minimises frame-skip aliasing
    private var lastServerSendMs = 0L
    private var lastMlImageSendFrameIndex = -1
    private var lastMlImageSendMs = 0L
    private val ML_IMAGE_SEND_INTERVAL_FRAMES = 8
    private val ML_IMAGE_SEND_INTERVAL_MS = 350L
    private val SERVER_SEND_INTERVAL_MS = PipelineTiming.SERVER_SEND_INTERVAL_MS

    // Subject tracking
    private var lockedCenter: Pair<Float, Float>? = null
    private var lockedBoundsSize: Pair<Float, Float>? = null

    // Dedicated single thread for server I/O — keeps inference thread free after MediaPipe finishes
    private val serverSendExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val latestServerFrame = AtomicReference<PendingServerFrame?>(null)
    private val serverDrainScheduled = AtomicBoolean(false)

    // ── Async inference decoupling ─────────────────────────────────────────────────────────────────
    // Camera analysis thread deposits the latest frame here and returns IMMEDIATELY.
    // A separate inference thread drains this slot and runs MediaPipe.
    // If MediaPipe is slower than the camera (rare on GPU), the displaced frame is
    // recycled and only the newest frame is ever processed — zero queuing latency.
    private val _pendingFrame = AtomicReference<CapturedCameraFrame?>(null)
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            r.run()
        }.apply { name = "mocap-inference" }
    }

    // ── Pre-allocated per-frame output arrays ──────────────────────────────────────────────────────
    // FloatArray allocations at 60fps → GC pressure → pauses. Reusing fixed-size arrays
    // eliminates this entirely. Thread-safe: only accessed from the single inferenceExecutor thread.
    private val _xNorm     = FloatArray(JOINT_COUNT)
    private val _yNorm     = FloatArray(JOINT_COUNT)
    private val _vis       = FloatArray(JOINT_COUNT)
    // LandmarkData list: reused by clearing each frame; a snapshot copy is made only when
    // the server send is due (avoids 33 object allocations on non-send frames).
    private val _landmarks = ArrayList<LandmarkData>(JOINT_COUNT)

    fun start() {
        // Do NOT eagerly load MediaPipe here — PoseLandmarker.createFromOptions
        // triggers dlopen of libmediapipe_tasks_vision_jni.so which can SIGSEGV
        // on some devices.  Defer to the first onCameraFrame() call so the
        // calibration UI can still appear even if MediaPipe later crashes.
        emitState(PipelineState.IDLE)
    }

    fun stop() {
        poseLandmarker?.close()
        poseLandmarker = null
        // Drain pending frame — recycle bitmap so it's not leaked
        _pendingFrame.getAndSet(null)?.bitmap?.recycle()
        latestServerFrame.set(null)
        inferenceExecutor.shutdown()
        serverSendExecutor.shutdown()
        state = PipelineState.IDLE
    }

    private fun enqueueLatestServerFrame(payload: PendingServerFrame) {
        latestServerFrame.set(payload)
        scheduleServerDrain()
    }

    private fun scheduleServerDrain() {
        if (!serverDrainScheduled.compareAndSet(false, true)) return
        runCatching {
            serverSendExecutor.execute {
                try {
                    while (true) {
                        val next = latestServerFrame.getAndSet(null) ?: break
                        serverClient.sendFrame(
                            frameIndex = next.frameIndex,
                            timestampUs = next.timestampUs,
                            imageWidth = next.imageWidth,
                            imageHeight = next.imageHeight,
                            rotationDegrees = next.rotationDegrees,
                            landmarks = next.landmarks,
                            worldTracking = next.worldTracking,
                            sceneMetrics = next.sceneMetrics,
                            mlImageBase64 = next.mlImage?.jpegBase64,
                            mlImageWidth = next.mlImage?.width,
                            mlImageHeight = next.mlImage?.height,
                            mlImageSourceWidth = next.mlImage?.sourceWidth,
                            mlImageSourceHeight = next.mlImage?.sourceHeight,
                            mlImageCropLeft = next.mlImage?.cropLeft,
                            mlImageCropTop = next.mlImage?.cropTop,
                            mlImageCropWidth = next.mlImage?.cropWidth,
                            mlImageCropHeight = next.mlImage?.cropHeight,
                            mlImageJpegQuality = next.mlImage?.jpegQuality,
                            mlImageCropPadRatio = next.mlImage?.cropPadRatio,
                        )
                    }
                } finally {
                    serverDrainScheduled.set(false)
                    if (latestServerFrame.get() != null) scheduleServerDrain()
                }
            }
        }.onFailure {
            serverDrainScheduled.set(false)
        }
    }

    fun setMirrorDistance(distance: Float) {
        mirrorDistance = distance
        if (serverClient.isConnected) {
            serverClient.sendMirrorDistance(distance)
        }
    }

    private fun shouldAttachMlImage(frameIndex: Int, nowMs: Long): Boolean {
        return lastMlImageSendFrameIndex < 0 ||
            frameIndex - lastMlImageSendFrameIndex >= ML_IMAGE_SEND_INTERVAL_FRAMES ||
            nowMs - lastMlImageSendMs >= ML_IMAGE_SEND_INTERVAL_MS
    }

    /**
     * Send camera calibration to server and start bootstrap.
     * Called after SETUP & CALIBRATE button is pressed.
     */
    fun beginCalibration(imageWidth: Int, imageHeight: Int) {
        Log.i(TAG, "beginCalibration: ${imageWidth}x${imageHeight}")
        val json = runCatching { JSONObject(intrinsicsJsonProvider()) }.getOrNull()
        val intrinsics = cameraIntrinsicsFromJsonScaled(json, imageWidth, imageHeight)

        Log.i(TAG, "Sending calibration: fx=${intrinsics.fx} fy=${intrinsics.fy} cx=${intrinsics.cx} cy=${intrinsics.cy} mirror=$mirrorDistance")
        serverClient.sendCalibration(
            fx = intrinsics.fx.toDouble(),
            fy = intrinsics.fy.toDouble(),
            cx = intrinsics.cx.toDouble(),
            cy = intrinsics.cy.toDouble(),
            imageWidth = intrinsics.imageWidth,
            imageHeight = intrinsics.imageHeight,
            mirrorDistance = mirrorDistance,
        )
        calibrationSent = true
        bootstrapFramesSent = 0
        emitState(PipelineState.CALIBRATING)
    }

    /**
     * Called when server confirms calibration.
     */
    fun onCalibrationConfirmed() {
        emitState(PipelineState.BOOTSTRAPPING)
    }

    /**
     * Called when server confirms bootstrap complete.
     */
    fun onBootstrapComplete() {
        emitState(PipelineState.CAPTURING)
    }

    /**
     * Deposit the latest camera frame and return IMMEDIATELY.
     * The inference thread picks it up asynchronously — the camera analysis thread
     * is never blocked waiting for MediaPipe GPU inference to complete.
     * If a frame is already pending (inference is behind), it is displaced and
     * its bitmap recycled, so we always process the freshest available frame.
     */
    fun onCameraFrame(frame: CapturedCameraFrame) {
        _pendingFrame.getAndSet(frame)?.bitmap?.recycle()
        inferenceExecutor.execute {
            val f = _pendingFrame.getAndSet(null) ?: return@execute
            processFrame(f)
        }
    }

    /** All MediaPipe work runs here — on the dedicated inference thread. */
    private fun processFrame(frame: CapturedCameraFrame) {
        if (frameIndex % 30 == 0) {
            Log.d(TAG, "onCameraFrame #$frameIndex state=$state ${frame.width}x${frame.height}")
        }
        val landmarker = ensurePoseLandmarker() ?: return

        val bitmap = frame.bitmap
        val inferenceBitmap = bitmap.scaledForPoseInference()
        val mpImage = BitmapImageBuilder(inferenceBitmap).build()
        // ARCore owns capture now; let MediaPipe handle any frame rotation metadata.
        // Output landmarks are in the coordinate space of the rotated (display-upright) image.
        val options = ImageProcessingOptions.builder()
            .setRotationDegrees(frame.rotationDegrees)
            .build()

        val result = runCatching {
            landmarker.detectForVideo(mpImage, options, frame.timestampUs / 1000L)
        }.getOrElse {
            Log.e(TAG, "MediaPipe detection failed", it)
            if (inferenceBitmap !== bitmap) inferenceBitmap.recycle()
            bitmap.recycle()
            return
        }
        if (inferenceBitmap !== bitmap) inferenceBitmap.recycle()

        // Single pass: build UI arrays + LandmarkData list simultaneously (no second 33-joint iteration)
        val poses = result.landmarks()
        if (poses.isEmpty()) {
            bitmap.recycle()
            listener.onNoPoseDetected()
            return
        }
        val poseIdx = selectBestPose(result) ?: 0
        val lms = poses[poseIdx]
        if (lms.size < JOINT_COUNT) {
            bitmap.recycle()
            listener.onNoPoseDetected()
            return
        }

        // Reuse pre-allocated arrays — zero allocations on the hot path
        val worldPose = result.worldLandmarks().getOrNull(poseIdx)
        val hasWorld = worldPose != null && worldPose.size >= JOINT_COUNT
        // World arrays: allocated per-frame because listener stores these references directly
        val xWorld = if (hasWorld) FloatArray(JOINT_COUNT) else null
        val yWorld = if (hasWorld) FloatArray(JOINT_COUNT) else null
        val zWorld = if (hasWorld) FloatArray(JOINT_COUNT) else null

        _landmarks.clear()
        val imageToViewTransform = ImageToViewTransform.fallbackForRotation(frame.rotationDegrees)
        val rotatedWidth = if (frame.rotationDegrees % 180 != 0) bitmap.height else bitmap.width
        val rotatedHeight = if (frame.rotationDegrees % 180 != 0) bitmap.width else bitmap.height
        for (i in 0 until JOINT_COUNT) {
            val p = lms[i]
            val v = readVisibility(p)
            val pres = readPresence(p)
            val imageX = p.x().coerceIn(0f, 1f)
            val imageY = p.y().coerceIn(0f, 1f)
            _xNorm[i] = imageX
            _yNorm[i] = imageY
            _vis[i]   = v
            if (hasWorld) {
                val w = worldPose!![i]
                xWorld!![i] = w.x(); yWorld!![i] = w.y(); zWorld!![i] = w.z()
                _landmarks.add(LandmarkData(
                    x = imageX * rotatedWidth, y = imageY * rotatedHeight, z = p.z(),
                    xMetric = w.x(), yMetric = w.y(), zMetric = w.z(),
                    visibility = v, presence = pres, confidence = min(v, pres),
                ))
            } else {
                _landmarks.add(LandmarkData(
                    x = imageX * rotatedWidth, y = imageY * rotatedHeight, z = p.z(),
                    xMetric = 0f, yMetric = 0f, zMetric = 0f,
                    visibility = v, presence = pres, confidence = min(v, pres),
                ))
            }
        }
        // Rotate MediaPipe coordinates into the same display-upright landmark basis
        // used by the older stable client. ARCore's preview texture transform may
        // include viewport/crop details and must not drive the landmark math.
        rotateLandmarksToDisplay(_xNorm, _yNorm, frame.rotationDegrees)
        // Bitmap data fully extracted — release immediately to cut GC pressure
        val visualTopScan = estimateVisualTopFromSegmentation(
            result = result,
            poseIndex = poseIdx,
            rotationDegrees = frame.rotationDegrees,
            xNorm = _xNorm,
            yNorm = _yNorm,
            visibility = _vis,
        ) ?: estimateVisualTopFromLandmarks(_xNorm, _yNorm, _vis)
        listener.onLandmarksDetected(
            _xNorm,
            _yNorm,
            _vis,
            zWorld,
            xWorld,
            yWorld,
            rotatedWidth,
            rotatedHeight,
            frame.worldTracking,
            visualTopScan?.yNorm ?: Float.NaN,
            visualTopScan?.confidence ?: Float.NaN,
        )
        val rawOutboundLandmarks = ArrayList(_landmarks)
        val outboundLandmarks = listener.prepareServerLandmarks(
            rawOutboundLandmarks,
            frame.width,
            frame.height,
            frame.rotationDegrees,
            imageToViewTransform,
        )
            ?: rawOutboundLandmarks
        val sceneMetrics = listener.prepareServerSceneMetrics(frame.worldTracking)

        when (state) {
            PipelineState.BOOTSTRAPPING -> {
                serverClient.sendBootstrapFrame(outboundLandmarks)
                bootstrapFramesSent++
            }
            PipelineState.CAPTURING -> {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastServerSendMs >= SERVER_SEND_INTERVAL_MS) {
                    lastServerSendMs = nowMs
                    val fi = frameIndex++
                    val ts = frame.timestampUs
                    val iw = frame.width
                    val ih = frame.height
                    val mlImage = if (shouldAttachMlImage(fi, nowMs)) {
                        lastMlImageSendFrameIndex = fi
                        lastMlImageSendMs = nowMs
                        val mlCropLandmarks = listener.prepareMlImageCropLandmarks(_xNorm, _yNorm, _vis)
                        buildMlImagePayload(
                            bitmap = bitmap,
                            rotationDegrees = frame.rotationDegrees,
                            xNorm = mlCropLandmarks?.xNorm ?: _xNorm,
                            yNorm = mlCropLandmarks?.yNorm ?: _yNorm,
                            visibility = mlCropLandmarks?.visibility ?: _vis,
                            requireVisibleLandmarks = mlCropLandmarks == null,
                        )
                    } else {
                        null
                    }
                    listener.onServerFrameQueued(
                        frameIndex = fi,
                        timestampUs = ts,
                        transportHint = if (serverClient.isRtcReady) "webrtc" else "socketio",
                    )
                    enqueueLatestServerFrame(
                        PendingServerFrame(
                            frameIndex = fi,
                            timestampUs = ts,
                            imageWidth = iw,
                            imageHeight = ih,
                            rotationDegrees = frame.rotationDegrees,
                            landmarks = ArrayList(outboundLandmarks),
                            worldTracking = frame.worldTracking,
                            sceneMetrics = sceneMetrics,
                            mlImage = mlImage,
                        )
                    )
                }
            }
            PipelineState.CALIBRATING -> {
                // First frame after calibration → treat as bootstrap
                if (calibrationSent) {
                    serverClient.sendBootstrapFrame(outboundLandmarks)
                    bootstrapFramesSent++
                    emitState(PipelineState.BOOTSTRAPPING)
                }
            }
            else -> { /* idle, wait */ }
        }
        bitmap.recycle()
    }

    private fun selectBestPose(result: PoseLandmarkerResult): Int? {
        val poses = result.landmarks()
        if (poses.isEmpty()) return null
        if (poses.size == 1) return 0

        var bestIdx = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 0 until min(poses.size, MAX_TRACKED_POSES)) {
            val pose = poses[i]
            if (pose.size < 25) continue

            var confSum = 0f
            pose.forEach { confSum += max(readVisibility(it), readPresence(it)) }
            val avgConf = confSum / pose.size

            // Prefer centered, confident poses
            val cx = (pose[11].x() + pose[12].x() + pose[23].x() + pose[24].x()) * 0.25f
            val cy = (pose[11].y() + pose[12].y() + pose[23].y() + pose[24].y()) * 0.25f
            val centerDist = Math.sqrt(((cx - 0.5) * (cx - 0.5) + (cy - 0.58) * (cy - 0.58)).toDouble()).toFloat()

            var score = avgConf - centerDist * 0.75f
            if (i == selectedPoseIndex) score += 0.15f

            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun rotateLandmarksToDisplay(xNorm: FloatArray, yNorm: FloatArray, rotationDegrees: Int) {
        val count = minOf(xNorm.size, yNorm.size, JOINT_COUNT)
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> for (i in 0 until count) {
                val x = xNorm[i]
                val y = yNorm[i]
                xNorm[i] = (1f - y).coerceIn(0f, 1f)
                yNorm[i] = x.coerceIn(0f, 1f)
            }
            180 -> for (i in 0 until count) {
                xNorm[i] = (1f - xNorm[i]).coerceIn(0f, 1f)
                yNorm[i] = (1f - yNorm[i]).coerceIn(0f, 1f)
            }
            270 -> for (i in 0 until count) {
                val x = xNorm[i]
                val y = yNorm[i]
                xNorm[i] = y.coerceIn(0f, 1f)
                yNorm[i] = (1f - x).coerceIn(0f, 1f)
            }
        }
    }

    private fun readVisibility(lm: NormalizedLandmark): Float {
        return runCatching { lm.visibility().orElse(0f) }.getOrDefault(0f)
    }

    private fun readPresence(lm: NormalizedLandmark): Float {
        return runCatching { lm.presence().orElse(0f) }.getOrDefault(0f)
    }

    private fun buildMlImagePayload(
        bitmap: Bitmap,
        rotationDegrees: Int,
        xNorm: FloatArray,
        yNorm: FloatArray,
        visibility: FloatArray,
        requireVisibleLandmarks: Boolean,
    ): MlImagePayload? {
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        var count = 0
        var upperCoreCount = 0
        var lowerCoreCount = 0
        for (i in 0 until JOINT_COUNT) {
            val x = xNorm[i]
            val y = yNorm[i]
            val v = visibility[i]
            if (!x.isFinite() || !y.isFinite()) continue
            if (!v.isFinite() || v < ML_CROP_MIN_VISIBILITY) continue
            if (requireVisibleLandmarks && v < 0.20f) continue
            val likelySyntheticClamp =
                v <= SYNTHETIC_FALLBACK_VISIBILITY + 0.015f &&
                    (
                        x <= ML_CROP_CLAMP_EDGE_EPS ||
                            x >= 1f - ML_CROP_CLAMP_EDGE_EPS ||
                            y <= ML_CROP_CLAMP_EDGE_EPS ||
                            y >= 1f - ML_CROP_CLAMP_EDGE_EPS
                    )
            if (likelySyntheticClamp) continue
            minX = min(minX, x.coerceIn(0f, 1f))
            minY = min(minY, y.coerceIn(0f, 1f))
            maxX = max(maxX, x.coerceIn(0f, 1f))
            maxY = max(maxY, y.coerceIn(0f, 1f))
            count++
            if (ML_CROP_UPPER_CORE.contains(i)) upperCoreCount++
            if (ML_CROP_LOWER_CORE.contains(i)) lowerCoreCount++
        }
        if (count < 8 || maxX <= minX || maxY <= minY) return null
        if (upperCoreCount < 2 || lowerCoreCount < 2) return null
        if (maxY - minY < ML_CROP_MIN_BODY_HEIGHT_NORM) return null

        val cropSource = bitmap.toDisplayUpright(rotationDegrees)
        val imageW = cropSource.width
        val imageH = cropSource.height
        val bodyW = (maxX - minX) * imageW
        val bodyH = (maxY - minY) * imageH
        if (bodyW < 32f || bodyH < 48f) {
            if (cropSource !== bitmap) cropSource.recycle()
            return null
        }

        val pad = max(bodyW, bodyH) * ML_CROP_PAD_RATIO
        val left = ((minX * imageW) - pad).toInt().coerceIn(0, imageW - 1)
        val top = ((minY * imageH) - pad).toInt().coerceIn(0, imageH - 1)
        val right = ((maxX * imageW) + pad).toInt().coerceIn(left + 1, imageW)
        val bottom = ((maxY * imageH) + pad).toInt().coerceIn(top + 1, imageH)
        val cropRect = Rect(left, top, right, bottom)
        if (cropRect.width() < 16 || cropRect.height() < 16) {
            if (cropSource !== bitmap) cropSource.recycle()
            return null
        }

        return runCatching {
            val crop = Bitmap.createBitmap(cropSource, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
            val resized = Bitmap.createScaledBitmap(crop, ML_TRANSPORT_IMAGE_SIZE, ML_TRANSPORT_IMAGE_SIZE, true)
            if (crop !== resized) crop.recycle()
            val out = ByteArrayOutputStream(ML_TRANSPORT_IMAGE_SIZE * ML_TRANSPORT_IMAGE_SIZE)
            resized.compress(Bitmap.CompressFormat.JPEG, ML_JPEG_QUALITY, out)
            resized.recycle()
            MlImagePayload(
                jpegBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                width = ML_TRANSPORT_IMAGE_SIZE,
                height = ML_TRANSPORT_IMAGE_SIZE,
                sourceWidth = imageW,
                sourceHeight = imageH,
                cropLeft = cropRect.left,
                cropTop = cropRect.top,
                cropWidth = cropRect.width(),
                cropHeight = cropRect.height(),
                jpegQuality = ML_JPEG_QUALITY,
                cropPadRatio = ML_CROP_PAD_RATIO,
            )
        }.also {
            if (cropSource !== bitmap) cropSource.recycle()
        }.getOrNull()
    }

    private fun Bitmap.toDisplayUpright(rotationDegrees: Int): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return this
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.scaledForPoseInference(): Bitmap {
        val longEdge = max(width, height)
        if (longEdge <= MAX_MEDIAPIPE_INPUT_LONG_EDGE) return this
        val scale = MAX_MEDIAPIPE_INPUT_LONG_EDGE.toFloat() / longEdge.toFloat()
        val scaledWidth = max(1, (width * scale).toInt())
        val scaledHeight = max(1, (height * scale).toInt())
        return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    }

    @Synchronized
    private fun ensurePoseLandmarker(): PoseLandmarker? {
        poseLandmarker?.let { return it }
        Log.i(TAG, "Creating PoseLandmarker (thread=${Thread.currentThread().name})")

        // Try GPU delegate first — 3-5ms vs CPU's 15-20ms on device
        // Falls back to CPU if GPU init fails (old device / no GLES3.1)
        poseLandmarker = tryCreateLandmarker(Delegate.GPU)
            ?: tryCreateLandmarker(Delegate.CPU)
        Log.i(TAG, "PoseLandmarker ready: ${poseLandmarker != null}")
        return poseLandmarker
    }

    private fun tryCreateLandmarker(delegate: Delegate): PoseLandmarker? {
        val baseOptions = runCatching {
            BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET_PATH)
                .setDelegate(delegate)
                .build()
        }.getOrNull() ?: return null

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(MAX_TRACKED_POSES)
            // Accuracy-first live capture: stale tracked landmarks are worse than a
            // brief re-detection because they poison depth reconstruction downstream.
            .setMinPoseDetectionConfidence(0.45f)
            .setMinPosePresenceConfidence(0.45f)
            .setMinTrackingConfidence(0.5f)
            .setOutputSegmentationMasks(OUTPUT_SEGMENTATION_MASKS)
            .build()
        return runCatching {
            PoseLandmarker.createFromOptions(context, options)
        }.onFailure {
            Log.w(TAG, "PoseLandmarker failed with delegate=$delegate: ${it.message}")
        }.getOrNull()
    }

    private fun estimateVisualTopFromSegmentation(
        result: PoseLandmarkerResult,
        poseIndex: Int,
        rotationDegrees: Int,
        xNorm: FloatArray,
        yNorm: FloatArray,
        visibility: FloatArray,
    ): VisualTopScan? {
        if (xNorm.size < JOINT_COUNT || yNorm.size < JOINT_COUNT || visibility.size < JOINT_COUNT) return null
        val masks = result.segmentationMasks().orElse(null) ?: return null
        val mask = masks.getOrNull(poseIndex) ?: masks.firstOrNull() ?: return null
        val width = mask.width
        val height = mask.height
        if (width <= 4 || height <= 4) return null
        val headIndices = intArrayOf(0, 7, 8, 9, 10)
        val visibleHead = buildList<Pair<Float, Float>> {
            for (idx in headIndices) {
                val v = visibility.getOrNull(idx) ?: 0f
                val x = xNorm.getOrNull(idx) ?: Float.NaN
                val y = yNorm.getOrNull(idx) ?: Float.NaN
                if (v > 0.28f && x.isFinite() && y.isFinite()) {
                    add(Pair(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
                }
            }
        }
        if (visibleHead.isEmpty()) return null
        val headCenterX = visibleHead.map { it.first }.average().toFloat().coerceIn(0f, 1f)
        val headTopY = visibleHead.minOf { it.second }
        val shoulderSpan = normalizedSpanForScan(xNorm, yNorm, visibility, 11, 12)
        val headSpan = normalizedSpanForScan(xNorm, yNorm, visibility, 7, 8)
            ?: normalizedSpanForScan(xNorm, yNorm, visibility, 9, 10)
        val halfBand = max(
            0.050f,
            max((shoulderSpan ?: 0f) * 0.58f, (headSpan ?: 0f) * 1.35f),
        ).coerceIn(0.045f, 0.150f)
        val minX = (headCenterX - halfBand).coerceIn(0f, 1f)
        val maxX = (headCenterX + halfBand).coerceIn(0f, 1f)
        val scanBottomY = (headTopY + 0.20f).coerceIn(0f, 0.82f)
        val buffer = runCatching {
            ByteBufferExtractor.extract(mask, MPImage.IMAGE_FORMAT_VEC32F1)
        }.getOrNull() ?: runCatching {
            ByteBufferExtractor.extract(mask)
        }.getOrNull() ?: return null
        val pixelCount = width * height
        val floatBuffer = if (buffer.capacity() >= pixelCount * 4) {
            buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        } else {
            null
        }
        fun maskValue(offset: Int): Float =
            if (floatBuffer != null && floatBuffer.capacity() > offset) {
                floatBuffer.get(offset)
            } else if (buffer.capacity() > offset) {
                (buffer.get(offset).toInt() and 0xff) / 255f
            } else {
                0f
            }
        fun toDisplayNorm(px: Int, py: Int): Pair<Float, Float> {
            val x = (px + 0.5f) / width.toFloat()
            val y = (py + 0.5f) / height.toFloat()
            return when (((rotationDegrees % 360) + 360) % 360) {
                90 -> Pair((1f - y).coerceIn(0f, 1f), x.coerceIn(0f, 1f))
                180 -> Pair((1f - x).coerceIn(0f, 1f), (1f - y).coerceIn(0f, 1f))
                270 -> Pair(y.coerceIn(0f, 1f), (1f - x).coerceIn(0f, 1f))
                else -> Pair(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            }
        }
        var bestY = Float.POSITIVE_INFINITY
        var support = 0
        val step = if (width * height > 180_000) 2 else 1
        for (py in 0 until height step step) {
            for (px in 0 until width step step) {
                val value = maskValue(py * width + px)
                if (value < 0.38f) continue
                val (dx, dy) = toDisplayNorm(px, py)
                if (dx < minX || dx > maxX || dy > scanBottomY) continue
                if (dy < bestY - 0.0025f) {
                    bestY = dy
                    support = 1
                } else if (abs(dy - bestY) <= 0.014f) {
                    support += 1
                }
            }
        }
        if (!bestY.isFinite() || bestY >= headTopY + 0.030f || support < 3) return null
        val liftFromHead = headTopY - bestY
        if (liftFromHead < -0.015f || liftFromHead > 0.140f) return null
        val confidence = (
            0.42f +
                (support / 26f).coerceIn(0f, 0.46f) +
                (liftFromHead / 0.10f).coerceIn(0f, 0.12f)
            ).coerceIn(0f, 1f)
        return VisualTopScan(bestY.coerceIn(0f, 1f), confidence)
    }

    private fun estimateVisualTopFromLandmarks(
        xNorm: FloatArray,
        yNorm: FloatArray,
        visibility: FloatArray,
    ): VisualTopScan? {
        if (xNorm.size < JOINT_COUNT || yNorm.size < JOINT_COUNT || visibility.size < JOINT_COUNT) return null
        val headIndices = intArrayOf(0, 7, 8, 9, 10)
        val visibleHead = headIndices.asIterable().mapNotNull { idx: Int ->
            val v = visibility[idx]
            val y = yNorm[idx]
            if (v > 0.30f && y.isFinite()) y.coerceIn(0f, 1f) to v else null
        }
        if (visibleHead.isEmpty()) return null
        val headTopY = visibleHead.minOf { it.first }
        val headSpan = normalizedSpanForScan(xNorm, yNorm, visibility, 7, 8)
            ?: normalizedSpanForScan(xNorm, yNorm, visibility, 9, 10)
        val shoulderSpan = normalizedSpanForScan(xNorm, yNorm, visibility, 11, 12)
        val crownLift = max(
            0.018f,
            max((headSpan ?: 0f) * 0.45f, (shoulderSpan ?: 0f) * 0.16f),
        ).coerceIn(0.018f, 0.070f)
        val confidence = (
            0.30f + visibleHead.map { it.second }.average().toFloat().coerceIn(0f, 0.45f)
        ).coerceIn(0f, 0.72f)
        return VisualTopScan((headTopY - crownLift).coerceIn(0f, 1f), confidence)
    }

    private fun normalizedSpanForScan(
        xNorm: FloatArray,
        yNorm: FloatArray,
        visibility: FloatArray,
        a: Int,
        b: Int,
    ): Float? {
        val va = visibility.getOrNull(a) ?: 0f
        val vb = visibility.getOrNull(b) ?: 0f
        if (va <= 0.25f || vb <= 0.25f) return null
        val ax = xNorm.getOrNull(a) ?: return null
        val ay = yNorm.getOrNull(a) ?: return null
        val bx = xNorm.getOrNull(b) ?: return null
        val by = yNorm.getOrNull(b) ?: return null
        if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) return null
        val dx = ax - bx
        val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it.isFinite() && it > 1e-4f }
    }

    private fun emitState(newState: PipelineState) {
        state = newState
        listener.onStateChanged(newState)
    }
}

