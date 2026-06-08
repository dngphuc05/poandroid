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
    private val latencyCompensationFrames: Float = 0.0f,
    private val minPredictionMotion: Float = 0.025f,
    private val maxPredictionStep: Float = 0.06f,
    private val lowerBodyStillAlpha: Float = 0.26f,
    private val lowerBodyFastAlpha: Float = 0.82f,
    private val lowerBodyFastMotionDistance: Float = 0.18f,
    private val handEndpointStillAlpha: Float = 0.28f,
    private val handEndpointFastAlpha: Float = 0.88f,
    private val handEndpointFastMotionDistance: Float = 0.08f,
    private val isolatedJumpAlpha: Float = 0.10f,
) {
    private val state = DisplayJointState(jointCount)
    private val measuredHandX = FloatArray(jointCount)
    private val measuredHandY = FloatArray(jointCount)
    private val measuredHandVisibility = FloatArray(jointCount)
    private val previousX = FloatArray(jointCount)
    private val previousY = FloatArray(jointCount)
    private val previousVisible = BooleanArray(jointCount)
    private val isolatedEndpointJump = BooleanArray(jointCount)

    fun reset() {
        state.reset()
        previousX.fill(0f)
        previousY.fill(0f)
        previousVisible.fill(false)
        isolatedEndpointJump.fill(false)
    }

    fun update(rawX: FloatArray, rawY: FloatArray, rawVisibility: FloatArray): ObservedJointDisplayFrame {
        val outX = FloatArray(jointCount)
        val outY = FloatArray(jointCount)
        val outVisibility = FloatArray(jointCount)
        for (index in 0 until jointCount) {
            previousVisible[index] = state.hasVisible(index)
            previousX[index] = state.x(index)
            previousY[index] = state.y(index)
            isolatedEndpointJump[index] = false
        }

        for (index in 0 until jointCount) {
            updateJoint(index, rawX, rawY, rawVisibility, outX, outY, outVisibility)
        }
        stabilizeHandEndpoints(rawX, rawY, rawVisibility, outX, outY, outVisibility)

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
        val isolatedJump = previousVisible[index] && isIsolatedDetectorJump(index, x, y, rawX, rawY, rawVisibility)
        if (isolatedJump && isHandEndpoint(index)) {
            isolatedEndpointJump[index] = true
        }
        val predicted = state.predictObserved(
            index = index,
            measuredX = x,
            measuredY = y,
            frames = if (isolatedJump) 0f else predictionFramesFor(index),
            minMotion = minPredictionMotion,
            maxStep = predictionStepFor(index),
        )
        val alpha = if (isolatedJump) {
            isolatedJumpAlpha
        } else if (state.hasVisible(index)) {
            adaptiveAlpha(index, predicted.first, predicted.second)
        } else {
            fastAlpha
        }

        state.writeVisible(index, x, y, predicted.first, predicted.second, visibility, alpha, outX, outY, outVisibility)
    }

    private fun isVisibleMeasurement(visibility: Float, x: Float, y: Float): Boolean =
        visibility >= visibleThreshold && x.isFinite() && y.isFinite()

    private fun adaptiveAlpha(index: Int, x: Float, y: Float): Float {
        val dx = x - state.x(index)
        val dy = y - state.y(index)
        val distance = sqrt(dx * dx + dy * dy)
        if (isHandEndpoint(index)) {
            val motion = (distance / handEndpointFastMotionDistance).coerceIn(0f, 1f)
            return handEndpointStillAlpha + (handEndpointFastAlpha - handEndpointStillAlpha) * motion
        }
        if (isLowerBody(index)) {
            val motion = (distance / lowerBodyFastMotionDistance).coerceIn(0f, 1f)
            return lowerBodyStillAlpha + (lowerBodyFastAlpha - lowerBodyStillAlpha) * motion
        }
        val motion = (distance / fastMotionDistance).coerceIn(0f, 1f)
        return stillAlpha + (fastAlpha - stillAlpha) * motion
    }

    private fun predictionFramesFor(index: Int): Float =
        if (isLowerBody(index) || isHandEndpoint(index)) 0f else latencyCompensationFrames

    private fun predictionStepFor(index: Int): Float =
        if (isLowerBody(index) || isHandEndpoint(index)) 0f else maxPredictionStep

    private fun isIsolatedDetectorJump(
        index: Int,
        x: Float,
        y: Float,
        rawX: FloatArray,
        rawY: FloatArray,
        rawVisibility: FloatArray,
    ): Boolean {
        val jump = previousDistanceTo(index, x, y)
        if (isTorsoCore(index)) {
            if (jump < 0.055f) return false
            val peerMotion = torsoPeerMotion(index, rawX, rawY, rawVisibility)
            return peerMotion < 0.036f
        }
        if (isHandEndpoint(index)) {
            if (jump < 0.065f) return false
            val parent = handEndpointParent(index)
            if (parent < 0 || !previousVisible[parent]) return false
            if (!isVisibleMeasurement(
                    rawVisibility.getOrElse(parent) { 0f },
                    rawX.getOrElse(parent) { Float.NaN },
                    rawY.getOrElse(parent) { Float.NaN },
                )
            ) {
                return false
            }
            val parentMotion = previousDistanceTo(
                parent,
                rawX[parent].coerceIn(0f, 1f),
                rawY[parent].coerceIn(0f, 1f),
            )
            return parentMotion < 0.035f
        }
        return false
    }

    private fun torsoPeerMotion(index: Int, rawX: FloatArray, rawY: FloatArray, rawVisibility: FloatArray): Float {
        val peers = intArrayOf(11, 12, 23, 24)
        var sum = 0f
        var count = 0
        for (peer in peers) {
            if (peer == index || !previousVisible[peer]) continue
            val vx = rawX.getOrElse(peer) { Float.NaN }
            val vy = rawY.getOrElse(peer) { Float.NaN }
            val vv = rawVisibility.getOrElse(peer) { 0f }
            if (!isVisibleMeasurement(vv, vx, vy)) continue
            sum += previousDistanceTo(peer, vx.coerceIn(0f, 1f), vy.coerceIn(0f, 1f))
            count += 1
        }
        return if (count == 0) Float.POSITIVE_INFINITY else sum / count.toFloat()
    }

    private fun stabilizeHandEndpoints(
        rawX: FloatArray,
        rawY: FloatArray,
        rawVisibility: FloatArray,
        outX: FloatArray,
        outY: FloatArray,
        outVisibility: FloatArray,
    ) {
        HandEndpointStabilizer.stabilizeInPlace(
            x = outX,
            y = outY,
            visibility = outVisibility,
            visibleThreshold = visibleThreshold,
            onChanged = state::overwritePosition,
        )
        if (rawX.size < jointCount || rawY.size < jointCount || rawVisibility.size < jointCount) return

        rawX.copyInto(measuredHandX, endIndex = jointCount)
        rawY.copyInto(measuredHandY, endIndex = jointCount)
        rawVisibility.copyInto(measuredHandVisibility, endIndex = jointCount)
        for (index in 0 until jointCount) {
            if (!isolatedEndpointJump[index]) continue
            measuredHandX[index] = outX[index]
            measuredHandY[index] = outY[index]
            measuredHandVisibility[index] = minOf(measuredHandVisibility[index], visibleThreshold)
        }
        HandEndpointStabilizer.stabilizeInPlace(
            x = measuredHandX,
            y = measuredHandY,
            visibility = measuredHandVisibility,
            visibleThreshold = visibleThreshold,
            correctStrongFlips = true,
        ) { index, nextX, nextY ->
            if (!isHandEndpoint(index)) return@stabilizeInPlace
            outX[index] = nextX
            outY[index] = nextY
            state.overwritePosition(index, nextX, nextY)
        }
    }

    private fun isLowerBody(index: Int): Boolean = index in 23..32

    private fun isHandEndpoint(index: Int): Boolean = index in 17..22

    private fun isTorsoCore(index: Int): Boolean = index == 11 || index == 12 || index == 23 || index == 24

    private fun previousDistanceTo(index: Int, nextX: Float, nextY: Float): Float {
        val dx = nextX - previousX[index]
        val dy = nextY - previousY[index]
        return sqrt(dx * dx + dy * dy)
    }

    private fun handEndpointParent(index: Int): Int =
        when (index) {
            17, 19, 21 -> 15
            18, 20, 22 -> 16
            else -> -1
        }
}

