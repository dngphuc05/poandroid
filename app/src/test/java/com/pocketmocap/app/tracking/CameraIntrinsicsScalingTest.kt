package com.pocketmocap.app.tracking

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraIntrinsicsScalingTest {

    @Test
    fun scalesFullResolutionIntrinsicsToServerFrame() {
        val raw = JSONObject()
            .put("width", 4032)
            .put("height", 3024)
            .put("fx", 2841.1766)
            .put("fy", 2830.0782)
            .put("cx", 2016.0)
            .put("cy", 1512.0)

        val scaled = cameraIntrinsicsFromJsonScaled(raw, targetWidth = 640, targetHeight = 480)

        assertEquals(640, scaled.imageWidth)
        assertEquals(480, scaled.imageHeight)
        assertEquals(451.0f, scaled.fx, 1.0f)
        assertEquals(449.0f, scaled.fy, 1.0f)
        assertEquals(320.0f, scaled.cx, 0.5f)
        assertEquals(240.0f, scaled.cy, 0.5f)
    }

    @Test
    fun missingJsonFallsBackToFrameSizedIntrinsics() {
        val scaled = cameraIntrinsicsFromJsonScaled(null, targetWidth = 640, targetHeight = 480)

        assertEquals(640, scaled.imageWidth)
        assertEquals(480, scaled.imageHeight)
        assertEquals(768.0f, scaled.fx, 0.01f)
        assertEquals(576.0f, scaled.fy, 0.01f)
        assertEquals(320.0f, scaled.cx, 0.01f)
        assertEquals(240.0f, scaled.cy, 0.01f)
    }
}
