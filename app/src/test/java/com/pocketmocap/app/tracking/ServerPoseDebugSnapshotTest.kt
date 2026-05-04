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

        val json = scene.toJson()

        assertEquals(1.82f, json.optDouble("local_height_candidate_m", Double.NaN).toFloat(), 1e-6f)
        assertEquals(0.66f, json.optDouble("local_height_candidate_confidence", Double.NaN).toFloat(), 1e-6f)
        assertEquals("local_display_top_supported", json.optString("local_height_candidate_source"))
    }

