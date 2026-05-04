package com.pocketmocap.app.tracking

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPoseDebugSnapshotTest {
    @Test
    fun sceneMetricSnapshotExportsLocalHeightCandidate() {
        val scene = SceneMetricSnapshot(
            source = "arcore_floor",
            confidence = 0.58f,
            distanceMeters = 2.58f,
            bodyHeightMeters = 1.80f,
            cameraHeightMeters = 1.35f,
            floorPitchDegrees = 0f,
            lateralOffsetMeters = 0.04f,
            localHeightCandidateMeters = 1.82f,
            localHeightCandidateConfidence = 0.66f,
            localHeightCandidateSource = "local_display_top_supported",
        )

