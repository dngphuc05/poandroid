package com.pocketmocap.app.tracking

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private const val MIN_AUTHORITATIVE_HEIGHT_METERS = 1.05f
private const val MAX_AUTHORITATIVE_HEIGHT_METERS = 2.35f
private const val MAX_CONSTRAINED_HEIGHT_TARGET_DELTA_METERS = 0.06f
private const val MIN_AUTHORITATIVE_DISTANCE_METERS = 0.35f
private const val MAX_AUTHORITATIVE_DISTANCE_METERS = 12.0f
private const val MAX_CONSTRAINED_DISTANCE_TARGET_DELTA_METERS = 0.35f
private const val MAX_CONSTRAINED_DISTANCE_TARGET_RATIO = 0.12f

val METRIC_EVIDENCE_V2_OUTPUT_NAMES = listOf(
    "top_endpoint_confidence",
    "foot_endpoint_confidence",
    "mask_endpoint_confidence",
    "visible_body_fraction",
    "body_clip_risk",
    "left_foot_contact_probability",
    "right_foot_contact_probability",
    "top_ray_sigma_m",
    "hip_geometry_sigma_m",
    "pixel_span_sigma_m",
    "torso_sigma_m",
    "foot_plane_sigma_m",
    "dlt_joint_quality",
    "top_ray_error_m",
    "hip_geometry_error_m",
    "pixel_span_error_m",
    "torso_error_m",
    "foot_plane_distance_error_m",
    "dlt_height_error_m",
    "dlt_distance_error_m",
    "height_correction_delta_m",
    "distance_correction_delta_m",
    "height_reliability",
    "distance_reliability",
)

data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fx", fx.toDouble())
        put("fy", fy.toDouble())
        put("cx", cx.toDouble())
        put("cy", cy.toDouble())
        put("image_width", imageWidth)
        put("image_height", imageHeight)
    }
}

data class DepthMapSnapshot(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray,
)

data class WorldTrackingSnapshot(
    val timestampUs: Long,
    val source: String,
    val trackingState: String,
    val cameraPosition: FloatArray? = null,
    val cameraRotation: FloatArray? = null,
    val groundPoint: FloatArray? = null,
    val groundNormal: FloatArray? = null,
    val cameraHeightMeters: Float = Float.NaN,
    val subjectDistanceMeters: Float = Float.NaN,
    val subjectHeightMeters: Float = Float.NaN,
    val lateralOffsetMeters: Float = Float.NaN,
    val floorPitchDegrees: Float = Float.NaN,
    val confidence: Float = 0f,
    val rawCameraHeightMeters: Float = Float.NaN,
    val floorSource: String = "",
    val floorLockState: String = "",
    val intrinsics: CameraIntrinsics? = null,
    val depthMap: DepthMapSnapshot? = null,
) {
    val hasGroundPlane: Boolean
        get() = groundPoint?.size == 3 && groundNormal?.size == 3

    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp_us", timestampUs)
        put("source", source)
        put("tracking_state", trackingState)
        put("confidence", confidence.toDouble())
        putFinite("camera_height_m", cameraHeightMeters)
        putFinite("subject_distance_m", subjectDistanceMeters)
        putFinite("subject_height_m", subjectHeightMeters)
        putFinite("lateral_offset_m", lateralOffsetMeters)
        putFinite("floor_pitch_deg", floorPitchDegrees)
        putFinite("raw_camera_height_m", rawCameraHeightMeters)
        if (floorSource.isNotBlank()) put("floor_source", floorSource)
        if (floorLockState.isNotBlank()) put("floor_lock_state", floorLockState)
        cameraPosition?.takeIf { it.size >= 3 }?.let { put("camera_position_m", it.toJsonArray(3)) }
        cameraRotation?.takeIf { it.size >= 4 }?.let { put("camera_rotation_xyzw", it.toJsonArray(4)) }
        groundPoint?.takeIf { it.size >= 3 }?.let { put("ground_point_m", it.toJsonArray(3)) }
        groundNormal?.takeIf { it.size >= 3 }?.let { put("ground_normal", it.toJsonArray(3)) }
        intrinsics?.let { put("intrinsics", it.toJson()) }
    }
}

