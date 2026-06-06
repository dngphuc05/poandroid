package com.pocketmocap.app.pipeline

import kotlin.math.sqrt

object HandEndpointStabilizer {
    private const val JOINT_COUNT = 33
    private const val DEFAULT_WEAK_VISIBILITY = 0.55f
    private const val MIN_FOREARM_RATIO = 0.08f
    private const val MAX_FOREARM_RATIO = 0.58f
    private const val MIN_DIRECTION_DOT = -0.20f

    fun stabilizeInPlace(
        x: FloatArray,
        y: FloatArray,
        visibility: FloatArray,
        visibleThreshold: Float,
        weakVisibility: Float = DEFAULT_WEAK_VISIBILITY,
        correctStrongFlips: Boolean = false,
        onChanged: ((index: Int, nextX: Float, nextY: Float) -> Unit)? = null,
    ): Int {
        if (x.size < JOINT_COUNT || y.size < JOINT_COUNT || visibility.size < JOINT_COUNT) return 0
        var changed = 0
        changed += stabilizeSide(
            elbow = 13,
            wrist = 15,
            endpoints = intArrayOf(17, 19, 21),
            x = x,
            y = y,
            visibility = visibility,
            visibleThreshold = visibleThreshold,
            weakVisibility = weakVisibility,
            correctStrongFlips = correctStrongFlips,
            onChanged = onChanged,
        )
        changed += stabilizeSide(
            elbow = 14,
            wrist = 16,
            endpoints = intArrayOf(18, 20, 22),
            x = x,
            y = y,
            visibility = visibility,
            visibleThreshold = visibleThreshold,
            weakVisibility = weakVisibility,
            correctStrongFlips = correctStrongFlips,
            onChanged = onChanged,
        )
        return changed
    }

    private fun stabilizeSide(
        elbow: Int,
        wrist: Int,
        endpoints: IntArray,
        x: FloatArray,
        y: FloatArray,
        visibility: FloatArray,
        visibleThreshold: Float,
        weakVisibility: Float,
        correctStrongFlips: Boolean,
        onChanged: ((index: Int, nextX: Float, nextY: Float) -> Unit)?,
    ): Int {
        if (!hasUsableJoint(elbow, visibility, visibleThreshold) ||
            !hasUsableJoint(wrist, visibility, visibleThreshold)
        ) {
            return 0
        }
        val forearmX = x[wrist] - x[elbow]
        val forearmY = y[wrist] - y[elbow]
        val forearmLength = sqrt(forearmX * forearmX + forearmY * forearmY)
        if (forearmLength <= 1e-5f) return 0

        val forearmUnitX = forearmX / forearmLength
        val forearmUnitY = forearmY / forearmLength
        var changed = 0

        for (endpoint in endpoints) {
            if (!hasUsableJoint(endpoint, visibility, visibleThreshold)) continue
            val handX = x[endpoint] - x[wrist]
            val handY = y[endpoint] - y[wrist]
            val handLength = sqrt(handX * handX + handY * handY)
            if (handLength <= 1e-5f) continue

            val ratio = handLength / forearmLength
            val directionDot = (handX * forearmUnitX + handY * forearmUnitY) / handLength
            val weakEndpoint = visibility[endpoint] < weakVisibility
            val tooLong = ratio > MAX_FOREARM_RATIO
            val flipped = directionDot < MIN_DIRECTION_DOT
            val implausible = tooLong ||
                (flipped && (weakEndpoint || correctStrongFlips)) ||
                (weakEndpoint && directionDot < 0.05f)
            if (!implausible) continue

            val safeLength = handLength.coerceIn(
                forearmLength * MIN_FOREARM_RATIO,
                forearmLength * MAX_FOREARM_RATIO,
            )
            val targetX = x[wrist] + forearmUnitX * safeLength
            val targetY = y[wrist] + forearmUnitY * safeLength
            val blend = when {
                weakEndpoint && flipped -> 0.76f
                flipped -> 0.62f
                tooLong -> 0.46f
                else -> 0.28f
            }
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
