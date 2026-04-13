package com.pocketmocap.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.pocketmocap.app.CapturedCameraFrame
import com.pocketmocap.app.tracking.CameraIntrinsics
import com.pocketmocap.app.tracking.DepthMapSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import java.io.ByteArrayOutputStream
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
) {
    companion object {
        private const val TAG = "ArCoreFrameCapture"
        private const val MIN_FRAME_INTERVAL_NS = 16_000_000L
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
            val config = Config(arSession).apply {
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
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

    private fun maybeEmitCameraFrame(arSession: Session, frame: Frame) {
        val timestampNs = frame.timestamp
        if (timestampNs <= 0L || timestampNs == lastSentTimestampNs) return
        if (timestampNs - lastSentTimestampNs < MIN_FRAME_INTERVAL_NS) return
        lastSentTimestampNs = timestampNs

        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire ARCore camera image: ${e.message}")
            return
        }

        image.use { cameraImage ->
            val bitmap = yuv420ImageToBitmap(cameraImage) ?: return
            val depthMap = acquirePortraitDepthMap(frame)
            val snapshot = buildWorldTrackingSnapshot(arSession, frame, cameraImage, depthMap)
            onFrame(
                CapturedCameraFrame(
                    bitmap = bitmap,
                    width = bitmap.width,
                    height = bitmap.height,
                    timestampUs = timestampNs / 1000L,
                    rotationDegrees = 90,
                    worldTracking = snapshot,
                )
            )
        }
    }

