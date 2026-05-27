package com.pocketmocap.app.pipeline

import kotlin.math.sqrt

data class ObservedJointDisplayFrame(
    val x: FloatArray,
    val y: FloatArray,
    val visibility: FloatArray,
)

/**
 * Fast phone-overlay filter for observed MediaPipe joints only.
 *
 * This class deliberately does not complete hidden joints. The metric pipeline can
 * use plausible completion, but the camera overlay should only draw joints that
 * MediaPipe is actually seeing so points stay attached to the person in frame.
 */
class ObservedJointDisplayFilter(
    private val jointCount: Int = 33,
    private val visibleThreshold: Float = 0.28f,
    private val stillAlpha: Float = 0.40f,
    private val fastAlpha: Float = 0.96f,
    private val fastMotionDistance: Float = 0.12f,
) {
    private val state = DisplayJointState(jointCount)

    fun reset() {
        state.reset()
    }

    fun update(rawX: FloatArray, rawY: FloatArray, rawVisibility: FloatArray): ObservedJointDisplayFrame {
        val outX = FloatArray(jointCount)
        val outY = FloatArray(jointCount)
        val outVisibility = FloatArray(jointCount)

        for (index in 0 until jointCount) {
            updateJoint(index, rawX, rawY, rawVisibility, outX, outY, outVisibility)
        }

        return ObservedJointDisplayFrame(outX, outY, outVisibility)
    }

    private fun updateJoint(
        index: Int,
        rawX: FloatArray,
        rawY: FloatArray,
        rawVisibility: FloatArray,
        outX: FloatArray,
        outY: FloatArray,
        outVisibility: FloatArray,
    ) {
        val visibility = rawVisibility.getOrElse(index) { 0f }
        val measuredX = rawX.getOrElse(index) { Float.NaN }
        val measuredY = rawY.getOrElse(index) { Float.NaN }
        if (!isVisibleMeasurement(visibility, measuredX, measuredY)) {
            state.writeHidden(index, outX, outY, outVisibility)
            return
        }

        val x = measuredX.coerceIn(0f, 1f)
        val y = measuredY.coerceIn(0f, 1f)
        val alpha = if (state.hasVisible(index)) {
            adaptiveAlpha(index, x, y)
        } else {
            fastAlpha
        }

        state.writeVisible(index, x, y, visibility, alpha, outX, outY, outVisibility)
    }

    private fun isVisibleMeasurement(visibility: Float, x: Float, y: Float): Boolean =
        visibility >= visibleThreshold && x.isFinite() && y.isFinite()

    private fun adaptiveAlpha(index: Int, x: Float, y: Float): Float {
        val dx = x - state.x(index)
        val dy = y - state.y(index)
        val distance = sqrt(dx * dx + dy * dy)
        val motion = (distance / fastMotionDistance).coerceIn(0f, 1f)
        return stillAlpha + (fastAlpha - stillAlpha) * motion
    }
}

private class DisplayJointState(private val jointCount: Int) {
    private val x = FloatArray(jointCount)
    private val y = FloatArray(jointCount)
    private val visibility = FloatArray(jointCount)
    private val hasPosition = BooleanArray(jointCount)

    fun reset() {
        x.fill(0f)
        y.fill(0f)
        visibility.fill(0f)
        hasPosition.fill(false)
    }

    fun hasVisible(index: Int): Boolean = hasPosition[index] && visibility[index] > 0f

    fun x(index: Int): Float = x[index]

    fun y(index: Int): Float = y[index]

    fun writeHidden(index: Int, outX: FloatArray, outY: FloatArray, outVisibility: FloatArray) {
        outX[index] = x[index]
        outY[index] = y[index]
        outVisibility[index] = 0f
        visibility[index] = 0f
    }

    fun writeVisible(
        index: Int,
        measuredX: Float,
        measuredY: Float,
        measuredVisibility: Float,
        alpha: Float,
        outX: FloatArray,
        outY: FloatArray,
        outVisibility: FloatArray,
    ) {
        val nextX = if (hasPosition[index]) lerp(x[index], measuredX, alpha) else measuredX
        val nextY = if (hasPosition[index]) lerp(y[index], measuredY, alpha) else measuredY
        x[index] = nextX
        y[index] = nextY
        visibility[index] = measuredVisibility.coerceIn(0f, 1f)
        hasPosition[index] = true

        outX[index] = nextX
        outY[index] = nextY
        outVisibility[index] = visibility[index]
    }

    private fun lerp(from: Float, to: Float, alpha: Float): Float =
        from + (to - from) * alpha.coerceIn(0f, 1f)
}
