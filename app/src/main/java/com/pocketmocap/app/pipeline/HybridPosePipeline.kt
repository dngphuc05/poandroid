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
    private val SERVER_SEND_INTERVAL_MS = 33L

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
        val fx = json?.optDouble("fx", imageWidth * 1.2) ?: imageWidth * 1.2
        val fy = json?.optDouble("fy", imageWidth * 1.2) ?: imageWidth * 1.2
        val cx = json?.optDouble("cx", imageWidth * 0.5) ?: imageWidth * 0.5
        val cy = json?.optDouble("cy", imageHeight * 0.5) ?: imageHeight * 0.5

        Log.i(TAG, "Sending calibration: fx=$fx fy=$fy cx=$cx cy=$cy mirror=$mirrorDistance")
        serverClient.sendCalibration(
            fx = fx, fy = fy, cx = cx, cy = cy,
            imageWidth = imageWidth, imageHeight = imageHeight,
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
        val mpImage = BitmapImageBuilder(bitmap).build()
        // ARCore owns capture now; let MediaPipe handle any frame rotation metadata.
        // Output landmarks are in the coordinate space of the rotated (display-upright) image.
        val options = ImageProcessingOptions.builder()
            .setRotationDegrees(frame.rotationDegrees)
            .build()

        val result = runCatching {
            landmarker.detectForVideo(mpImage, options, frame.timestampUs / 1000L)
        }.getOrElse {
            Log.e(TAG, "MediaPipe detection failed", it)
            return
        }

        // Single pass: build UI arrays + LandmarkData list simultaneously (no second 33-joint iteration)
        val poses = result.landmarks()
        if (poses.isEmpty()) {
            listener.onNoPoseDetected()
            return
        }
        val poseIdx = selectBestPose(result) ?: 0
        val lms = poses[poseIdx]
        if (lms.size < JOINT_COUNT) {
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
        val bw = if (frame.rotationDegrees % 180 != 0) bitmap.height else bitmap.width
        val bh = if (frame.rotationDegrees % 180 != 0) bitmap.width else bitmap.height
        for (i in 0 until JOINT_COUNT) {
            val p = lms[i]
            val v = readVisibility(p)
            val pres = readPresence(p)
            _xNorm[i] = p.x()
            _yNorm[i] = p.y()
            _vis[i]   = v
            if (hasWorld) {
                val w = worldPose!![i]
                xWorld!![i] = w.x(); yWorld!![i] = w.y(); zWorld!![i] = w.z()
                _landmarks.add(LandmarkData(
                    x = p.x() * bw, y = p.y() * bh, z = p.z(),
                    xMetric = w.x(), yMetric = w.y(), zMetric = w.z(),
                    visibility = v, presence = pres, confidence = min(v, pres),
                ))
            } else {
                _landmarks.add(LandmarkData(
                    x = p.x() * bw, y = p.y() * bh, z = p.z(),
                    xMetric = 0f, yMetric = 0f, zMetric = 0f,
                    visibility = v, presence = pres, confidence = min(v, pres),
                ))
            }
        }
        // ── Rotate landmark coords from sensor space to display-upright space ─────────
        // MediaPipe's setRotationDegrees() helps model accuracy but output coords are
        // always in the ORIGINAL unrotated sensor frame.  We apply a lossless in-place
        // coord transform (33×2 float ops, ~0μs) to put them in display portrait space.
        if (frame.rotationDegrees != 0) {
            for (i in 0 until JOINT_COUNT) {
                val ox = _xNorm[i]; val oy = _yNorm[i]
                when (frame.rotationDegrees) {
                    90  -> { _xNorm[i] = 1f - oy; _yNorm[i] = ox }
                    180 -> { _xNorm[i] = 1f - ox; _yNorm[i] = 1f - oy }
                    270 -> { _xNorm[i] = oy;       _yNorm[i] = 1f - ox }
                }
            }
        }
        // Bitmap data fully extracted — release immediately to cut GC pressure
        val visualTopScan = estimateVisualTopFromSegmentation(
            result = result,
            poseIndex = poseIdx,
            rotationDegrees = frame.rotationDegrees,
            xNorm = _xNorm,
            yNorm = _yNorm,
            visibility = _vis,
        )
        listener.onLandmarksDetected(
            _xNorm,
            _yNorm,
            _vis,
            zWorld,
            xWorld,
            yWorld,
            bw,
            bh,
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
                        val mlCropLandmarks = listener.prepareMlImageCropLandmarks(_xNorm, _yNorm, _vis)
                        buildMlImagePayload(
                            bitmap = bitmap,
                            rotationDegrees = frame.rotationDegrees,
                            xNorm = mlCropLandmarks?.xNorm ?: _xNorm,
                            yNorm = mlCropLandmarks?.yNorm ?: _yNorm,
                            visibility = mlCropLandmarks?.visibility ?: _vis,
                            requireVisibleLandmarks = mlCropLandmarks == null,
                        )?.also {
                            lastMlImageSendFrameIndex = fi
                            lastMlImageSendMs = nowMs
                        }
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
