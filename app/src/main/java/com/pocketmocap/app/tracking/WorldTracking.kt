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

