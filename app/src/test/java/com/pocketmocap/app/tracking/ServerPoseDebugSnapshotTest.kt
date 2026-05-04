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

        val snapshot = ServerPoseDebugSnapshot.fromJson(debug)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals("metric_v2", snapshot.mlEvidenceSchema)
        assertEquals(3, snapshot.mlEvidenceOutputs.size)
        assertEquals(0.91f, snapshot.mlEvidenceOutputs["height_reliability"] ?: Float.NaN, 1e-6f)
        assertEquals(0.82f, snapshot.mlEvidenceOutputs["distance_reliability"] ?: Float.NaN, 1e-6f)
        assertEquals(-0.08f, snapshot.mlEvidenceOutputs["top_ray_error_m"] ?: Float.NaN, 1e-6f)
    }

    @Test
    fun fromJsonPreservesMetricEvidenceWhenServerDebugIsMissing() {
        val outputs = JSONObject().apply {
            put("height_reliability", 0.88)
            put("height_correction_delta_m", 0.06)
        }
        val mlEvidence = JSONObject().apply {
            put("status", "ok")
            put("schema", "metric_v2")
            put("outputs", outputs)
        }

        val snapshot = ServerPoseDebugSnapshot.fromJson(json = null, mlEvidenceJson = mlEvidence)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals("unknown", snapshot.poseStatus)
        assertEquals("metric_v2", snapshot.mlEvidenceSchema)
        assertEquals(0.88f, snapshot.mlEvidenceOutputs["height_reliability"] ?: Float.NaN, 1e-6f)
        assertEquals(0.06f, snapshot.mlEvidenceOutputs["height_correction_delta_m"] ?: Float.NaN, 1e-6f)
    }

    @Test
    fun fromJsonPreservesServerFirstV2MetricAuthorityFields() {
        val debug = JSONObject().apply {
            put("pose_status", "ok")
            put("correction_reason", "height_and_root_constrained")
            put("constraint_confidence", 0.72)
            put("scale_applied", 1.18)
            put("root_translation_m", 0.42)
            put("constrained_height_m", 1.82)
            put("constrained_distance_m", 2.36)
            put("ml_visual_usable", 1)
            put("ml_dlt_metric_bad", "true")
            put("ml_height_target_source", "ml_corrected_witnesses")
            put("ml_distance_target_source", "held_distance_target")
            put("ml_distance_hold_active", 0)
            put("ml_dlt_weight_scale", 0.25)
            put("height_target_weight", 72.0)
            put("height_prior_weight", 0.24)
            put("height_smooth_weight", 0.50)
            put("distance_target_weight", 8.0)
            put("distance_prior_weight", 0.20)
            put("distance_smooth_weight", 0.70)
            put("distance_local_authority", 0.83)
        }

        val snapshot = ServerPoseDebugSnapshot.fromJson(debug)

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(true, snapshot.mlVisualUsable)
        assertEquals(true, snapshot.mlDltMetricBad)
        assertEquals("ml_corrected_witnesses", snapshot.mlHeightTargetSource)
        assertEquals("held_distance_target", snapshot.mlDistanceTargetSource)
        assertEquals(false, snapshot.mlDistanceHoldActive)
        assertEquals(0.25f, snapshot.mlDltWeightScale, 1e-6f)
        assertEquals(72.0f, snapshot.heightTargetWeight, 1e-6f)
        assertEquals(0.24f, snapshot.heightPriorWeight, 1e-6f)
        assertEquals(0.50f, snapshot.heightSmoothWeight, 1e-6f)
        assertEquals(8.0f, snapshot.distanceTargetWeight, 1e-6f)
        assertEquals(0.20f, snapshot.distancePriorWeight, 1e-6f)
        assertEquals(0.70f, snapshot.distanceSmoothWeight, 1e-6f)
        assertEquals(0.83f, snapshot.distanceLocalAuthority, 1e-6f)
        assertTrue(snapshot.hasV2MetricAuthority())
        assertEquals(1.82f, snapshot.authoritativeHeightMetersOrNull() ?: Float.NaN, 1e-6f)
        assertEquals(2.36f, snapshot.authoritativeDistanceMetersOrNull() ?: Float.NaN, 1e-6f)
    }

    @Test
    fun canonicalMetricPoseIsPreferredForAuthoritativeDisplayMetrics() {
        val debug = JSONObject().apply {
            put("pose_status", "ok")
            put("correction_reason", "height_and_root_constrained")
            put("constrained_height_m", 1.70)
            put("constrained_distance_m", 3.20)
            put("metric_pose_status", "ok")
            put("metric_pose_reject_reason", "none")
            put("metric_pose_frame", "camera_floor_metric_v1")
            put("metric_root_x_m", 0.12)
            put("metric_root_y_m", 0.92)
            put("metric_root_z_m", -2.54)
            put("metric_root_distance_m", 2.55)
            put("metric_foot_midpoint_x_m", 0.10)
            put("metric_foot_midpoint_z_m", -2.48)
            put("metric_body_height_m", 1.82)
            put("metric_body_scale_locked", true)
            put("metric_bone_scale_source", "stable_skeleton")
            put("metric_pose_jitter_scale_m", 0.012)
            put("metric_pose_jitter_root_m", 0.045)
        }

        val snapshot = ServerPoseDebugSnapshot.fromJson(debug)

        assertNotNull(snapshot)
        snapshot!!
        assertTrue(snapshot.hasCanonicalMetricPose())
        assertEquals("camera_floor_metric_v1", snapshot.metricPoseFrame)
        assertEquals(true, snapshot.metricBodyScaleLocked)
        assertEquals("stable_skeleton", snapshot.metricBoneScaleSource)
        assertEquals(1.82f, snapshot.authoritativeHeightMetersOrNull() ?: Float.NaN, 1e-6f)
        assertEquals(2.55f, snapshot.authoritativeDistanceMetersOrNull() ?: Float.NaN, 1e-6f)
    }

    @Test
    fun rejectedCanonicalMetricPoseDoesNotClaimCanonicalAuthority() {
        val debug = JSONObject().apply {
            put("pose_status", "ok")
            put("metric_pose_status", "rejected")
            put("metric_pose_reject_reason", "upper_body_missing")
            put("metric_pose_frame", "camera_floor_metric_v1")
            put("metric_body_height_m", 1.82)
            put("metric_root_distance_m", 2.55)
        }

        val snapshot = ServerPoseDebugSnapshot.fromJson(debug)

        assertNotNull(snapshot)
        snapshot!!
        assertFalse(snapshot.hasCanonicalMetricPose())
        assertNull(snapshot.authoritativeHeightMetersOrNull())
        assertNull(snapshot.authoritativeDistanceMetersOrNull())
    }

    @Test
    fun heldCanonicalMetricPoseStillClaimsCanonicalAuthority() {
        val debug = JSONObject().apply {
            put("pose_status", "ok")
            put("metric_pose_status", "hold_previous")
            put("metric_pose_reject_reason", "root_distance_no_target")
            put("metric_pose_frame", "camera_floor_metric_v1")
            put("metric_body_height_m", 1.82)
            put("metric_root_distance_m", 2.55)
        }

        val snapshot = ServerPoseDebugSnapshot.fromJson(debug)

        assertNotNull(snapshot)
        snapshot!!
        assertTrue(snapshot.hasCanonicalMetricPose())
        assertEquals(1.82f, snapshot.authoritativeHeightMetersOrNull() ?: Float.NaN, 1e-6f)
        assertEquals(2.55f, snapshot.authoritativeDistanceMetersOrNull() ?: Float.NaN, 1e-6f)
    }

    @Test
    fun v2HeightAuthorityPrefersTargetWhenConstrainedHeightDriftsLow() {
        val debug = JSONObject().apply {
            put("pose_status", "ok")
            put("constraint_confidence", 0.72)
            put("scale_applied", 1.18)
            put("root_translation_m", 0.42)
            put("constrained_height_m", 1.71)
            put("ar_target_height_m", 1.81)
            put("ml_visual_usable", true)
            put("ml_dlt_metric_bad", true)
        }

        val snapshot = ServerPoseDebugSnapshot.fromJson(debug)

        assertNotNull(snapshot)
        snapshot!!
        assertTrue(snapshot.hasV2MetricAuthority())
        assertEquals(1.81f, snapshot.authoritativeHeightMetersOrNull() ?: Float.NaN, 1e-6f)
    }

