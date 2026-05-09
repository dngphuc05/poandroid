package com.pocketmocap.app.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkFallbackEngineTest {

    @Test
    fun syntheticFallbackDoesNotAdvertiseObservedVisibility() {
        val engine = LandmarkFallbackEngine()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0f }
        val outX = FloatArray(33)
        val outY = FloatArray(33)
        val outV = FloatArray(33)

        x[11] = 0.42f
        y[11] = 0.35f
        v[11] = 0.95f
        x[12] = 0.58f
        y[12] = 0.35f
        v[12] = 0.95f
        x[23] = 0.45f
        y[23] = 0.58f
        v[23] = 0.95f
        x[24] = 0.55f
        y[24] = 0.58f
        v[24] = 0.95f

        engine.complete(x, y, v, outX, outY, outV)

        assertTrue("missing wrist fallback must stay below observed-ROI threshold", outV[15] < 0.20f)
        assertTrue("missing ankle fallback must stay below observed-ROI threshold", outV[27] < 0.20f)
        assertTrue("observed shoulder remains trusted", outV[11] > 0.90f)
    }
}
