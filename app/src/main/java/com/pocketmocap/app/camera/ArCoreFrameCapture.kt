package com.pocketmocap.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Config
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import java.util.EnumSet
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.pocketmocap.app.CapturedCameraFrame
import com.pocketmocap.app.calibration.CloudAnchorEngine
import com.pocketmocap.app.calibration.CloudAnchorResult
import com.pocketmocap.app.tracking.CameraIntrinsics
import com.pocketmocap.app.tracking.DepthMapSnapshot
import com.pocketmocap.app.tracking.ImageToViewTransform
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2

/**
 * ARCore-owned camera capture.
 *
 * ARCore provides the preview texture, camera pose, floor-plane tracking, and the
 * synchronized CPU image that we feed into MediaPipe.
 */
class ArCoreFrameCapture(
    private val context: Context,
    private val onFrame: (CapturedCameraFrame) -> Unit,
) : CloudAnchorEngine {
    companion object {
        private const val TAG = "ArCoreFrameCapture"
        private const val MIN_FRAME_INTERVAL_NS = 16_000_000L
        private const val DEPTH_SAMPLE_INTERVAL_NS = 200_000_000L
        private const val DEPTH_REUSE_WINDOW_NS = 600_000_000L
        private const val STARTUP_CAMERA_HEIGHT_PRIOR_M = 1.17f
        private const val MIN_REASONABLE_CAMERA_HEIGHT_M = 0.20f
        private const val MAX_REASONABLE_CAMERA_HEIGHT_M = 2.50f
        private const val MAX_HIT_BOOTSTRAP_CAMERA_HEIGHT_M = 1.55f
        private const val MAX_SOFT_FLOOR_DRIFT_FROM_PRIOR_M = 0.70f
    }

    private val renderer = Renderer()

    val previewView: GLSurfaceView = GLSurfaceView(context).apply {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    @Volatile private var session: Session? = null
    @Volatile private var running = false
    @Volatile private var lastSentTimestampNs = 0L
    @Volatile private var manualCameraHeightMeters = STARTUP_CAMERA_HEIGHT_PRIOR_M
    @Volatile private var lockedCameraHeightMeters = Float.NaN
    @Volatile private var pendingCameraHeightMeters = Float.NaN
    @Volatile private var pendingCameraHeightFrames = 0
    @Volatile private var latestCameraPose: Pose? = null
    @Volatile private var latestDepthMapSnapshot: DepthMapSnapshot? = null
    private val depthFrameSampler = DepthFrameSampler(
        sampleIntervalNs = DEPTH_SAMPLE_INTERVAL_NS,
        reuseWindowNs = DEPTH_REUSE_WINDOW_NS,
    )

    private data class FloorAnchor(
        val point: FloatArray,
        val normal: FloatArray,
        val confidence: Float,
        val source: String,
    )

    fun start(lifecycleOwner: LifecycleOwner) {
        if (running) return
        running = true
        try {
            val arSession = Session(context)
            val filter = CameraConfigFilter(arSession).apply {
                targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60)
            }
            val cameraConfigs = arSession.getSupportedCameraConfigs(filter)
            if (cameraConfigs.isNotEmpty()) {
                arSession.cameraConfig = cameraConfigs[0]
                Log.i(TAG, "Enabled 60fps ARCore camera mode")
            }

            val config = Config(arSession).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
                if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    depthMode = Config.DepthMode.AUTOMATIC
                }
            }
            arSession.configure(config)
            session = arSession
            arSession.resume()
            previewView.onResume()
            previewView.queueEvent {
                renderer.bindSessionTexture(arSession)
            }
            Log.i(TAG, "ARCore capture started")
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "Failed to start ARCore capture", e)
        }
    }

    fun switchCamera(useFront: Boolean) {
        Log.w(TAG, "ARCore capture uses the AR-supported back camera; front camera ignored")
    }

    fun setManualCameraHeightMeters(heightMeters: Float) {
        val clamped = heightMeters.coerceIn(MIN_REASONABLE_CAMERA_HEIGHT_M, MAX_REASONABLE_CAMERA_HEIGHT_M)
        manualCameraHeightMeters = clamped
        lockedCameraHeightMeters = clamped
        pendingCameraHeightMeters = Float.NaN
        pendingCameraHeightFrames = 0
        latestCameraPose = null
    }

    override fun hostSharedAnchor(onResult: (CloudAnchorResult) -> Unit) {
        previewView.queueEvent {
            val arSession = session
            val pose = latestCameraPose
            if (arSession == null || pose == null) {
                onResult(CloudAnchorResult(state = "failed", errorMessage = "ARCore is not tracking yet"))
                return@queueEvent
            }
            runCatching {
                val localAnchor = arSession.createAnchor(pose)
                val featureQuality = arSession.estimateFeatureMapQualityForHosting(pose)
                val qualityScore = when (featureQuality) {
                    Session.FeatureMapQuality.GOOD -> 0.92f
                    Session.FeatureMapQuality.SUFFICIENT -> 0.74f
                    Session.FeatureMapQuality.INSUFFICIENT -> 0.42f
                    else -> 0.0f
                }
                onResult(CloudAnchorResult(state = "hosting", quality = qualityScore))
                arSession.hostCloudAnchorAsync(localAnchor, 1) { cloudAnchorId, cloudState ->
                    if (cloudState == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS && cloudAnchorId.isNotBlank()) {
                        onResult(
                            CloudAnchorResult(
                                state = "hosted",
                                sharedAnchorId = cloudAnchorId,
                                position = pose.translation,
                                rotation = pose.rotationQuaternion,
                                quality = qualityScore,
                            )
                        )
                    } else {
                        onResult(
                            CloudAnchorResult(
                                state = "failed",
                                quality = qualityScore,
                                errorMessage = "Cloud Anchor hosting failed: ${cloudState.name}",
                            )
                        )
                    }
                    runCatching { localAnchor.detach() }
                }
            }.onFailure {
                onResult(CloudAnchorResult(state = "failed", errorMessage = it.message ?: "Cloud Anchor hosting failed"))
            }
        }
    }

    override fun resolveSharedAnchor(sharedAnchorId: String, onResult: (CloudAnchorResult) -> Unit) {
        val anchorId = sharedAnchorId.trim()
        if (anchorId.isBlank()) {
            onResult(CloudAnchorResult(state = "failed", errorMessage = "Missing Cloud Anchor ID"))
            return
        }
        previewView.queueEvent {
            val arSession = session
            if (arSession == null) {
                onResult(CloudAnchorResult(state = "failed", sharedAnchorId = anchorId, errorMessage = "ARCore is not running"))
                return@queueEvent
            }
            runCatching {
                onResult(CloudAnchorResult(state = "resolving", sharedAnchorId = anchorId))
                arSession.resolveCloudAnchorAsync(anchorId) { anchor, cloudState ->
                    if (cloudState == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                        val pose = anchor.pose
                        onResult(
                            CloudAnchorResult(
                                state = "resolved",
                                sharedAnchorId = anchorId,
                                position = pose.translation,
                                rotation = pose.rotationQuaternion,
                                quality = 0.82f,
                            )
                        )
                    } else {
                        onResult(
                            CloudAnchorResult(
                                state = "failed",
                                sharedAnchorId = anchorId,
                                errorMessage = "Cloud Anchor resolve failed: ${cloudState.name}",
                            )
                        )
                    }
                    runCatching { anchor.detach() }
                }
            }.onFailure {
                onResult(CloudAnchorResult(state = "failed", sharedAnchorId = anchorId, errorMessage = it.message ?: "Cloud Anchor resolve failed"))
            }
        }
    }

    fun stop() {
        running = false
        val oldSession = session
        session = null
        runCatching { oldSession?.pause() }
        runCatching { oldSession?.close() }
        previewView.onPause()
        lockedCameraHeightMeters = Float.NaN
        pendingCameraHeightMeters = Float.NaN
        pendingCameraHeightFrames = 0
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        private var textureId = 0
        private var program = 0
        private var positionAttrib = 0
        private var texCoordAttrib = 0
        private var textureUniform = 0
        private var sessionTextureBound = false
        @Volatile private var viewportWidth = 0
        @Volatile private var viewportHeight = 0

        private val quadVertices: FloatBuffer = floatBufferOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        private val quadTexCoords: FloatBuffer = floatBufferOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            textureId = createExternalTexture()
            program = createCameraProgram()
            positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
            texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
            textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
            session?.let(::bindSessionTexture)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            GLES20.glViewport(0, 0, width, height)
            session?.setDisplayGeometry(Surface.ROTATION_0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val arSession = session ?: return
            if (!sessionTextureBound) {
                bindSessionTexture(arSession)
            }

            val frame = try {
                arSession.update()
            } catch (e: Exception) {
                Log.w(TAG, "ARCore update skipped: ${e.message}")
                return
            }

            updateCameraTextureCoordinates(frame)
            drawCameraTexture()
            maybeEmitCameraFrame(arSession, frame)
        }

        fun bindSessionTexture(arSession: Session) {
            if (textureId == 0) return
            runCatching {
                arSession.setCameraTextureName(textureId)
                sessionTextureBound = true
            }.onFailure {
                Log.w(TAG, "Could not bind ARCore camera texture: ${it.message}")
            }
        }

        private fun drawCameraTexture() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureUniform, 0)
            GLES20.glEnableVertexAttribArray(positionAttrib)
            GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
            GLES20.glEnableVertexAttribArray(texCoordAttrib)
            GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionAttrib)
            GLES20.glDisableVertexAttribArray(texCoordAttrib)
        }

        private fun updateCameraTextureCoordinates(frame: Frame) {
            val ndc = floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            )
            val tex = FloatArray(8)
            runCatching {
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    ndc,
                    Coordinates2d.TEXTURE_NORMALIZED,
                    tex,
                )
                quadTexCoords.position(0)
                quadTexCoords.put(tex)
                quadTexCoords.position(0)
            }
        }

        fun currentViewportSize(): Pair<Int, Int> = viewportWidth to viewportHeight
    }

    private val frameExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val isProcessingImage = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun maybeEmitCameraFrame(arSession: Session, frame: Frame) {
        val timestampNs = frame.timestamp
        if (timestampNs <= 0L || timestampNs == lastSentTimestampNs) return
        if (timestampNs - lastSentTimestampNs < MIN_FRAME_INTERVAL_NS) return

        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire ARCore camera image: ${e.message}")
            return
        }

        val depthImage = if (depthFrameSampler.shouldRequestDepth(timestampNs)) {
            try {
                frame.acquireDepthImage16Bits()
            } catch (_: NotYetAvailableException) {
                null
            } catch (e: Exception) {
                Log.w(TAG, "Depth image unavailable: ${e.message}")
                null
            }
        } else {
            null
        }

        // Build frame metadata synchronously on GL thread to safely access ARCore Frame/Camera.
        val snapshotWithoutDepth = buildWorldTrackingSnapshot(arSession, frame, image, null)
        val imageToViewTransform = buildImageToViewTransform(frame)

        if (isProcessingImage.compareAndSet(false, true)) {
            lastSentTimestampNs = timestampNs
            frameExecutor.execute {
                try {
                    val bitmap = yuv420ImageToBitmap(image) ?: return@execute
                    val depthMap = depthImage?.let { extractPortraitDepthMap(it) }
                    if (depthMap != null) {
                        latestDepthMapSnapshot = depthMap
                        depthFrameSampler.markDepthSampleSucceeded(timestampNs)
                    }
                    val reusableDepthMap = latestDepthMapSnapshot
                        .takeIf { depthFrameSampler.canReuseDepth(timestampNs) }
                    val snapshot = snapshotWithoutDepth.copy(depthMap = depthMap ?: reusableDepthMap)
                    onFrame(
                        CapturedCameraFrame(
                            bitmap = bitmap,
                            width = bitmap.width,
                            height = bitmap.height,
                            timestampUs = timestampNs / 1000L,
                            rotationDegrees = 90,
                            imageToViewTransform = imageToViewTransform,
                            worldTracking = snapshot,
                        )
                    )
                } finally {
                    image.close()
                    depthImage?.close()
                    isProcessingImage.set(false)
                }
            }
        } else {
            image.close()
            depthImage?.close()
        }
    }

    private fun buildImageToViewTransform(frame: Frame): ImageToViewTransform {
        val imageCorners = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
        )
        val viewCorners = FloatArray(imageCorners.size)
        val transform = runCatching {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_NORMALIZED,
                imageCorners,
                Coordinates2d.VIEW_NORMALIZED,
                viewCorners,
            )
            ImageToViewTransform.fromImageCorners(
                topLeftX = viewCorners[0],
                topLeftY = viewCorners[1],
                topRightX = viewCorners[2],
                topRightY = viewCorners[3],
                bottomLeftX = viewCorners[4],
                bottomLeftY = viewCorners[5],
            )
        }.getOrNull()
        return transform?.takeIf { it.isUsable() } ?: ImageToViewTransform.fallbackForRotation(90)
    }

    private fun buildWorldTrackingSnapshot(
        arSession: Session,
        frame: Frame,
        image: Image,
        depthMap: DepthMapSnapshot?,
    ): WorldTrackingSnapshot {
        val camera = frame.camera
        val pose = camera.pose
        latestCameraPose = pose
        val translation = pose.translation
        val rotation = pose.rotationQuaternion
        val floorAnchor = selectFloorAnchor(arSession, frame, translation)
        val floorY = floorAnchor?.point?.getOrNull(1)
        val rawCameraHeight = if (floorY != null) {
            pose.ty() - floorY
        } else {
            Float.NaN
        }
        val manualCameraHeight = manualCameraHeightMeters
            .takeIf { it.isFinite() && it in MIN_REASONABLE_CAMERA_HEIGHT_M..MAX_REASONABLE_CAMERA_HEIGHT_M }
        val cameraHeight = manualCameraHeight
            ?: rawCameraHeight
                .takeIf { it.isFinite() }
                ?.coerceIn(MIN_REASONABLE_CAMERA_HEIGHT_M, MAX_REASONABLE_CAMERA_HEIGHT_M)
            ?: Float.NaN
        val pitch = cameraPitchDegrees(rotation)
        val tracking = camera.trackingState
        val cpuIntrinsics = camera.imageIntrinsics
        val focal = cpuIntrinsics.focalLength
        val principal = cpuIntrinsics.principalPoint
        val dimensions = cpuIntrinsics.imageDimensions
        // MediaPipe/display coordinates are portrait-upright (rotationDegrees=90).
        // Rotate ARCore CPU-image intrinsics into the same coordinate frame.
        val displayIntrinsics = CameraIntrinsics(
            fx = focal[1],
            fy = focal[0],
            cx = dimensions[1].toFloat() - principal[1],
            cy = principal[0],
            imageWidth = dimensions[1],
            imageHeight = dimensions[0],
        )

        return WorldTrackingSnapshot(
            timestampUs = frame.timestamp / 1000L,
            source = if (floorAnchor != null && tracking == TrackingState.TRACKING) floorAnchor.source else "arcore",
            trackingState = tracking.name.lowercase(),
            cameraPosition = floatArrayOf(translation[0], translation[1], translation[2]),
            cameraRotation = floatArrayOf(rotation[0], rotation[1], rotation[2], rotation[3]),
            groundPoint = floorAnchor?.point,
            groundNormal = floorAnchor?.normal,
            cameraHeightMeters = cameraHeight,
            subjectDistanceMeters = Float.NaN,
            subjectHeightMeters = Float.NaN,
            lateralOffsetMeters = Float.NaN,
            floorPitchDegrees = pitch,
            confidence = when {
                floorAnchor != null && tracking == TrackingState.TRACKING -> floorAnchor.confidence
                tracking == TrackingState.TRACKING -> 0.70f
                else -> 0.30f
            },
            rawCameraHeightMeters = rawCameraHeight,
            floorSource = floorAnchor?.source ?: "",
            floorLockState = when {
                floorAnchor == null -> "none"
                !lockedCameraHeightMeters.isFinite() -> "acquiring"
                floorAnchor.source.endsWith("_held") -> "holding"
                else -> "locked"
            },
            intrinsics = displayIntrinsics,
            depthMap = depthMap,
        )
    }

    private fun acquirePortraitDepthMap(frame: Frame): DepthMapSnapshot? {
        return try {
            frame.acquireDepthImage16Bits().use { depthImage ->
                extractPortraitDepthMap(depthImage)
            }
        } catch (_: NotYetAvailableException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Depth image unavailable: ${e.message}")
            null
        }
    }

    private fun extractPortraitDepthMap(depthImage: Image): DepthMapSnapshot? {
        val plane = depthImage.planes.firstOrNull() ?: return null
        val srcWidth = depthImage.width
        val srcHeight = depthImage.height
        if (srcWidth <= 1 || srcHeight <= 1) return null
        val dstWidth = srcHeight
        val dstHeight = srcWidth
        val out = ShortArray(dstWidth * dstHeight)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return null
        val rowBuffer = ByteArray(rowStride)
        val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        for (sy in 0 until srcHeight) {
            val rowStart = sy * rowStride
            if (rowStart + rowStride > buffer.capacity()) break
            buffer.position(rowStart)
            buffer.get(rowBuffer, 0, rowStride)
            for (sx in 0 until srcWidth) {
                val offset = sx * pixelStride
                if (offset + 1 >= rowStride) continue
                val depthMm = ((rowBuffer[offset + 1].toInt() and 0xFF) shl 8) or
                    (rowBuffer[offset].toInt() and 0xFF)
                val dx = srcHeight - 1 - sy
                val dy = sx
                out[dy * dstWidth + dx] = depthMm.toShort()
            }
        }
        return DepthMapSnapshot(
            width = dstWidth,
            height = dstHeight,
            depthMm = out,
        )
    }

    private fun selectFloorAnchor(arSession: Session, frame: Frame, cameraPosition: FloatArray): FloorAnchor? {
        val cameraY = cameraPosition.getOrNull(1) ?: return null
        selectFloorPlane(arSession, cameraY)?.let { plane ->
            return stabilizeFloorAnchor(
                cameraY,
                FloorAnchor(
                point = plane.centerPose.translation,
                normal = floatArrayOf(0f, 1f, 0f),
                confidence = 0.90f,
                source = "arcore_floor",
                )
            )
        }
        selectFloorHit(frame, cameraY)?.let { return stabilizeFloorAnchor(cameraY, it) }

        // Startup path: formal ARCore planes often need parallax before they appear.
        // Keep this soft and handheld-height biased so it cannot masquerade as a
        // real floor several meters below the phone.
        return stabilizeFloorAnchor(
            cameraY,
            FloorAnchor(
            point = floatArrayOf(
                cameraPosition.getOrNull(0) ?: 0f,
                cameraY - currentCameraHeightPrior(),
                cameraPosition.getOrNull(2) ?: 0f,
            ),
            normal = floatArrayOf(0f, 1f, 0f),
            confidence = 0.58f,
            source = "arcore_floor_provisional",
            )
        )
    }

    private fun selectFloorPlane(arSession: Session, cameraY: Float): Plane? {
        var best: Plane? = null
        var bestScore = Float.POSITIVE_INFINITY
        val priorHeight = currentCameraHeightPrior()
        for (plane in arSession.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.subsumedBy != null) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val dy = cameraY - plane.centerPose.ty()
            if (dy !in MIN_REASONABLE_CAMERA_HEIGHT_M..MAX_REASONABLE_CAMERA_HEIGHT_M) continue
            val area = (plane.extentX * plane.extentZ).coerceAtLeast(0f)
            val score = abs(dy - priorHeight) * 0.70f +
                abs(dy - STARTUP_CAMERA_HEIGHT_PRIOR_M) * if (lockedCameraHeightMeters.isFinite()) 0.04f else 0.18f -
                area.coerceIn(0f, 1.6f) * 0.10f
            if (score < bestScore) {
                bestScore = score
                best = plane
            }
        }
        return best
    }

    private fun selectFloorHit(frame: Frame, cameraY: Float): FloorAnchor? {
        val (width, height) = renderer.currentViewportSize()
        if (width <= 1 || height <= 1) return null

        val samples = arrayOf(
            0.50f to 0.86f,
            0.35f to 0.84f,
            0.65f to 0.84f,
            0.50f to 0.72f,
            0.42f to 0.76f,
            0.58f to 0.76f,
        )
        var bestPoint: FloatArray? = null
        var bestConfidence = 0f
        var bestScore = Float.POSITIVE_INFINITY
        val priorHeight = currentCameraHeightPrior()

        for ((nx, ny) in samples) {
            val hits = runCatching { frame.hitTest(nx * width, ny * height) }.getOrNull() ?: continue
            for (hit in hits) {
                val candidate = floorPointFromHit(hit, cameraY) ?: continue
                val dy = cameraY - candidate.first[1]
                val score = abs(dy - priorHeight) * 0.78f +
                    abs(dy - STARTUP_CAMERA_HEIGHT_PRIOR_M) * if (lockedCameraHeightMeters.isFinite()) 0.05f else 0.20f +
                    candidate.second
                if (score < bestScore) {
                    bestScore = score
                    bestPoint = candidate.first
                    bestConfidence = candidate.third
                }
            }
        }

        val point = bestPoint ?: return null
        return FloorAnchor(
            point = point,
            normal = floatArrayOf(0f, 1f, 0f),
            confidence = bestConfidence,
            source = "arcore_floor_hit",
        )
    }

    private fun stabilizeFloorAnchor(cameraY: Float, candidate: FloorAnchor): FloorAnchor {
        manualCameraHeightMeters
            .takeIf { it.isFinite() && it in MIN_REASONABLE_CAMERA_HEIGHT_M..MAX_REASONABLE_CAMERA_HEIGHT_M }
            ?.let { manualHeight ->
                lockedCameraHeightMeters = manualHeight
                pendingCameraHeightMeters = Float.NaN
                pendingCameraHeightFrames = 0
                return candidate.copy(
                    point = floatArrayOf(candidate.point[0], cameraY - manualHeight, candidate.point[2]),
                    source = "${candidate.source}_manual_height",
                )
            }

        val rawHeight = cameraY - candidate.point[1]
        val isRealPlane = candidate.source == "arcore_floor"
        val isSoftFloor = candidate.source == "arcore_floor_hit" || candidate.source == "arcore_floor_provisional"
        if (candidate.source == "arcore_floor_provisional") {
            val held = currentCameraHeightPrior()
            if (lockedCameraHeightMeters.isFinite() && abs(lockedCameraHeightMeters - STARTUP_CAMERA_HEIGHT_PRIOR_M) > 0.55f) {
                lockedCameraHeightMeters = lockedCameraHeightMeters * 0.94f + STARTUP_CAMERA_HEIGHT_PRIOR_M * 0.06f
            }
            return candidate.copy(
                point = floatArrayOf(candidate.point[0], cameraY - held, candidate.point[2]),
                confidence = minOf(candidate.confidence, 0.42f),
                source = "${candidate.source}_held",
            )
        }
        if (!rawHeight.isFinite() || rawHeight !in MIN_REASONABLE_CAMERA_HEIGHT_M..MAX_REASONABLE_CAMERA_HEIGHT_M) {
            val held = currentCameraHeightPrior()
            return candidate.copy(
                point = floatArrayOf(candidate.point[0], cameraY - held, candidate.point[2]),
                confidence = minOf(candidate.confidence, 0.42f),
                source = "${candidate.source}_held",
            )
        }
        val locked = lockedCameraHeightMeters
        if (!locked.isFinite()) {
            val canBootstrapFromSoftFloor =
                isSoftFloor &&
                    rawHeight <= MAX_HIT_BOOTSTRAP_CAMERA_HEIGHT_M &&
                    abs(rawHeight - STARTUP_CAMERA_HEIGHT_PRIOR_M) <= MAX_SOFT_FLOOR_DRIFT_FROM_PRIOR_M &&
                    candidate.confidence >= 0.82f
            if (isRealPlane && candidate.confidence >= 0.76f) {
                lockedCameraHeightMeters = rawHeight
                pendingCameraHeightFrames = 0
                pendingCameraHeightMeters = Float.NaN
                return candidate
            }
            if (canBootstrapFromSoftFloor) {
                if (!pendingCameraHeightMeters.isFinite() || abs(rawHeight - pendingCameraHeightMeters) > 0.10f) {
                    pendingCameraHeightMeters = rawHeight
                    pendingCameraHeightFrames = 1
                } else {
                    pendingCameraHeightMeters = pendingCameraHeightMeters * 0.75f + rawHeight * 0.25f
                    pendingCameraHeightFrames += 1
                }
                if (pendingCameraHeightFrames >= 8) {
                    lockedCameraHeightMeters = pendingCameraHeightMeters
                    pendingCameraHeightFrames = 0
                    pendingCameraHeightMeters = Float.NaN
                    return candidate.copy(
                        point = floatArrayOf(candidate.point[0], cameraY - lockedCameraHeightMeters, candidate.point[2]),
                        confidence = minOf(candidate.confidence, 0.70f),
                        source = "${candidate.source}_held",
                    )
                }
            }
            val held = currentCameraHeightPrior()
            return candidate.copy(
                point = floatArrayOf(candidate.point[0], cameraY - held, candidate.point[2]),
                confidence = minOf(candidate.confidence, 0.42f),
                source = "${candidate.source}_held",
            )
        }
        val jump = abs(rawHeight - locked)
        if (jump <= 0.18f) {
            lockedCameraHeightMeters = locked * 0.88f + rawHeight * 0.12f
            pendingCameraHeightFrames = 0
            pendingCameraHeightMeters = Float.NaN
            return candidate
        }
        val softFloorTooFarFromLock = isSoftFloor && jump > MAX_SOFT_FLOOR_DRIFT_FROM_PRIOR_M
        if (!softFloorTooFarFromLock && candidate.confidence >= 0.86f) {
            if (!pendingCameraHeightMeters.isFinite() || abs(rawHeight - pendingCameraHeightMeters) > 0.12f) {
                pendingCameraHeightMeters = rawHeight
                pendingCameraHeightFrames = 1
            } else {
                pendingCameraHeightMeters = pendingCameraHeightMeters * 0.75f + rawHeight * 0.25f
                pendingCameraHeightFrames += 1
            }
            if (pendingCameraHeightFrames >= 10) {
                lockedCameraHeightMeters = pendingCameraHeightMeters
                pendingCameraHeightFrames = 0
                pendingCameraHeightMeters = Float.NaN
                return candidate
            }
        }
        return candidate.copy(
            point = floatArrayOf(candidate.point[0], cameraY - locked, candidate.point[2]),
            confidence = minOf(candidate.confidence, 0.62f),
            source = "${candidate.source}_held",
        )
    }

    private fun currentCameraHeightPrior(): Float {
        val manual = manualCameraHeightMeters
        if (manual.isFinite() && manual in MIN_REASONABLE_CAMERA_HEIGHT_M..MAX_REASONABLE_CAMERA_HEIGHT_M) {
            return manual
        }
        val locked = lockedCameraHeightMeters
        return if (locked.isFinite() && locked in MIN_REASONABLE_CAMERA_HEIGHT_M..MAX_REASONABLE_CAMERA_HEIGHT_M) {
            locked
        } else {
            STARTUP_CAMERA_HEIGHT_PRIOR_M
        }
    }

    private fun floorPointFromHit(hit: HitResult, cameraY: Float): Triple<FloatArray, Float, Float>? {
        val trackable = hit.trackable
        val confidencePenalty = when (trackable) {
            is Plane -> {
                if (trackable.trackingState != TrackingState.TRACKING) return null
                if (trackable.type != Plane.Type.HORIZONTAL_UPWARD_FACING) return null
                if (!trackable.isPoseInPolygon(hit.hitPose)) return null
                0.00f
            }
            is DepthPoint -> 0.18f
            is Point -> {
                if (trackable.orientationMode != Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) return null
                0.30f
            }
            else -> return null
        }

        val point = hit.hitPose.translation
        val dy = cameraY - point[1]
        if (dy !in MIN_REASONABLE_CAMERA_HEIGHT_M..MAX_REASONABLE_CAMERA_HEIGHT_M) return null
        val confidence = when (trackable) {
            is Plane -> 0.88f
            is DepthPoint -> 0.78f
            else -> 0.66f
        }
        return Triple(floatArrayOf(point[0], point[1], point[2]), confidencePenalty, confidence)
    }

    private fun cameraPitchDegrees(q: FloatArray): Float {
        if (q.size < 4) return Float.NaN
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        val sinp = 2f * (w * x - z * y)
        val cosp = 1f - 2f * (x * x + y * y)
        return Math.toDegrees(atan2(sinp.toDouble(), cosp.toDouble())).toFloat()
    }
}

