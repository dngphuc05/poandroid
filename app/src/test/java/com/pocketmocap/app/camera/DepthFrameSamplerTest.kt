package com.pocketmocap.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthFrameSamplerTest {
    @Test
    fun requestsDepthOnlyAfterSamplingInterval() {
        val sampler = DepthFrameSampler(
            sampleIntervalNs = 200_000_000L,
            reuseWindowNs = 600_000_000L,
        )

        assertTrue(sampler.shouldRequestDepth(1_000_000_000L))
        assertFalse(sampler.shouldRequestDepth(1_050_000_000L))
        assertFalse(sampler.shouldRequestDepth(1_199_000_000L))
        assertTrue(sampler.shouldRequestDepth(1_200_000_000L))
    }

    @Test
    fun failedDepthRequestDoesNotMakeOldDepthReusable() {
        val sampler = DepthFrameSampler(
            sampleIntervalNs = 200_000_000L,
            reuseWindowNs = 600_000_000L,
        )

        assertTrue(sampler.shouldRequestDepth(1_000_000_000L))
        sampler.markDepthSampleSucceeded(1_000_000_000L)
        assertTrue(sampler.canReuseDepth(1_500_000_000L))
        assertTrue(sampler.shouldRequestDepth(1_800_000_000L))

        assertFalse(sampler.canReuseDepth(1_800_000_000L))
    }
}