private class DisplayJointState(private val jointCount: Int) {
    private val x = FloatArray(jointCount)
    private val y = FloatArray(jointCount)
    private val lastMeasuredX = FloatArray(jointCount)
    private val lastMeasuredY = FloatArray(jointCount)
    private val visibility = FloatArray(jointCount)
    private val hasPosition = BooleanArray(jointCount)
    private val hasMeasurement = BooleanArray(jointCount)

    fun reset() {
        x.fill(0f)
        y.fill(0f)
        lastMeasuredX.fill(0f)
        lastMeasuredY.fill(0f)
        visibility.fill(0f)
        hasPosition.fill(false)
        hasMeasurement.fill(false)
    }

    fun hasVisible(index: Int): Boolean = hasPosition[index] && visibility[index] > 0f

    fun x(index: Int): Float = x[index]

    fun y(index: Int): Float = y[index]

    fun distanceTo(index: Int, nextX: Float, nextY: Float): Float {
        val dx = nextX - x[index]
        val dy = nextY - y[index]
        return sqrt(dx * dx + dy * dy)
    }

    fun overwritePosition(index: Int, nextX: Float, nextY: Float) {
        x[index] = nextX
        y[index] = nextY
    }

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
        displayTargetX: Float,
        displayTargetY: Float,
        measuredVisibility: Float,
        alpha: Float,
        outX: FloatArray,
        outY: FloatArray,
        outVisibility: FloatArray,
    ) {
        val nextX = if (hasPosition[index]) lerp(x[index], displayTargetX, alpha) else displayTargetX
        val nextY = if (hasPosition[index]) lerp(y[index], displayTargetY, alpha) else displayTargetY
        x[index] = nextX
        y[index] = nextY
        lastMeasuredX[index] = measuredX
        lastMeasuredY[index] = measuredY
        visibility[index] = measuredVisibility.coerceIn(0f, 1f)
        hasPosition[index] = true
        hasMeasurement[index] = true

        outX[index] = nextX
        outY[index] = nextY
        outVisibility[index] = visibility[index]
    }

    fun predictObserved(
        index: Int,
        measuredX: Float,
        measuredY: Float,
        frames: Float,
        minMotion: Float,
        maxStep: Float,
    ): Pair<Float, Float> {
        if (!hasMeasurement[index] || frames <= 0f || maxStep <= 0f) {
            return Pair(measuredX, measuredY)
        }
        var dx = (measuredX - lastMeasuredX[index]) * frames
        var dy = (measuredY - lastMeasuredY[index]) * frames
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < minMotion) {
            return Pair(measuredX, measuredY)
        }
        if (distance > maxStep && distance > 1e-6f) {
            val scale = maxStep / distance
            dx *= scale
            dy *= scale
        }
        return Pair((measuredX + dx).coerceIn(0f, 1f), (measuredY + dy).coerceIn(0f, 1f))
    }

    private fun lerp(from: Float, to: Float, alpha: Float): Float =
        from + (to - from) * alpha.coerceIn(0f, 1f)
}