data class SceneMetricSnapshot(
    val source: String,
    val confidence: Float,
    val distanceMeters: Float,
    val bodyHeightMeters: Float,
    val cameraHeightMeters: Float,
    val floorPitchDegrees: Float,
    val lateralOffsetMeters: Float,
    val correctedDistanceMeters: Float = Float.NaN,
    val correctedHeightMeters: Float = Float.NaN,
    val correctedCameraHeightMeters: Float = Float.NaN,
    val localHeightCandidateMeters: Float = Float.NaN,
    val localHeightCandidateConfidence: Float = Float.NaN,
    val localHeightCandidateSource: String = "",
    val profileSubjectHeightMeters: Float = Float.NaN,
    val profileSubjectHeightConfidence: Float = Float.NaN,
    val profileSubjectHeightSource: String = "",
    val floorSource: String = "",
    val solverConfidence: Float = Float.NaN,
    val solverResidualMeters: Float = Float.NaN,
    val floorHeightBiasMeters: Float = Float.NaN,
    val depthScale: Float = Float.NaN,
    val depthOffsetMeters: Float = Float.NaN,
    val heightEndpointBiasMeters: Float = Float.NaN,
    val heightLockState: String = "",
    val distanceCandidateSpreadMeters: Float = Float.NaN,
    val heightCandidateSpreadMeters: Float = Float.NaN,
    val rawHipDepthDistanceMeters: Float = Float.NaN,
    val footPlaneDistanceMeters: Float = Float.NaN,
    val roiDistanceMeters: Float = Float.NaN,
    val topRayHeightMeters: Float = Float.NaN,
    val pixelSpanHeightMeters: Float = Float.NaN,
    val hipGeometryDistanceMeters: Float = Float.NaN,
    val hipGeometryHeightMeters: Float = Float.NaN,
    val torsoHeightMeters: Float = Float.NaN,
    val torsoResidualMeters: Float = Float.NaN,
    val groundedFootDistanceMeters: Float = Float.NaN,
    val footContactState: String = "",
    val leftFootRayFloorDistanceMeters: Float = Float.NaN,
    val rightFootRayFloorDistanceMeters: Float = Float.NaN,
    val feetMidpointFloorDistanceMeters: Float = Float.NaN,
    val nearestFootFloorDistanceMeters: Float = Float.NaN,
    val footRayFloorSpreadMeters: Float = Float.NaN,
    val topRayFloorHeightMeters: Float = Float.NaN,
    val visualTopScanYNorm: Float = Float.NaN,
    val visualTopScanConfidence: Float = Float.NaN,
    val visualTopLiftNorm: Float = Float.NaN,
    val distanceHeightGeometryResidualMeters: Float = Float.NaN,
    val rootHipRayFloorDistanceMeters: Float = Float.NaN,
    val boneLengthSpreadMeters: Float = Float.NaN,
    val bodyScaleConfidence: Float = Float.NaN,
    val distanceConfidence: Float = Float.NaN,
    val heightConfidence: Float = Float.NaN,
    val floorConfidence: Float = Float.NaN,
    val distanceState: String = "",
    val weightHip: Float = Float.NaN,
    val weightHead: Float = Float.NaN,
    val weightFoot: Float = Float.NaN,
    val weightTorso: Float = Float.NaN,
    val weightBone: Float = Float.NaN,
    val weightDepth: Float = Float.NaN,
    val weightRoi: Float = Float.NaN,
    val weightTemporal: Float = Float.NaN,
    val hipCenterXNorm: Float = Float.NaN,
    val hipCenterYNorm: Float = Float.NaN,
    val shoulderHipSpanNorm: Float = Float.NaN,
    val torsoSpanNorm: Float = Float.NaN,
    val bodyRoiHeightNorm: Float = Float.NaN,
    val relativeScaleDistanceMeters: Float = Float.NaN,
    val rejectedHipReason: String = "",
    val heightSeedTrusted: Boolean = false,
    val activeFactors: String = "",
    val bodyClipRisk: Float = Float.NaN,
    val topEndpointConfidence: Float = Float.NaN,
    val footEndpointConfidence: Float = Float.NaN,
    val maskEndpointConfidence: Float = Float.NaN,
    val experimentalHeightMeters: Float = Float.NaN,
    val experimentalHeightSigmaMeters: Float = Float.NaN,
    val experimentalHeightConfidence: Float = Float.NaN,
    val experimentalHeightState: String = "",
    val experimentalDistanceMeters: Float = Float.NaN,
    val experimentalDistanceSigmaMeters: Float = Float.NaN,
    val experimentalDistanceConfidence: Float = Float.NaN,
    val experimentalSolverCost: Float = Float.NaN,
    val experimentalSolverStatus: String = "",
    val experimentalFactorSummary: String = "",
    val baselineExperimentalHeightDeltaMeters: Float = Float.NaN,
    val baselineExperimentalDistanceDeltaMeters: Float = Float.NaN,
    val promotedSolverSource: String = "baseline",
    val rawKeypointGeometry: Map<String, Float> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source)
        put("confidence", confidence.toDouble())
        putFinite("distance_m", distanceMeters)
        putFinite("body_height_m", bodyHeightMeters)
        putFinite("camera_height_m", cameraHeightMeters)
        putFinite("floor_pitch_deg", floorPitchDegrees)
        putFinite("lateral_offset_m", lateralOffsetMeters)
        putFinite("corrected_distance_m", correctedDistanceMeters)
        putFinite("corrected_height_m", correctedHeightMeters)
        putFinite("corrected_camera_height_m", correctedCameraHeightMeters)
        putFinite("local_height_candidate_m", localHeightCandidateMeters)
        putFinite("local_height_candidate_confidence", localHeightCandidateConfidence)
        if (localHeightCandidateSource.isNotBlank()) put("local_height_candidate_source", localHeightCandidateSource)
        putFinite("profile_subject_height_m", profileSubjectHeightMeters)
        putFinite("profile_subject_height_confidence", profileSubjectHeightConfidence)
        if (profileSubjectHeightSource.isNotBlank()) put("profile_subject_height_source", profileSubjectHeightSource)
        if (floorSource.isNotBlank()) put("floor_source", floorSource)
        putFinite("solver_confidence", solverConfidence)
        putFinite("solver_residual_m", solverResidualMeters)
        putFinite("floor_height_bias_m", floorHeightBiasMeters)
        putFinite("depth_scale", depthScale)
        putFinite("depth_offset_m", depthOffsetMeters)
        putFinite("height_endpoint_bias_m", heightEndpointBiasMeters)
        if (heightLockState.isNotBlank()) put("height_lock_state", heightLockState)
        putFinite("distance_candidate_spread_m", distanceCandidateSpreadMeters)
        putFinite("height_candidate_spread_m", heightCandidateSpreadMeters)
        putFinite("raw_hip_depth_distance_m", rawHipDepthDistanceMeters)
        putFinite("foot_plane_distance_m", footPlaneDistanceMeters)
        putFinite("roi_distance_m", roiDistanceMeters)
        putFinite("top_ray_height_m", topRayHeightMeters)
        putFinite("pixel_span_height_m", pixelSpanHeightMeters)
        putFinite("hip_geometry_distance_m", hipGeometryDistanceMeters)
        putFinite("hip_geometry_height_m", hipGeometryHeightMeters)
        putFinite("torso_height_m", torsoHeightMeters)
        putFinite("torso_residual_m", torsoResidualMeters)
        putFinite("grounded_foot_distance_m", groundedFootDistanceMeters)
        if (footContactState.isNotBlank()) put("foot_contact_state", footContactState)
        putFinite("left_foot_ray_floor_distance_m", leftFootRayFloorDistanceMeters)
        putFinite("right_foot_ray_floor_distance_m", rightFootRayFloorDistanceMeters)
        putFinite("feet_midpoint_floor_distance_m", feetMidpointFloorDistanceMeters)
        putFinite("nearest_foot_floor_distance_m", nearestFootFloorDistanceMeters)
        putFinite("foot_ray_floor_spread_m", footRayFloorSpreadMeters)
        putFinite("top_ray_floor_height_m", topRayFloorHeightMeters)
        putFinite("visual_top_scan_y_norm", visualTopScanYNorm)
        putFinite("visual_top_scan_confidence", visualTopScanConfidence)
        putFinite("visual_top_lift_norm", visualTopLiftNorm)
        putFinite("distance_height_geometry_residual_m", distanceHeightGeometryResidualMeters)
        putFinite("root_hip_ray_floor_distance_m", rootHipRayFloorDistanceMeters)
        putFinite("bone_length_spread_m", boneLengthSpreadMeters)
        putFinite("body_scale_confidence", bodyScaleConfidence)
        putFinite("distance_confidence", distanceConfidence)
        putFinite("height_confidence", heightConfidence)
        putFinite("floor_confidence", floorConfidence)
        if (distanceState.isNotBlank()) put("distance_state", distanceState)
        putFinite("w_hip", weightHip)
        putFinite("w_head", weightHead)
        putFinite("w_foot", weightFoot)
        putFinite("w_torso", weightTorso)
        putFinite("w_bone", weightBone)
        putFinite("w_depth", weightDepth)
        putFinite("w_roi", weightRoi)
        putFinite("w_temporal", weightTemporal)
        putFinite("hip_center_x_norm", hipCenterXNorm)
        putFinite("hip_center_y_norm", hipCenterYNorm)
        putFinite("shoulder_hip_span_norm", shoulderHipSpanNorm)
        putFinite("torso_span_norm", torsoSpanNorm)
        putFinite("body_roi_height_norm", bodyRoiHeightNorm)
        putFinite("relative_scale_distance_m", relativeScaleDistanceMeters)
        if (rejectedHipReason.isNotBlank()) put("rejected_hip_reason", rejectedHipReason)
        put("height_seed_trusted", heightSeedTrusted)
        if (activeFactors.isNotBlank()) put("active_factors", activeFactors)
        putFinite("body_clip_risk", bodyClipRisk)
        putFinite("top_endpoint_confidence", topEndpointConfidence)
        putFinite("foot_endpoint_confidence", footEndpointConfidence)
        putFinite("mask_endpoint_confidence", maskEndpointConfidence)
        putFinite("experimental_height_m", experimentalHeightMeters)
        putFinite("experimental_height_sigma_m", experimentalHeightSigmaMeters)
        putFinite("experimental_height_confidence", experimentalHeightConfidence)
        if (experimentalHeightState.isNotBlank()) put("experimental_height_state", experimentalHeightState)
        putFinite("experimental_distance_m", experimentalDistanceMeters)
        putFinite("experimental_distance_sigma_m", experimentalDistanceSigmaMeters)
        putFinite("experimental_distance_confidence", experimentalDistanceConfidence)
        putFinite("experimental_solver_cost", experimentalSolverCost)
        if (experimentalSolverStatus.isNotBlank()) put("experimental_solver_status", experimentalSolverStatus)
        if (experimentalFactorSummary.isNotBlank()) put("experimental_factor_summary", experimentalFactorSummary)
        putFinite("baseline_experimental_height_delta_m", baselineExperimentalHeightDeltaMeters)
        putFinite("baseline_experimental_distance_delta_m", baselineExperimentalDistanceDeltaMeters)
        put("promoted_solver_source", promotedSolverSource)
        for ((key, value) in rawKeypointGeometry) {
            putFinite(key, value)
        }
    }
}

data class ServerPoseDebugSnapshot(
    val poseStatus: String,
    val correctionReason: String,
    val constraintConfidence: Float = Float.NaN,
    val scaleApplied: Float = Float.NaN,
    val rootTranslationMeters: Float = Float.NaN,
    val rawHeightMeters: Float = Float.NaN,
    val rawDistanceMeters: Float = Float.NaN,
    val preSkeletonConstrainedHeightMeters: Float = Float.NaN,
    val preSkeletonConstrainedDistanceMeters: Float = Float.NaN,
    val constrainedHeightMeters: Float = Float.NaN,
    val constrainedDistanceMeters: Float = Float.NaN,
    val arTargetHeightMeters: Float = Float.NaN,
    val arTargetDistanceMeters: Float = Float.NaN,
