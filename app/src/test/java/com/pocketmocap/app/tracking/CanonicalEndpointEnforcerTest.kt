package com.pocketmocap.app.tracking

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEndpointEnforcerTest {
    @Test
    fun clampsElongatedHandEndpointsFromCanonicalHeight() {
        val x = FloatArray(33)
        val y = FloatArray(33)
        val z = FloatArray(33)
        val confidence = FloatArray(33) { 0.92f }
        x[15] = 0.0f
        y[15] = 1.2f
        z[15] = -2.4f
        seedNormalHandEndpoints(x, y, z, leftWrist = 15, rightWrist = 16)
        x[19] = 0.35f
        y[19] = 1.2f
        z[19] = -2.4f

        val result = enforceCanonicalHandEndpoints(
            x = x,
            y = y,
            z = z,
            confidence = confidence,
            targetHeightMeters = 1.82f,
        )

        val length = distance(x, y, z, 15, 19)
        assertEquals(1, result.clampedCount)
        assertEquals(1, result.lowTrustCount)
        assertEquals(1.82f * 0.050f, length, 1e-4f)
        assertTrue(confidence[19] <= 0.46f)
    }

    @Test
    fun leavesNormalHandEndpointsTrusted() {
        val x = FloatArray(33)
        val y = FloatArray(33)
        val z = FloatArray(33)
        val confidence = FloatArray(33) { 0.88f }
        x[16] = 0.0f
        y[16] = 1.1f
        z[16] = -2.2f
        seedNormalHandEndpoints(x, y, z, leftWrist = 15, rightWrist = 16)
        x[20] = 0.091f
        y[20] = 1.1f
        z[20] = -2.2f

        val result = enforceCanonicalHandEndpoints(
            x = x,
            y = y,
            z = z,
            confidence = confidence,
            targetHeightMeters = 1.82f,
        )

        assertEquals(0, result.clampedCount)
        assertEquals(0, result.lowTrustCount)
        assertEquals(0.88f, confidence[20], 1e-6f)
    }

    @Test
    fun clampsElongatedForearmWithoutMovingShoulderAnchor() {
        val x = FloatArray(33)
        val y = FloatArray(33)
        val z = FloatArray(33)
        val confidence = FloatArray(33) { 0.91f }
        seedCanonicalLimbs(x, y, z, targetHeightMeters = 1.82f)
        val shoulderX = x[11]
        val shoulderY = y[11]
        val shoulderZ = z[11]
        x[15] = x[13] - 0.72f
        y[15] = y[13]
        z[15] = z[13]

        val result = enforceCanonicalLimbEndpoints(
            x = x,
            y = y,
            z = z,
            confidence = confidence,
            targetHeightMeters = 1.82f,
        )

        assertTrue(result.clampedCount >= 1)
        assertTrue(result.lowTrustCount >= 1)
        assertEquals(shoulderX, x[11], 1e-6f)
        assertEquals(shoulderY, y[11], 1e-6f)
        assertEquals(shoulderZ, z[11], 1e-6f)
        assertEquals(1.82f * 0.160f, distance(x, y, z, 13, 15), 1e-4f)
        assertTrue(confidence[15] <= 0.56f)
    }

    private fun distance(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        a: Int,
        b: Int,
    ): Float {
        val dx = x[b] - x[a]
        val dy = y[b] - y[a]
        val dz = z[b] - z[a]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun seedNormalHandEndpoints(
        x: FloatArray,
        y: FloatArray,
        z: FloatArray,
        leftWrist: Int,
        rightWrist: Int,
    ) {
        x[rightWrist] = 0.0f
        y[rightWrist] = 1.1f
        z[rightWrist] = -2.2f
        for (child in intArrayOf(17, 19, 21)) {
            x[child] = x[leftWrist] - 0.091f
            y[child] = y[leftWrist]
            z[child] = z[leftWrist]
        }
        for (child in intArrayOf(18, 20, 22)) {
            x[child] = x[rightWrist] + 0.091f
            y[child] = y[rightWrist]
            z[child] = z[rightWrist]
        }
    }

