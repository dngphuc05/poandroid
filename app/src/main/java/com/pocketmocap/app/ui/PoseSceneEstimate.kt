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
private const val MAX_PIXEL_ONLY_BRACKET_GAP_METERS = 0.16f

private fun heightLockUpwardMarginMeters(
    anchor: Float,
    target: Float,
    matureRetargetActive: Boolean = false,
): Float = when {
    matureRetargetActive &&
        anchor.isFinite() &&
        target.isFinite() &&
        target > anchor -> MATURE_RETARGET_UPWARD_MARGIN_METERS
    else -> HEIGHT_LOCK_UPWARD_MARGIN_METERS
}

/**
 * Latent subject-height tracker. Treats human stature as a nearly constant
 * scene parameter and accumulates a posterior over recent top-ray and hip-
 * geometry observations. When the in-flight lock has acquired a value that
 * is persistently below the rolling top envelope, the estimator returns a
 * non-null retarget target so the caller can lift the lock with a wider upward
 * margin instead of being pinned by per-frame hip-weighted averaging.
 */
internal class SubjectHeightEstimator {
    var estimateMeters: Float = Float.NaN
        private set
    var matureFrames: Int = 0
        private set
    private var retargetCorrectionFrames = 0
    private val topEnvelope = ArrayDeque<Float>()
    private val hipMedian = ArrayDeque<Float>()
    private val torsoMedian = ArrayDeque<Float>()
    private val bracketEnvelope = ArrayDeque<Float>()
    private val topCorrectionResiduals = ArrayDeque<Float>()

    fun reset() {
        estimateMeters = Float.NaN
        matureFrames = 0
        retargetCorrectionFrames = 0
        topEnvelope.clear()
        hipMedian.clear()
        torsoMedian.clear()
        bracketEnvelope.clear()
        topCorrectionResiduals.clear()
    }

    /**
     * Updates the latent estimate from the current frame's witnesses.
     * Returns a non-null target only when the caller should lift an immature
     * lock toward the estimator with a wider upward margin.
     */
    fun update(
        topRayHeight: Float,
        hipGeometryHeight: Float,
        torsoHeight: Float,
        pixelSpanHeight: Float,
        bodyScaleConfidence: Float,
        endpointBias: Float,
        distanceTrusted: Boolean,
        sceneConfidence: Float,
        currentLocked: Float,
        lowerAnchor: Float,
    ): Float? {
        if (!distanceTrusted && sceneConfidence < MIN_SCENE_CONFIDENCE_TO_LEARN) return null
        val top = topRayHeight.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val hip = hipGeometryHeight.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val torso = torsoHeight.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val pixel = pixelSpanHeight.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        if (top == null && hip == null) return null

        // Use only positive endpoint bias (head-up extension). A persistently
        // negative bias has been seen to collapse the estimate downward, which
        // is the metrics_63 failure mode the upstream clamp already addresses.
        val positiveEndpointBias = maxOf(0f, endpointBias)
        val biasedTop = top?.let { it + positiveEndpointBias }
        val lockedForBias = currentLocked
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val endpointBiasSupported = positiveEndpointBias > 0f &&
            top != null &&
            (
                lockedForBias?.let { top >= it - MAX_TOP_LOW_BIAS_MASK_GAP_METERS } == true ||
                    listOfNotNull(torso)
                        .any { abs(it - (biasedTop ?: top)) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP_METERS }
                )
        val topBase = top?.let { it + if (endpointBiasSupported) positiveEndpointBias else 0f }
        val estimatorHip = hip?.takeUnless { candidate ->
            val topReference = top
            val torsoSupportsHip = torso?.let { abs(it - candidate) <= MAX_HIP_TORSO_REFERENCE_SPREAD_METERS } == true
            topReference != null &&
                candidate > topReference + STARTUP_HIGH_HIP_TOP_GAP_METERS &&
                !torsoSupportsHip
        }
        updateTopCorrection(
            topBase = topBase,
            hip = estimatorHip,
            torso = torso,
            lockedHeight = currentLocked.takeIf { it.isFinite() },
        )
        val topStature = topBase
