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
