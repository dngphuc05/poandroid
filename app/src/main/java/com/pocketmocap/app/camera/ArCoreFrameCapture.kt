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

