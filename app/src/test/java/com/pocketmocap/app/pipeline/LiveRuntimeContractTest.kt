package com.pocketmocap.app.pipeline

import com.pocketmocap.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRuntimeContractTest {

    @Test
    fun phoneLivePipelineUsesLatencyFirstPoseModel() {
        assertEquals("pose_landmarker_lite.task", BuildConfig.POSE_LANDMARKER_MODEL)
        assertFalse("segmentation masks are too expensive for every live frame", BuildConfig.POSE_SEGMENTATION_MASKS)
        assertTrue("server cadence must target at least 30fps when inference can keep up", PipelineTiming.SERVER_SEND_INTERVAL_MS <= 33L)
    }
}
