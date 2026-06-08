package com.pocketmocap.app.pipeline

import kotlin.math.sqrt

/**
 * Keeps heel/toe detector outliers attached to the ankle chain.
 *
 * This is deliberately local and geometric. It does not invent hidden feet or
 * force a gait model; it only caps visible endpoints that stretch or flip far
 * outside the lower-leg scale in a single observed frame.
 */
object FootEndpointStabilizer {
    private const val JOINT_COUNT = 33
    private const val DEFAULT_WEAK_VISIBILITY = 0.55f
    private const val MIN_FOOT_RATIO = 0.06f
    private const val MAX_FOOT_RATIO = 0.58f
    private const val FLIPPED_DIRECTION_DOT = -0.35f

    fun stabilizeInPlace(
        x: FloatArray,
        y: FloatArray,
        visibility: FloatArray,
        visibleThreshold: Float,
        weakVisibility: Float = DEFAULT_WEAK_VISIBILITY,
        onChanged: ((index: Int, nextX: Float, nextY: Float) -> Unit)? = null,
    ): Int {
        if (x.size < JOINT_COUNT || y.size < JOINT_COUNT || visibility.size < JOINT_COUNT) return 0
        var changed = 0
        changed += stabilizeSide(
            knee = 25,
            ankle = 27,
            endpoints = intArrayOf(29, 31),
            x = x,
            y = y,
            visibility = visibility,
            visibleThreshold = visibleThreshold,
            weakVisibility = weakVisibility,
            onChanged = onChanged,
        )
        changed += stabilizeSide(
            knee = 26,
            ankle = 28,
            endpoints = intArrayOf(30, 32),
            x = x,
            y = y,
            visibility = visibility,
            visibleThreshold = visibleThreshold,
            weakVisibility = weakVisibility,
            onChanged = onChanged,
        )
        return changed
    }

    private fun stabilizeSide(
        knee: Int,
        ankle: Int,
        endpoints: IntArray,
        x: FloatArray,
        y: FloatArray,
        visibility: FloatArray,
        visibleThreshold: Float,
        weakVisibility: Float,
        onChanged: ((index: Int, nextX: Float, nextY: Float) -> Unit)?,
    ): Int {
        if (!hasUsableJoint(knee, visibility, visibleThreshold) ||
            !hasUsableJoint(ankle, visibility, visibleThreshold)
        ) {
            return 0
        }

        val shinX = x[ankle] - x[knee]
        val shinY = y[ankle] - y[knee]
        val shinLength = sqrt(shinX * shinX + shinY * shinY)
        if (shinLength <= 1e-5f) return 0
        val shinUnitX = shinX / shinLength
        val shinUnitY = shinY / shinLength

        var changed = 0
        for (endpoint in endpoints) {
            if (!hasUsableJoint(endpoint, visibility, visibleThreshold)) continue
            val footX = x[endpoint] - x[ankle]
            val footY = y[endpoint] - y[ankle]
            val footLength = sqrt(footX * footX + footY * footY)
            if (footLength <= 1e-5f) continue

            val ratio = footLength / shinLength
            val footUnitX = footX / footLength
            val footUnitY = footY / footLength
            val directionDot = footUnitX * shinUnitX + footUnitY * shinUnitY
            val weakEndpoint = visibility[endpoint] < weakVisibility
            val tooLong = ratio > MAX_FOOT_RATIO
            val weakFlip = weakEndpoint && directionDot < FLIPPED_DIRECTION_DOT
            if (!tooLong && !weakFlip) continue

            val safeLength = footLength.coerceIn(
                shinLength * MIN_FOOT_RATIO,
                shinLength * MAX_FOOT_RATIO,
            )
            val targetDirectionX = if (weakFlip) shinUnitX else footUnitX
            val targetDirectionY = if (weakFlip) shinUnitY else footUnitY
            val targetX = x[ankle] + targetDirectionX * safeLength
            val targetY = y[ankle] + targetDirectionY * safeLength
            val blend = if (weakFlip) 0.72f else 0.76f
            val nextX = lerp(x[endpoint], targetX, blend).coerceIn(0f, 1f)
            val nextY = lerp(y[endpoint], targetY, blend).coerceIn(0f, 1f)
            x[endpoint] = nextX
            y[endpoint] = nextY
            onChanged?.invoke(endpoint, nextX, nextY)
            changed += 1
        }
        return changed
    }

    private fun hasUsableJoint(index: Int, visibility: FloatArray, visibleThreshold: Float): Boolean =
        index in visibility.indices && visibility[index] >= visibleThreshold

    private fun lerp(from: Float, to: Float, alpha: Float): Float =
        from + (to - from) * alpha.coerceIn(0f, 1f)
}
