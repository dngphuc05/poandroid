package com.pocketmocap.app

import com.pocketmocap.app.pipeline.LandmarkFallbackEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkFallbackEngineTest {
    @Test
    fun uncertainHiddenLeftElbowDoesNotFollowBadRawPointAcrossBody() {
        val engine = LandmarkFallbackEngine()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val visibility = FloatArray(33) { 0.05f }
        seedTorso(x, y, visibility)

        x[11] = 0.40f; y[11] = 0.30f; visibility[11] = 0.95f // left shoulder
        x[15] = 0.32f; y[15] = 0.46f; visibility[15] = 0.95f // left wrist
        x[13] = 0.88f; y[13] = 0.24f; visibility[13] = 0.30f // bad low-confidence elbow

        val outX = FloatArray(33)
        val outY = FloatArray(33)
        val outVisibility = FloatArray(33)

        engine.complete(x, y, visibility, outX, outY, outVisibility)

        assertTrue("hidden left elbow should stay on the left half of the body", outX[13] < 0.50f)
        assertTrue("hidden left elbow should remain drawable as a weak prediction", outVisibility[13] >= 0.18f)
    }

    @Test
    fun completionKeepsThirtyThreeFiniteLandmarksForServerPayload() {
        val engine = LandmarkFallbackEngine()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val visibility = FloatArray(33) { 0.02f }
        seedTorso(x, y, visibility)

        val outX = FloatArray(33)
        val outY = FloatArray(33)
        val outVisibility = FloatArray(33)

        engine.complete(x, y, visibility, outX, outY, outVisibility)

        for (i in 0 until 33) {
            assertFalse("x[$i] should be finite", outX[i].isNaN())
            assertFalse("y[$i] should be finite", outY[i].isNaN())
            assertTrue("x[$i] should stay normalized", outX[i] in 0f..1f)
            assertTrue("y[$i] should stay normalized", outY[i] in 0f..1f)
        }
    }

    private fun seedTorso(x: FloatArray, y: FloatArray, visibility: FloatArray) {
        x[11] = 0.40f; y[11] = 0.30f; visibility[11] = 0.95f
        x[12] = 0.60f; y[12] = 0.30f; visibility[12] = 0.95f
        x[23] = 0.44f; y[23] = 0.56f; visibility[23] = 0.95f
        x[24] = 0.56f; y[24] = 0.56f; visibility[24] = 0.95f
    }
}
