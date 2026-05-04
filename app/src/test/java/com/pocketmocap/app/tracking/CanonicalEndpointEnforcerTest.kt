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

