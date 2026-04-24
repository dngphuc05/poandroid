package com.pocketmocap.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max

/**
 * Transparent SurfaceView overlay for skeleton/avatar drawing.
 *
 * Draws DIRECTLY from the MediaPipe analysis thread — bypasses Compose
 * recomposition and the Choreographer Vsync wait (~8ms avg).  The surface
 * buffer is posted to SurfaceFlinger which composites it at the next hardware
 * Vsync (~8ms max), not the next Compose frame (~16ms max).
 *
 * To use, call [renderSkeleton] or [renderAvatar] from any thread after
 * landmarks are available.  Call [clear] when no person is detected.
 */
class SkeletonSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile private var surfaceReady = false

    // ── Paints (allocated once) ───────────────────────────────────────────────
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 100, 230, 200)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(114, 0, 229, 160)  // Mint 45%
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(191, 0, 229, 160)  // Mint 75%
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    // Avatar body fill
    private val torsoFill  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(63, 100, 230, 200) ; style = Paint.Style.FILL }
    private val limbFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(51, 100, 230, 200) ; style = Paint.Style.FILL }
    private val headFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(45, 200, 100, 200) ; style = Paint.Style.FILL }

    private val majorJoints = setOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
    }

    override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true }
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}

    /** Clear surface (no person detected). */
    fun clear() = withCanvas { drawColor(0, PorterDuff.Mode.CLEAR) }

    /** Draw skeleton overlay — call from any thread. */
    fun renderSkeleton(
        xNorm: FloatArray, yNorm: FloatArray, vis: FloatArray?,
        imageWidth: Int, imageHeight: Int,
    ) = withCanvas { drawSkeletonInternal(xNorm, yNorm, vis, imageWidth, imageHeight, avatar = false) }

    /** Draw avatar body overlay — call from any thread. */
    fun renderAvatar(
        xNorm: FloatArray, yNorm: FloatArray, vis: FloatArray?,
        imageWidth: Int, imageHeight: Int,
    ) = withCanvas { drawSkeletonInternal(xNorm, yNorm, vis, imageWidth, imageHeight, avatar = true) }

    // ── Internal drawing ──────────────────────────────────────────────────────

    private inline fun withCanvas(block: Canvas.() -> Unit) {
        if (!surfaceReady) return
        // API 26+: hardware canvas → GPU compositing path, avoids CPU software blend
        val canvas = if (android.os.Build.VERSION.SDK_INT >= 26)
            holder.lockHardwareCanvas() else holder.lockCanvas()
        canvas ?: return
        try {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.block()
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun Canvas.px(
        idx: Int, xNorm: FloatArray, yNorm: FloatArray,
        imageWidth: Int, imageHeight: Int,
        avatar: Boolean = false,
    ): Pair<Float, Float> {
        val lx = xNorm[idx]; val ly = yNorm[idx]
        val W = width.toFloat(); val H = height.toFloat()
        return if (imageWidth > 0 && imageHeight > 0) {
            val scale = max(W / imageWidth, H / imageHeight) * if (avatar) 0.86f else 1f
            val dispW = imageWidth * scale; val dispH = imageHeight * scale
            val offX = (dispW - W) / 2f
            val offY = (dispH - H) / 2f - if (avatar) H * 0.03f else 0f
            Pair(lx * dispW - offX, ly * dispH - offY)
        } else {
            Pair(lx * W, ly * H)
        }
    }

