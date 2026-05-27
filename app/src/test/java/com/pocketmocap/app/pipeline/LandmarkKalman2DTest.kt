package com.pocketmocap.app.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

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
}
