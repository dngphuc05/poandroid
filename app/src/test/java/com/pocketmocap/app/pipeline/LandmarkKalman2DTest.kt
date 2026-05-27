package com.pocketmocap.app.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LandmarkKalman2DTest {

    @Test
    fun fastMotionSnapPreservesVelocityForOccludedPrediction() {
        val filter = LandmarkKalman2D(fps = 30f)

        filter.update(x = 0.10f, y = 0.50f, visible = true, maxInnovation = 0.10f)
        filter.update(x = 0.60f, y = 0.50f, visible = true, maxInnovation = 0.10f)

        val predicted = filter.update(x = 0.60f, y = 0.50f, visible = false)

        assertTrue(
            "after a real fast swing, the occluded point should keep moving instead of freezing",
            predicted.first > 0.60f,
        )
    }

    @Test
    fun velocityDecayConvergesHiddenJointWithin10Frames() {
        val filter = LandmarkKalman2D(fps = 30f)

        // Give initial position + small velocity
        filter.update(x = 0.50f, y = 0.50f, visible = true)
        filter.update(x = 0.52f, y = 0.50f, visible = true)

        // Predict 10 frames with no measurement (hidden joint)
        var lastX = 0.52f
        for (i in 0 until 10) {
            val (px, _) = filter.update(x = 0f, y = 0f, visible = false)
            lastX = px
        }

        // After 10 frames of decay (333ms at 30fps), velocity should have reduced
        // enough that position is not far from where it started drifting.
        // With 0.88 decay: velocity after 10 frames = v0 * 0.88^10 ≈ v0 * 0.28
        assertTrue(
            "hidden joint should not drift more than 0.15 in normalized coords over 10 frames",
            lastX < 0.67f,
        )
    }

    @Test
    fun stationaryInputProducesStableOutput() {
        val filter = LandmarkKalman2D(fps = 30f)

        // Feed 20 frames of slightly noisy stationary position
        for (i in 0 until 20) {
            val noise = if (i % 2 == 0) 0.002f else -0.002f
            filter.update(x = 0.50f + noise, y = 0.50f + noise, visible = true)
        }

        // Output should be very close to 0.50 with minimal jitter
        val (x, y) = filter.getState()
        assertTrue(
            "stationary filtered output should be within 0.01 of true position",
            abs(x - 0.50f) < 0.01f && abs(y - 0.50f) < 0.01f,
        )
    }
}
