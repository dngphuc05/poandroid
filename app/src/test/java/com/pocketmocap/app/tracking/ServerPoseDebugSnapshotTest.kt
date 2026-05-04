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

    @Test
    fun fromJsonPreservesFullMetricEvidenceOutputs() {
        val outputs = JSONObject().apply {
            METRIC_EVIDENCE_V2_OUTPUT_NAMES.forEachIndexed { index, name ->
                put(name, index + 0.25)
            }
        }
        val mlEvidence = JSONObject().apply {
            put("status", "ok")
            put("schema", "metric_v2")
            put("height_sigma_m", 0.12)
            put("distance_sigma_m", 0.34)
            put("outputs", outputs)
        }
        val debug = JSONObject().apply {
            put("pose_status", "ok")
            put("correction_reason", "height_and_root_constrained")
            put("ml_evidence_image_status", "provided")
        }

        val snapshot = ServerPoseDebugSnapshot.fromJson(debug, mlEvidenceJson = mlEvidence)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals("ok", snapshot.mlEvidenceStatus)
        assertEquals("metric_v2", snapshot.mlEvidenceSchema)
        assertEquals("provided", snapshot.mlEvidenceImageStatus)
        assertEquals(METRIC_EVIDENCE_V2_OUTPUT_NAMES.size, snapshot.mlEvidenceOutputs.size)
        assertEquals(13.25f, snapshot.mlEvidenceOutputs["top_ray_error_m"] ?: Float.NaN, 1e-6f)
        assertEquals(22.25f, snapshot.mlEvidenceOutputs["height_reliability"] ?: Float.NaN, 1e-6f)
    }

    @Test
    fun fromJsonFallsBackToServerDebugMetricEvidenceOutputs() {
        val debug = JSONObject().apply {
            put("pose_status", "ok")
            put("correction_reason", "root_constrained")
            put("ml_evidence_status", "ok")
            put("ml_evidence_schema", "metric_v2")
            put("ml_evidence_image_status", "provided")
            put("ml_height_reliability", 0.91)
            put("ml_distance_reliability", 0.82)
            put("ml_top_ray_error_m", -0.08)
        }

