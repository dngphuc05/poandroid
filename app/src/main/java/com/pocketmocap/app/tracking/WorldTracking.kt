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
    val mlVisualUsable: Boolean? = null,
    val mlDltMetricBad: Boolean? = null,
    val mlHeightTargetSource: String = "",
    val mlDistanceTargetSource: String = "",
    val mlDistanceHoldActive: Boolean? = null,
    val mlDltWeightScale: Float = Float.NaN,
    val heightTargetWeight: Float = Float.NaN,
    val heightPriorWeight: Float = Float.NaN,
    val heightSmoothWeight: Float = Float.NaN,
    val distanceTargetWeight: Float = Float.NaN,
    val distancePriorWeight: Float = Float.NaN,
    val distanceSmoothWeight: Float = Float.NaN,
    val distanceLocalAuthority: Float = Float.NaN,
    val heightTargetAdmission: String = "",
    val heightTargetAdmissionReason: String = "",
    val heightGeometrySuspicious: Boolean? = null,
    val heightWitnessCount: Float = Float.NaN,
    val heightWitnessMedianMeters: Float = Float.NaN,
    val heightWitnessSpreadMeters: Float = Float.NaN,
    val heightCorrectedVsTopDeltaMeters: Float = Float.NaN,
    val heightCorrectedVsHipDeltaMeters: Float = Float.NaN,
    val heightCorrectedVsPixelDeltaMeters: Float = Float.NaN,
    val heightCorrectedVsTorsoDeltaMeters: Float = Float.NaN,
    val heightTargetBeforeGateMeters: Float = Float.NaN,
    val heightTargetAfterGateMeters: Float = Float.NaN,
    val heightMemoryUpdate: String = "",
    val heightMemoryReason: String = "",
    val heightMemoryAgeFrames: Float = Float.NaN,
    val heightMemoryTargetMeters: Float = Float.NaN,
    val wlsHeightMeters: Float = Float.NaN,
    val postStableSkeletonHeightMeters: Float = Float.NaN,
    val finalSmoothedHeightMeters: Float = Float.NaN,
    val heightLossStage: String = "",
    val distanceTargetAdmission: String = "",
    val distanceTargetAdmissionReason: String = "",
    val distanceGeometrySuspicious: Boolean? = null,
    val distanceWitnessCount: Float = Float.NaN,
    val distanceWitnessMedianMeters: Float = Float.NaN,
    val distanceWitnessSpreadMeters: Float = Float.NaN,
    val distanceVsPreviousTargetDeltaMeters: Float = Float.NaN,
    val distanceFootRoiDisagreementMeters: Float = Float.NaN,
    val distanceFootRelativeDisagreementMeters: Float = Float.NaN,
    val distanceCorrectedVsFootDeltaMeters: Float = Float.NaN,
    val distanceTargetBeforeGateMeters: Float = Float.NaN,
    val distanceTargetAfterGateMeters: Float = Float.NaN,
    val distanceMemoryUpdate: String = "",
    val distanceMemoryReason: String = "",
    val distanceMemoryAgeFrames: Float = Float.NaN,
    val distanceMemoryTargetMeters: Float = Float.NaN,

    val factorGraphActive: Float = Float.NaN,
    val factorGraphStatus: String = "",
    val factorGraphCostBefore: Float = Float.NaN,
    val factorGraphCostAfter: Float = Float.NaN,
    val factorGraphScaleDelta: Float = Float.NaN,
    val factorGraphYawDegrees: Float = Float.NaN,
    val factorGraphRootDxMeters: Float = Float.NaN,
    val factorGraphRootDzMeters: Float = Float.NaN,
    val factorGraphLeftFootDzMeters: Float = Float.NaN,
    val factorGraphRightFootDzMeters: Float = Float.NaN,
    val factorGraphTargetDistanceMeters: Float = Float.NaN,
    val factorGraphTargetHeightMeters: Float = Float.NaN,
    val factorGraphFactorSummary: String = "",
    val metricPoseStatus: String = "",
    val metricPoseRejectReason: String = "",
    val metricPoseFrame: String = "",
    val metricRootXMeters: Float = Float.NaN,
    val metricRootYMeters: Float = Float.NaN,
    val metricRootZMeters: Float = Float.NaN,
    val metricRootDistanceMeters: Float = Float.NaN,
    val metricFootMidpointXMeters: Float = Float.NaN,
    val metricFootMidpointZMeters: Float = Float.NaN,
    val metricBodyHeightMeters: Float = Float.NaN,
    val metricBodyScaleLocked: Boolean? = null,
    val metricBoneScaleSource: String = "",
    val metricPoseJitterScaleMeters: Float = Float.NaN,
    val metricPoseJitterRootMeters: Float = Float.NaN,
    val poseJointsFrame: String = "",
    val poseJointsNormalized: Float = Float.NaN,
    val poseJointsNormalizationScale: Float = Float.NaN,
    val poseLifterStatus: String = "",
    val poseLifterModelPath: String = "",
    val poseLifterHiddenJointCount: Float = Float.NaN,
    val poseLifterMeanConfidence: Float = Float.NaN,
    val poseLifterAppliedJointCount: Float = Float.NaN,
    val poseLifterRejectReason: String = "",
    val stableSkeletonLearningEnabled: Boolean? = null,
    val stableSkeletonResetReason: String = "",
    val mlEvidenceStatus: String = "",
    val mlEvidenceHeightSigmaMeters: Float = Float.NaN,
    val mlEvidenceDistanceSigmaMeters: Float = Float.NaN,
    val mlEvidenceMaskEndpointConfidence: Float = Float.NaN,
    val mlEvidenceVisibleBodyFraction: Float = Float.NaN,
    val mlEvidenceFootContactProbability: Float = Float.NaN,
    val mlEvidenceDebug: String = "",
    val mlEvidenceImageStatus: String = "",
    val mlImageWidthPx: Int? = null,
    val mlImageHeightPx: Int? = null,
    val mlImageSourceWidthPx: Int? = null,
    val mlImageSourceHeightPx: Int? = null,
    val mlImageCropLeftPx: Int? = null,
    val mlImageCropTopPx: Int? = null,
    val mlImageCropWidthPx: Int? = null,
    val mlImageCropHeightPx: Int? = null,
    val mlImageJpegQuality: Int? = null,
    val mlImageCropPadRatio: Float = Float.NaN,
    val mlEvidenceSchema: String = "",
    val mlEvidenceOutputs: Map<String, Float> = emptyMap(),
    val serverSceneMetricsReceived: Boolean? = null,
    val serverSceneMetricsAccepted: Boolean? = null,
    val serverSceneMetricsSource: String = "",
    val serverSceneMetricsFloorSource: String = "",
    val serverSceneMetricsFilterReason: String = "",
    val serverSceneMetricsConfidence: Float = Float.NaN,
) {
    companion object {
        fun fromJson(
            json: JSONObject?,
            fallbackScene: SceneMetricSnapshot? = null,
            mlEvidenceJson: JSONObject? = null,
        ): ServerPoseDebugSnapshot? {
            if (json == null && mlEvidenceJson == null) return null
            val debugJson = json ?: JSONObject()
            return ServerPoseDebugSnapshot(
                poseStatus = debugJson.optString("pose_status", "unknown"),
                correctionReason = debugJson.optString("correction_reason", "unknown"),
                constraintConfidence = debugJson.optDouble("constraint_confidence", Double.NaN).toFloat(),
                scaleApplied = debugJson.optDouble("scale_applied", Double.NaN).toFloat(),
                rootTranslationMeters = debugJson.optDouble("root_translation_m", Double.NaN).toFloat(),
                rawHeightMeters = debugJson.optDouble("raw_height_m", Double.NaN).toFloat(),
                rawDistanceMeters = debugJson.optDouble("raw_distance_m", Double.NaN).toFloat(),
                preSkeletonConstrainedHeightMeters = debugJson.optDouble(
                    "pre_skeleton_constrained_height_m",
                    Double.NaN,
                ).toFloat(),
                preSkeletonConstrainedDistanceMeters = debugJson.optDouble(
                    "pre_skeleton_constrained_distance_m",
                    Double.NaN,
                ).toFloat(),
                constrainedHeightMeters = debugJson.optDouble("constrained_height_m", Double.NaN).toFloat(),
                constrainedDistanceMeters = debugJson.optDouble("constrained_distance_m", Double.NaN).toFloat(),
                arTargetHeightMeters = debugJson.optDouble(
                    "ar_target_height_m",
                    fallbackScene?.correctedHeightMeters?.takeIf { it.isFinite() }?.toDouble()
                        ?: fallbackScene?.bodyHeightMeters?.toDouble()
                        ?: Double.NaN,
                ).toFloat(),
                arTargetDistanceMeters = debugJson.optDouble(
                    "ar_target_distance_m",
                    fallbackScene?.correctedDistanceMeters?.takeIf { it.isFinite() }?.toDouble()
                        ?: fallbackScene?.distanceMeters?.toDouble()
                        ?: Double.NaN,
                ).toFloat(),
                mlVisualUsable = debugJson.optOptionalBooleanLike("ml_visual_usable"),
                mlDltMetricBad = debugJson.optOptionalBooleanLike("ml_dlt_metric_bad"),
                mlHeightTargetSource = debugJson.optString("ml_height_target_source", ""),
                mlDistanceTargetSource = debugJson.optString("ml_distance_target_source", ""),
                mlDistanceHoldActive = debugJson.optOptionalBooleanLike("ml_distance_hold_active"),
                mlDltWeightScale = debugJson.optDouble("ml_dlt_weight_scale", Double.NaN).toFloat(),
                heightTargetWeight = debugJson.optDouble("height_target_weight", Double.NaN).toFloat(),
                heightPriorWeight = debugJson.optDouble("height_prior_weight", Double.NaN).toFloat(),
                heightSmoothWeight = debugJson.optDouble("height_smooth_weight", Double.NaN).toFloat(),
                distanceTargetWeight = debugJson.optDouble("distance_target_weight", Double.NaN).toFloat(),
                distancePriorWeight = debugJson.optDouble("distance_prior_weight", Double.NaN).toFloat(),
                distanceSmoothWeight = debugJson.optDouble("distance_smooth_weight", Double.NaN).toFloat(),
                distanceLocalAuthority = debugJson.optDouble("distance_local_authority", Double.NaN).toFloat(),
                heightTargetAdmission = debugJson.optString("height_target_admission", ""),
                heightTargetAdmissionReason = debugJson.optString("height_target_admission_reason", ""),
                heightGeometrySuspicious = debugJson.optOptionalBooleanLike("height_geometry_suspicious"),
                heightWitnessCount = debugJson.optDouble("height_witness_count", Double.NaN).toFloat(),
                heightWitnessMedianMeters = debugJson.optDouble("height_witness_median_m", Double.NaN).toFloat(),
                heightWitnessSpreadMeters = debugJson.optDouble("height_witness_spread_m", Double.NaN).toFloat(),
                heightCorrectedVsTopDeltaMeters = debugJson.optDouble("height_corrected_vs_top_delta_m", Double.NaN).toFloat(),
                heightCorrectedVsHipDeltaMeters = debugJson.optDouble("height_corrected_vs_hip_delta_m", Double.NaN).toFloat(),
                heightCorrectedVsPixelDeltaMeters = debugJson.optDouble("height_corrected_vs_pixel_delta_m", Double.NaN).toFloat(),
                heightCorrectedVsTorsoDeltaMeters = debugJson.optDouble("height_corrected_vs_torso_delta_m", Double.NaN).toFloat(),
                heightTargetBeforeGateMeters = debugJson.optDouble("height_target_before_gate_m", Double.NaN).toFloat(),
                heightTargetAfterGateMeters = debugJson.optDouble("height_target_after_gate_m", Double.NaN).toFloat(),
                heightMemoryUpdate = debugJson.optString("height_memory_update", ""),
                heightMemoryReason = debugJson.optString("height_memory_reason", ""),
                heightMemoryAgeFrames = debugJson.optDouble("height_memory_age_frames", Double.NaN).toFloat(),
                heightMemoryTargetMeters = debugJson.optDouble("height_memory_target_m", Double.NaN).toFloat(),
                wlsHeightMeters = debugJson.optDouble("wls_height_m", Double.NaN).toFloat(),
                postStableSkeletonHeightMeters = debugJson.optDouble("post_stable_skeleton_height_m", Double.NaN).toFloat(),
                finalSmoothedHeightMeters = debugJson.optDouble("final_smoothed_height_m", Double.NaN).toFloat(),
                heightLossStage = debugJson.optString("height_loss_stage", ""),
                distanceTargetAdmission = debugJson.optString("distance_target_admission", ""),
                distanceTargetAdmissionReason = debugJson.optString("distance_target_admission_reason", ""),
                distanceGeometrySuspicious = debugJson.optOptionalBooleanLike("distance_geometry_suspicious"),
                distanceWitnessCount = debugJson.optDouble("distance_witness_count", Double.NaN).toFloat(),
                distanceWitnessMedianMeters = debugJson.optDouble("distance_witness_median_m", Double.NaN).toFloat(),
                distanceWitnessSpreadMeters = debugJson.optDouble("distance_witness_spread_m", Double.NaN).toFloat(),
                distanceVsPreviousTargetDeltaMeters = debugJson.optDouble("distance_vs_previous_target_delta_m", Double.NaN).toFloat(),
                distanceFootRoiDisagreementMeters = debugJson.optDouble("distance_foot_roi_disagreement_m", Double.NaN).toFloat(),
                distanceFootRelativeDisagreementMeters = debugJson.optDouble("distance_foot_relative_disagreement_m", Double.NaN).toFloat(),
                distanceCorrectedVsFootDeltaMeters = debugJson.optDouble("distance_corrected_vs_foot_delta_m", Double.NaN).toFloat(),
                distanceTargetBeforeGateMeters = debugJson.optDouble("distance_target_before_gate_m", Double.NaN).toFloat(),
                distanceTargetAfterGateMeters = debugJson.optDouble("distance_target_after_gate_m", Double.NaN).toFloat(),
                distanceMemoryUpdate = debugJson.optString("distance_memory_update", ""),
                distanceMemoryReason = debugJson.optString("distance_memory_reason", ""),
                distanceMemoryAgeFrames = debugJson.optDouble("distance_memory_age_frames", Double.NaN).toFloat(),
                distanceMemoryTargetMeters = debugJson.optDouble("distance_memory_target_m", Double.NaN).toFloat(),

                factorGraphActive = debugJson.optDouble("factor_graph_active", Double.NaN).toFloat(),
                factorGraphStatus = debugJson.optString("factor_graph_status", ""),
                factorGraphCostBefore = debugJson.optDouble("factor_graph_cost_before", Double.NaN).toFloat(),
                factorGraphCostAfter = debugJson.optDouble("factor_graph_cost_after", Double.NaN).toFloat(),
                factorGraphScaleDelta = debugJson.optDouble("factor_graph_scale_delta", Double.NaN).toFloat(),
                factorGraphYawDegrees = debugJson.optDouble("factor_graph_yaw_deg", Double.NaN).toFloat(),
                factorGraphRootDxMeters = debugJson.optDouble("factor_graph_root_dx_m", Double.NaN).toFloat(),
                factorGraphRootDzMeters = debugJson.optDouble("factor_graph_root_dz_m", Double.NaN).toFloat(),
                factorGraphLeftFootDzMeters = debugJson.optDouble("factor_graph_left_foot_dz_m", Double.NaN).toFloat(),
                factorGraphRightFootDzMeters = debugJson.optDouble("factor_graph_right_foot_dz_m", Double.NaN).toFloat(),
                factorGraphTargetDistanceMeters = debugJson.optDouble("factor_graph_target_distance_m", Double.NaN).toFloat(),
                factorGraphTargetHeightMeters = debugJson.optDouble("factor_graph_target_height_m", Double.NaN).toFloat(),
                factorGraphFactorSummary = debugJson.optString("factor_graph_factor_summary", ""),
                metricPoseStatus = debugJson.optString("metric_pose_status", ""),
                metricPoseRejectReason = debugJson.optString("metric_pose_reject_reason", ""),
                metricPoseFrame = debugJson.optString("metric_pose_frame", ""),
                metricRootXMeters = debugJson.optDouble("metric_root_x_m", Double.NaN).toFloat(),
                metricRootYMeters = debugJson.optDouble("metric_root_y_m", Double.NaN).toFloat(),
                metricRootZMeters = debugJson.optDouble("metric_root_z_m", Double.NaN).toFloat(),
                metricRootDistanceMeters = debugJson.optDouble("metric_root_distance_m", Double.NaN).toFloat(),
                metricFootMidpointXMeters = debugJson.optDouble("metric_foot_midpoint_x_m", Double.NaN).toFloat(),
                metricFootMidpointZMeters = debugJson.optDouble("metric_foot_midpoint_z_m", Double.NaN).toFloat(),
                metricBodyHeightMeters = debugJson.optDouble("metric_body_height_m", Double.NaN).toFloat(),
                metricBodyScaleLocked = debugJson.optOptionalBooleanLike("metric_body_scale_locked"),
                metricBoneScaleSource = debugJson.optString("metric_bone_scale_source", ""),
                metricPoseJitterScaleMeters = debugJson.optDouble("metric_pose_jitter_scale_m", Double.NaN).toFloat(),
                metricPoseJitterRootMeters = debugJson.optDouble("metric_pose_jitter_root_m", Double.NaN).toFloat(),
                poseJointsFrame = debugJson.optString("pose_joints_frame", ""),
                poseJointsNormalized = debugJson.optDouble("pose_joints_normalized", Double.NaN).toFloat(),
                poseJointsNormalizationScale = debugJson.optDouble("pose_joints_normalization_scale", Double.NaN).toFloat(),
                poseLifterStatus = debugJson.optString("pose_lifter_status", ""),
                poseLifterModelPath = debugJson.optString("pose_lifter_model_path", ""),
                poseLifterHiddenJointCount = debugJson.optDouble("pose_lifter_hidden_joint_count", Double.NaN).toFloat(),
                poseLifterMeanConfidence = debugJson.optDouble("pose_lifter_mean_confidence", Double.NaN).toFloat(),
                poseLifterAppliedJointCount = debugJson.optDouble("pose_lifter_applied_joint_count", Double.NaN).toFloat(),
                poseLifterRejectReason = debugJson.optString("pose_lifter_reject_reason", ""),
                stableSkeletonLearningEnabled = debugJson.optOptionalBooleanLike(
                    "stable_skeleton_learning_enabled",
                ),
                stableSkeletonResetReason = debugJson.optString("stable_skeleton_reset_reason", ""),
                mlEvidenceStatus = mlEvidenceJson?.optString("status")
                    ?: debugJson.optString("ml_evidence_status", ""),
                mlEvidenceHeightSigmaMeters = (
                    mlEvidenceJson?.optDouble("height_sigma_m", Double.NaN)
                        ?: debugJson.optDouble("ml_evidence_height_sigma_m", Double.NaN)
                    ).toFloat(),
                mlEvidenceDistanceSigmaMeters = (
                    mlEvidenceJson?.optDouble("distance_sigma_m", Double.NaN)
                        ?: debugJson.optDouble("ml_evidence_distance_sigma_m", Double.NaN)
                    ).toFloat(),
                mlEvidenceMaskEndpointConfidence = (
                    mlEvidenceJson?.optDouble("mask_endpoint_confidence", Double.NaN)
                        ?: debugJson.optDouble("ml_evidence_mask_endpoint_confidence", Double.NaN)
                    ).toFloat(),
                mlEvidenceVisibleBodyFraction = (
                    mlEvidenceJson?.optDouble("visible_body_fraction", Double.NaN)
                        ?: debugJson.optDouble("ml_evidence_visible_body_fraction", Double.NaN)
                    ).toFloat(),
                mlEvidenceFootContactProbability = (
                    mlEvidenceJson?.optDouble("foot_contact_probability", Double.NaN)
                        ?: debugJson.optDouble("ml_evidence_foot_contact_probability", Double.NaN)
                    ).toFloat(),
                mlEvidenceDebug = mlEvidenceJson?.optString("debug")
                    ?: debugJson.optString("ml_evidence_debug", ""),
                mlEvidenceImageStatus = debugJson.optString("ml_evidence_image_status", ""),
                mlImageWidthPx = debugJson.optOptionalInt("ml_image_width_px"),
                mlImageHeightPx = debugJson.optOptionalInt("ml_image_height_px"),
                mlImageSourceWidthPx = debugJson.optOptionalInt("ml_image_source_width_px"),
                mlImageSourceHeightPx = debugJson.optOptionalInt("ml_image_source_height_px"),
                mlImageCropLeftPx = debugJson.optOptionalInt("ml_image_crop_left_px"),
                mlImageCropTopPx = debugJson.optOptionalInt("ml_image_crop_top_px"),
                mlImageCropWidthPx = debugJson.optOptionalInt("ml_image_crop_width_px"),
                mlImageCropHeightPx = debugJson.optOptionalInt("ml_image_crop_height_px"),
                mlImageJpegQuality = debugJson.optOptionalInt("ml_image_jpeg_quality"),
                mlImageCropPadRatio = debugJson.optDouble("ml_image_crop_pad_ratio", Double.NaN).toFloat(),
                mlEvidenceSchema = mlEvidenceJson?.optString("schema")
                    ?: debugJson.optString("ml_evidence_schema", ""),
                mlEvidenceOutputs = parseMetricEvidenceOutputs(debugJson, mlEvidenceJson),
                serverSceneMetricsReceived = debugJson.optOptionalBooleanLike("scene_metrics_received"),
                serverSceneMetricsAccepted = debugJson.optOptionalBooleanLike("scene_metrics_accepted"),
                serverSceneMetricsSource = debugJson.optString("scene_metrics_source", ""),
                serverSceneMetricsFloorSource = debugJson.optString("scene_metrics_floor_source", ""),
                serverSceneMetricsFilterReason = debugJson.optString("scene_metrics_filter_reason", ""),
                serverSceneMetricsConfidence = debugJson.optDouble("scene_metrics_confidence", Double.NaN).toFloat(),
            )
        }

        private fun parseMetricEvidenceOutputs(
            json: JSONObject?,
            mlEvidenceJson: JSONObject?,
        ): Map<String, Float> {
            val outputJson = mlEvidenceJson?.optJSONObject("outputs")
            val values = linkedMapOf<String, Float>()
            for (name in METRIC_EVIDENCE_V2_OUTPUT_NAMES) {
                val value = outputJson?.optDouble(name, Double.NaN)
                    ?: json?.optDouble("ml_$name", Double.NaN)
                    ?: Double.NaN
                if (value.isFinite()) {
                    values[name] = value.toFloat()
                }
            }
            return values
        }
    }
}

fun ServerPoseDebugSnapshot.hasCanonicalMetricPose(): Boolean =
    metricPoseStatus in setOf("ok", "hold_previous") &&
        metricPoseFrame == "camera_floor_metric_v1" &&
        metricBodyHeightMeters.validAuthoritativeHeightOrNull() != null &&
        metricRootDistanceMeters.validAuthoritativeDistanceOrNull() != null

fun ServerPoseDebugSnapshot.hasV2MetricAuthority(): Boolean {
