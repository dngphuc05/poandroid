package com.pocketmocap.app.pipeline

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedJointDisplayFilterTest {

    @Test
    fun visibleFastMovementKeepsUpWithinOneFrame() {
        val filter = ObservedJointDisplayFilter()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[15] = 0.20f
        filter.update(x, y, v)

        x[15] = 0.70f
        val out = filter.update(x, y, v)

        assertTrue("visible wrist should follow a fast real swing", abs(out.x[15] - 0.70f) < 0.08f)
    }

    @Test
    fun stillJointStaysStableUnderSmallNoise() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        var minRaw = Float.POSITIVE_INFINITY
        var maxRaw = Float.NEGATIVE_INFINITY
        var minOut = Float.POSITIVE_INFINITY
        var maxOut = Float.NEGATIVE_INFINITY

        repeat(30) { frame ->
            val jitter = if (frame % 2 == 0) -0.010f else 0.010f
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            x[13] = 0.50f + jitter
            minRaw = minOf(minRaw, x[13])
            maxRaw = maxOf(maxRaw, x[13])

            val out = filter.update(x, y, v)
            if (frame > 3) {
                minOut = minOf(minOut, out.x[13])
                maxOut = maxOf(maxOut, out.x[13])
            }
        }

        assertTrue("display filter should damp tiny stationary jitter", (maxOut - minOut) < (maxRaw - minRaw))
    }

    @Test
    fun hiddenJointIsNotInventedOrHeldVisible() {
        val filter = ObservedJointDisplayFilter()
        val visible = FloatArray(33) { 0.95f }
        val hidden = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[16] = 0.44f
        y[16] = 0.40f
        filter.update(x, y, visible)

        hidden[16] = 0.05f
        x[16] = 0.95f
        y[16] = 0.95f
        val out = filter.update(x, y, hidden)

        assertTrue("hidden elbow must not be drawn as a confident point", out.visibility[16] < 0.10f)
        assertTrue("hidden elbow must not jump to a bad hidden measurement", abs(out.x[16] - 0.95f) > 0.25f)
    }

    @Test
    fun naturalElbowBendIsPreserved() {
        val filter = ObservedJointDisplayFilter()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[11] = 0.40f
        y[11] = 0.32f
        x[13] = 0.29f
        y[13] = 0.47f
        x[15] = 0.45f
        y[15] = 0.61f

        val out = filter.update(x, y, v)

        assertTrue("observed elbow bend should stay near the real MediaPipe elbow", abs(out.x[13] - 0.29f) < 0.02f)
        assertTrue("observed elbow bend should not be forced onto the shoulder-wrist line", out.x[13] < 0.34f)
    }

    @Test
    fun multipleFramesRemainFiniteAndInBounds() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }

        repeat(120) { frame ->
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            x[11] = 0.40f
            y[11] = 0.34f
            x[13] = 0.35f + 0.20f * ((frame % 20) / 19f)
            y[13] = 0.45f
            x[15] = 0.46f + 0.16f * ((frame % 30) / 29f)
            y[15] = 0.62f

            val out = filter.update(x, y, v)

            listOf(11, 13, 15).forEach { index ->
                assertTrue("joint $index x must stay finite", out.x[index].isFinite())
                assertTrue("joint $index y must stay finite", out.y[index].isFinite())
                assertTrue("joint $index x must stay normalized", out.x[index] in 0f..1f)
                assertTrue("joint $index y must stay normalized", out.y[index] in 0f..1f)
            }
        }
    }

    @Test
    fun displayFilterStaysInsideRealtimeBudget() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        val started = System.nanoTime()
        repeat(1_800) { frame ->
            for (index in 0 until 33) {
                val phase = ((frame + index) % 60) / 60f
                x[index] = (0.08f + phase * 0.84f).coerceIn(0f, 1f)
                y[index] = (0.12f + ((frame * 2 + index) % 60) / 60f * 0.76f).coerceIn(0f, 1f)
            }
            filter.update(x, y, v)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val averageFrameMs = elapsedMs / 1_800.0

        assertTrue("display filtering should stay far below the 16.7ms/frame camera budget", averageFrameMs < 0.50)
    }
}
