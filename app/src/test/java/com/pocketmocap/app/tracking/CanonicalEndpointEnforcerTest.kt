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

