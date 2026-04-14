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