private fun yuv420ImageToBitmap(image: Image): Bitmap? {
    if (image.format != ImageFormat.YUV_420_888) return null
    val width = image.width
    val height = image.height
    val y = image.planes[0]
    val u = image.planes[1]
    val v = image.planes[2]
    val pixels = IntArray(width * height)
    if (
        nativeYuvConverterAvailable &&
        runCatching {
            nativeYuv420ToArgb(
                y.buffer,
                u.buffer,
                v.buffer,
                y.rowStride,
                y.pixelStride,
                u.rowStride,
                u.pixelStride,
                v.rowStride,
                v.pixelStride,
                width,
                height,
                pixels,
            )
        }.getOrDefault(false)
    ) {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    return yuv420ImageToBitmapFallback(image)
}

private fun yuv420ImageToBitmapFallback(image: Image): Bitmap? {
    if (image.format != ImageFormat.YUV_420_888) return null
    val width = image.width
    val height = image.height
    val y = image.planes[0]
    val u = image.planes[1]
    val v = image.planes[2]

    val yBuffer = y.buffer
    val uBuffer = u.buffer
    val vBuffer = v.buffer
    val pixels = IntArray(width * height)
    var outputIndex = 0

    for (row in 0 until height) {
        val yRow = row * y.rowStride
        val uvRow = (row / 2) * u.rowStride
        for (col in 0 until width) {
            val uvCol = (col / 2) * u.pixelStride
            val yValue = yBuffer.get(yRow + col * y.pixelStride).toInt() and 0xff
            val uValue = uBuffer.get(uvRow + uvCol).toInt() and 0xff
            val vValue = vBuffer.get(uvRow + uvCol).toInt() and 0xff
            pixels[outputIndex++] = yuvPixelToArgb(yValue, uValue, vValue)
        }
    }

    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private val nativeYuvConverterAvailable: Boolean = runCatching {
    System.loadLibrary("physical_scene_optimizer")
    true
}.getOrDefault(false)

private external fun nativeYuv420ToArgb(
    yBuffer: ByteBuffer,
    uBuffer: ByteBuffer,
    vBuffer: ByteBuffer,
    yRowStride: Int,
    yPixelStride: Int,
    uRowStride: Int,
    uPixelStride: Int,
    vRowStride: Int,
    vPixelStride: Int,
    width: Int,
    height: Int,
    outPixels: IntArray,
): Boolean

internal fun yuvPixelToArgb(yValue: Int, uValue: Int, vValue: Int): Int {
    val y = (yValue - 16).coerceAtLeast(0)
    val u = uValue - 128
    val v = vValue - 128
    val r = ((298 * y + 409 * v + 128) shr 8).coerceIn(0, 255)
    val g = ((298 * y - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
    val b = ((298 * y + 516 * u + 128) shr 8).coerceIn(0, 255)
    return (0xff shl 24) or (r shl 16) or (g shl 8) or b
}

private fun createExternalTexture(): Int {
    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return textures[0]
}

private fun createCameraProgram(): Int {
    val vertexShader = compileShader(
        GLES20.GL_VERTEX_SHADER,
        """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() {
            gl_Position = a_Position;
            v_TexCoord = a_TexCoord;
        }
        """.trimIndent(),
    )
    val fragmentShader = compileShader(
        GLES20.GL_FRAGMENT_SHADER,
        """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES u_Texture;
        varying vec2 v_TexCoord;
        void main() {
            gl_FragColor = texture2D(u_Texture, v_TexCoord);
        }
        """.trimIndent(),
    )
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    return program
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    return shader
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
