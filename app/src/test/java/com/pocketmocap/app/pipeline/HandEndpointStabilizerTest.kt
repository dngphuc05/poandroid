package com.pocketmocap.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandEndpointStabilizerTest {

    @Test
    fun weakEndpointThatPointsAgainstForearmIsCorrected() {
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val visibility = FloatArray(33)

        x[13] = 0.62f
        y[13] = 0.50f
        x[15] = 0.48f
        y[15] = 0.50f
        x[19] = 0.62f
        y[19] = 0.50f
        visibility[13] = 0.95f
        visibility[15] = 0.95f
        visibility[19] = 0.30f

        val changed = HandEndpointStabilizer.stabilizeInPlace(
            x = x,
            y = y,
            visibility = visibility,
            visibleThreshold = 0.16f,
        )

        assertEquals(1, changed)
        assertTrue("weak endpoint should stay behind the wrist with the forearm direction", x[19] < 0.52f)
    }

    @Test
    fun plausibleFastEndpointIsNotModified() {
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val visibility = FloatArray(33)

        x[13] = 0.62f
        y[13] = 0.50f
        x[15] = 0.48f
        y[15] = 0.50f
        x[19] = 0.42f
        y[19] = 0.48f
        visibility[13] = 0.95f
        visibility[15] = 0.95f
        visibility[19] = 0.95f

        val beforeX = x[19]
        val beforeY = y[19]
        val changed = HandEndpointStabilizer.stabilizeInPlace(
            x = x,
            y = y,
            visibility = visibility,
            visibleThreshold = 0.16f,
        )

        assertEquals(0, changed)
        assertEquals(beforeX, x[19], 1e-6f)
        assertEquals(beforeY, y[19], 1e-6f)
    }

    @Test
    fun serverModeCanCorrectStrongEndpointFlip() {
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val visibility = FloatArray(33)

        x[13] = 0.62f
        y[13] = 0.50f
        x[15] = 0.48f
        y[15] = 0.50f
        x[19] = 0.62f
        y[19] = 0.50f
        visibility[13] = 0.95f
        visibility[15] = 0.95f
        visibility[19] = 0.95f

        val changed = HandEndpointStabilizer.stabilizeInPlace(
            x = x,
            y = y,
            visibility = visibility,
            visibleThreshold = 0.16f,
            correctStrongFlips = true,
        )

        assertEquals(1, changed)
        assertTrue("server-bound endpoint should be corrected when it points against the forearm", x[19] < 0.54f)
    }
}
