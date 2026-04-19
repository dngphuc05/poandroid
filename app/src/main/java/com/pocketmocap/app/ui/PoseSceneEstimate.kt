package com.pocketmocap.app.ui

import com.pocketmocap.app.tracking.CameraIntrinsics
import com.pocketmocap.app.tracking.DepthMapSnapshot
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

private const val MIN_BODY_HEIGHT_METERS = 1.15f
private const val MAX_BODY_HEIGHT_METERS = 2.15f
private const val MIN_MEASURED_HEIGHT_METERS = 0.75f
private const val MAX_MEASURED_HEIGHT_METERS = 2.80f
private const val ASSUMED_VERTICAL_FOV_DEGREES = 56f
private const val DEFAULT_VIEWPORT_ASPECT = 9f / 16f
private const val HIP_HEIGHT_BODY_RATIO = 0.580f
private const val HEIGHT_LOCK_UPWARD_MARGIN_METERS = 0.030f
private const val HEIGHT_LOCK_DOWNWARD_MARGIN_METERS = 0.055f
private const val SUBJECT_HEIGHT_RETARGET_STOP_GAP_METERS = 0.008f
private const val MATURE_RETARGET_UPWARD_MARGIN_METERS = 0.080f
private const val INITIAL_TOP_CONFLICT_GAP_METERS = 0.105f
private const val INITIAL_TOP_FALLBACK_GAP_METERS = 0.120f
private const val DEFAULT_INITIAL_LOCK_FRAMES = 8
private const val CONFLICTED_INITIAL_LOCK_FRAMES = 30
private const val MIN_HEIGHT_ENDPOINT_BIAS_METERS = -0.025f
private const val MAX_HEIGHT_ENDPOINT_BIAS_METERS = 0.080f
private const val MAX_TOP_LOW_BIAS_MASK_GAP_METERS = 0.045f
private const val MAX_ENDPOINT_BIAS_SUPPORT_GAP_METERS = 0.110f
private const val STARTUP_HEIGHT_SPREAD_SUSPECT_METERS = 0.220f
private const val STARTUP_HEIGHT_SPREAD_QUARANTINE_METERS = 0.300f
private const val STARTUP_DISTANCE_SPREAD_MAX_METERS = 0.750f
private const val STARTUP_MIN_LOCK_CONFIDENCE = 0.500f
private const val STARTUP_HIGH_HIP_TOP_GAP_METERS = 0.100f
private const val STARTUP_COLLAPSED_WITNESS_GAP_METERS = 0.180f
private const val STARTUP_BAD_LOCK_CORRECTION_FRAMES = 72
private const val STARTUP_BAD_LOCK_CORRECTION_ALPHA = 0.220f
private const val STARTUP_BAD_LOCK_CORRECTION_MAX_STEP_METERS = 0.120f
private const val SPAN_BRACKET_MIN_BODY_SCALE_CONFIDENCE = 0.62f
private const val SPAN_BRACKET_MIN_TOP_LOCK_GAP_METERS = 0.025f
private const val SPAN_BRACKET_TOP_BELOW_LOCK_TOLERANCE_METERS = 0.045f
private const val SPAN_BRACKET_MIN_GAP_METERS = 0.26f
private const val SPAN_BRACKET_MAX_GAP_METERS = 0.72f
private const val SPAN_BRACKET_INTERPOLATION = 0.46f
private const val PIXEL_ONLY_BRACKET_HEIGHT_INTERPOLATION = 0.82f
private const val MIN_PIXEL_ONLY_BRACKET_GAP_METERS = 0.085f
