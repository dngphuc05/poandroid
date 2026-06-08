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
    private val latencyCompensationFrames: Float = 1.0f,
    private val minPredictionMotion: Float = 0.025f,
    private val maxPredictionStep: Float = 0.06f,
    private val lowerBodyStillAlpha: Float = 0.26f,
    private val lowerBodyFastAlpha: Float = 0.82f,
    private val lowerBodyFastMotionDistance: Float = 0.18f,
    private val handEndpointStillAlpha: Float = 0.28f,
    private val handEndpointFastAlpha: Float = 0.88f,
    private val handEndpointFastMotionDistance: Float = 0.08f,
) {
    private val state = DisplayJointState(jointCount)
    private val measuredHandX = FloatArray(jointCount)
    private val measuredHandY = FloatArray(jointCount)
    private val measuredHandVisibility = FloatArray(jointCount)

    fun reset() {
        state.reset()
    }

    fun update(rawX: FloatArray, rawY: FloatArray, rawVisibility: FloatArray): ObservedJointDisplayFrame {
        val outX = FloatArray(jointCount)
        val outY = FloatArray(jointCount)
        val outVisibility = FloatArray(jointCount)
        val previousX = state.xSnapshot()
        val previousY = state.ySnapshot()
        val previousVisible = state.visibleSnapshot()

        for (index in 0 until jointCount) {
            updateJoint(index, rawX, rawY, rawVisibility, outX, outY, outVisibility)
        }
        dampIsolatedDetectorJumps(previousX, previousY, previousVisible, outX, outY, outVisibility)
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
        val predicted = state.predictObserved(
            index = index,
            measuredX = x,
            measuredY = y,
            frames = predictionFramesFor(index),
            minMotion = minPredictionMotion,
            maxStep = predictionStepFor(index),
        )
        val alpha = if (state.hasVisible(index)) {
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

    private fun dampIsolatedDetectorJumps(
        previousX: FloatArray,
        previousY: FloatArray,
        previousVisible: BooleanArray,
        outX: FloatArray,
        outY: FloatArray,
        outVisibility: FloatArray,
    ) {
        dampCoreJoint(11, intArrayOf(12, 23, 24), previousX, previousY, previousVisible, outX, outY, outVisibility)
        dampCoreJoint(12, intArrayOf(11, 23, 24), previousX, previousY, previousVisible, outX, outY, outVisibility)
        dampCoreJoint(23, intArrayOf(11, 12, 24), previousX, previousY, previousVisible, outX, outY, outVisibility)
        dampCoreJoint(24, intArrayOf(11, 12, 23), previousX, previousY, previousVisible, outX, outY, outVisibility)
    }

    private fun dampCoreJoint(
        index: Int,
        neighbors: IntArray,
        previousX: FloatArray,
        previousY: FloatArray,
        previousVisible: BooleanArray,
        outX: FloatArray,
        outY: FloatArray,
        outVisibility: FloatArray,
    ) {
        if (!previousVisible.getOrElse(index) { false } || outVisibility.getOrElse(index) { 0f } < visibleThreshold) return
        val jointStep = distance(previousX[index], previousY[index], outX[index], outY[index])
        if (jointStep < 0.035f) return
        val stableNeighbors = neighbors.count { neighbor ->
            previousVisible.getOrElse(neighbor) { false } &&
                outVisibility.getOrElse(neighbor) { 0f } >= visibleThreshold &&
                distance(previousX[neighbor], previousY[neighbor], outX[neighbor], outY[neighbor]) < 0.018f
        }
        if (stableNeighbors < 2) return
        val nextX = lerp(previousX[index], outX[index], 0.22f)
        val nextY = lerp(previousY[index], outY[index], 0.22f)
        outX[index] = nextX
        outY[index] = nextY
        state.overwritePosition(index, nextX, nextY)
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    private fun lerp(from: Float, to: Float, alpha: Float): Float =
        from + (to - from) * alpha.coerceIn(0f, 1f)

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

    fun xSnapshot(): FloatArray = x.copyOf()

    fun ySnapshot(): FloatArray = y.copyOf()

    fun visibleSnapshot(): BooleanArray =
        BooleanArray(jointCount) { index -> hasPosition[index] && visibility[index] > 0f }

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
