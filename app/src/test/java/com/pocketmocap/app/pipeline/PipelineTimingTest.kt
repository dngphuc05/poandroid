package com.pocketmocap.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineTimingTest {
    @Test
    fun serverSendCadenceMatchesThirtyFpsCameraEvidence() {
        assertEquals(33L, PipelineTiming.SERVER_SEND_INTERVAL_MS)
    }
}
