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
            ?.let { it + learnedTopCorrectionMeters() }
            ?.coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
        topStature?.let {
            topEnvelope.addLast(it)
            if (topEnvelope.size > ENVELOPE_LIMIT) topEnvelope.removeFirst()
        }
        estimatorHip?.let {
            hipMedian.addLast(it)
            if (hipMedian.size > ENVELOPE_LIMIT) hipMedian.removeFirst()
        }
        val torsoSample = torso?.takeIf { sample ->
            topStature?.let { abs(sample - it) <= MAX_TOP_TORSO_SAMPLE_SPREAD_METERS } == true ||
                estimatorHip?.let { abs(sample - it) <= MAX_HIP_TORSO_REFERENCE_SPREAD_METERS } == true
        }
        torsoSample?.let {
            torsoMedian.addLast(it)
            if (torsoMedian.size > ENVELOPE_LIMIT) torsoMedian.removeFirst()
        }
        estimateBracketedHeightSample(
            topStature = topStature,
            hip = estimatorHip,
            torso = torso,
            pixel = pixel,
            bodyScaleConfidence = bodyScaleConfidence,
        )?.let {
            bracketEnvelope.addLast(it)
            if (bracketEnvelope.size > ENVELOPE_LIMIT) bracketEnvelope.removeFirst()
        }

        // Upper percentile of the rolling top envelope captures true stature
        // from head-up frames while ignoring crouches/raised-arms artifacts.
        val sortedTop = topEnvelope.takeIf { it.size >= 6 }?.sorted()
        val topHigh = sortedTop?.let { samples ->
            samples[(samples.size * 0.78f).toInt().coerceAtMost(samples.size - 1)]
        }
        val topLow = sortedTop?.let { samples ->
            samples[(samples.size * 0.35f).toInt().coerceAtMost(samples.size - 1)]
        }
        val topStableSpread = sortedTop?.let { samples ->
            samples[(samples.size * 0.90f).toInt().coerceAtMost(samples.size - 1)] -
                samples[(samples.size * 0.10f).toInt().coerceAtMost(samples.size - 1)]
        }
        val hipMed = hipMedian.takeIf { it.size >= 4 }?.let { samples ->
            samples.sorted()[samples.size / 2]
        }
        val torsoMed = torsoMedian.takeIf { it.size >= 6 }?.let { samples ->
            samples.sorted()[samples.size / 2]
        }
        val sortedBracket = bracketEnvelope.takeIf { it.size >= MIN_BRACKET_OBSERVATIONS }?.sorted()
        val bracketConsensus = sortedBracket?.let { samples ->
            val spread = samples[(samples.size * 0.90f).toInt().coerceAtMost(samples.size - 1)] -
                samples[(samples.size * 0.10f).toInt().coerceAtMost(samples.size - 1)]
            if (spread <= MAX_BRACKET_STABLE_SPREAD_METERS) {
                samples[samples.size / 2]
            } else {
                null
            }
        }
        val topTorsoConsensus = if (
            topHigh != null &&
            torsoMed != null &&
            topStableSpread != null &&
            topStableSpread <= MAX_TOP_TORSO_STABLE_SPREAD_METERS &&
            abs(topHigh - torsoMed) <= MAX_TOP_TORSO_CONSENSUS_SPREAD_METERS
        ) {
            topHigh * 0.62f + torsoMed * 0.38f
        } else {
            null
        }
        val lockedForTarget = currentLocked.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val topOnlyStable = lockedForTarget == null &&
            topHigh != null &&
            topStableSpread != null &&
            topStableSpread <= MAX_TOP_ONLY_RETARGET_SPREAD_METERS

        val target = when {
            bracketConsensus != null -> bracketConsensus
            topTorsoConsensus != null && hipMed != null &&
                abs(hipMed - topTorsoConsensus) <= MAX_HIP_UPPER_CONSENSUS_SPREAD_METERS -> {
                topTorsoConsensus * 0.68f + hipMed * 0.32f
            }
            topTorsoConsensus != null -> topTorsoConsensus
            // Top persistently above hip: top acts as the upper-body stature
            // witness while hip remains a lower bound.
            topHigh != null && hipMed != null && (topHigh - hipMed) >= MIN_UPPER_WITNESS_GAP_METERS -> {
                if (topHigh - hipMed > MAX_RETARGET_WITNESS_GAP_METERS && !topOnlyStable) return null
                topHigh.coerceIn(hipMed + MIN_UPPER_WITNESS_GAP_METERS, MAX_BODY_HEIGHT_METERS)
            }
            // Otherwise blend top and hip when both are present.
            topHigh != null && hipMed != null -> 0.55f * topHigh + 0.45f * hipMed
            topLow != null -> topLow
            hipMed != null -> hipMed
            else -> return null
        }

        // Latent smoothing with very low process noise once mature, so jumping
        // / leg motion / momentary occlusions don't perturb subject stature.
        estimateMeters = if (!estimateMeters.isFinite()) {
            target
        } else {
            val delta = target - estimateMeters
            val alpha = when {
                bracketConsensus != null &&
                    abs(delta) > 0.010f -> 0.115f
                matureFrames >= MATURE_THRESHOLD &&
                    topTorsoConsensus != null &&
                    abs(delta) > 0.010f -> 0.028f
                matureFrames >= MATURE_THRESHOLD -> 0.012f
                matureFrames >= 12 -> 0.06f
                else -> 0.18f
            }
            (estimateMeters + delta * alpha).coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
        }
        matureFrames = (matureFrames + 1).coerceAtMost(120)

        // Decide if the existing in-flight lock should rise toward the latent
        // subject-height estimate. Conditions are deliberately relative, not a
        // hard-coded stature band: sustained top-envelope evidence and a real
        // gap between current lock and current posterior.
        val locked = currentLocked.takeIf { it.isFinite() }
        val anchor = lowerAnchor
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: locked
        if (matureFrames < ENABLE_RETARGET_AFTER_FRAMES) return null
        val bracketSupportedFrames = bracketConsensus?.let { consensus ->
            bracketEnvelope.count { abs(it - consensus) <= MAX_ESTIMATE_SUPPORT_RESIDUAL_METERS }
        } ?: 0
        val usingBracketSupport = bracketConsensus != null &&
            bracketSupportedFrames >= MIN_BRACKET_OBSERVATIONS
        val supportedFrames = if (usingBracketSupport) {
            bracketSupportedFrames
        } else {
            topEnvelope.count { abs(it - estimateMeters) <= MAX_ESTIMATE_SUPPORT_RESIDUAL_METERS }
        }
        if (supportedFrames < MIN_OBSERVATIONS_TO_RETARGET) {
            retargetCorrectionFrames = (retargetCorrectionFrames - 1).coerceAtLeast(0)
            return null
        }
        val retargetEstimate = if (usingBracketSupport && bracketConsensus != null) {
            maxOf(estimateMeters, bracketConsensus)
        } else {
            estimateMeters
        }
        if (locked == null) {
            if (
                !usingBracketSupport &&
                (
                    topLow == null ||
                        supportedFrames < MIN_OBSERVATIONS_TO_ACQUIRE ||
                        topStableSpread == null ||
                        topStableSpread > MAX_TOP_ONLY_ACQUIRE_SPREAD_METERS
                    )
            ) {
                retargetCorrectionFrames = (retargetCorrectionFrames - 1).coerceAtLeast(0)
                return null
            }
            retargetCorrectionFrames = (retargetCorrectionFrames + 1).coerceAtMost(60)
            return if (retargetCorrectionFrames >= INITIAL_ACQUIRE_RETARGET_FRAMES) retargetEstimate else null
        }
        val validAnchor = anchor ?: locked
        if (hip != null && hip > validAnchor + HIP_REJECTS_RETARGET_GAP_METERS) {
            // If the current hip witness itself jumps upward, treat it as a
            // fresh geometry claim and let the normal trust gates handle it
            // instead of ratcheting the lock.
            retargetCorrectionFrames = 0
            return null
        }
        if (hipMed != null && hipMed > validAnchor + HIP_REJECTS_RETARGET_GAP_METERS) {
            // If hip geometry also moved upward well beyond the lock, this is
            // no longer the metrics_64 "hip-low, top-tall" pattern. Let the
            // normal semantic/lock gates decide instead of using the top
            // envelope to chase a high silhouette run.
            retargetCorrectionFrames = 0
            return null
        }
        if (retargetEstimate - locked < SUBJECT_HEIGHT_RETARGET_STOP_GAP_METERS) {
            retargetCorrectionFrames = (retargetCorrectionFrames - 1).coerceAtLeast(0)
            return null
        }
        retargetCorrectionFrames = (retargetCorrectionFrames + 1).coerceAtMost(60)
        return if (retargetCorrectionFrames >= LOCKED_RETARGET_FRAMES) retargetEstimate else null
    }

    private fun estimateBracketedHeightSample(
        topStature: Float?,
        hip: Float?,
        torso: Float?,
        pixel: Float?,
        bodyScaleConfidence: Float,
    ): Float? {
        val top = topStature?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        if (bodyScaleConfidence.isFinite() && bodyScaleConfidence < MIN_BRACKET_BODY_SCALE_CONFIDENCE) return null
        val highTorso = torso?.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS &&
                candidate > top + MIN_BRACKET_WITNESS_GAP_METERS &&
                candidate < top + MAX_BRACKET_WITNESS_GAP_METERS &&
                hip?.let { candidate > it + MIN_BRACKET_WITNESS_GAP_METERS } != false
        }
        val highPixel = pixel?.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS &&
                candidate > top + MIN_BRACKET_WITNESS_GAP_METERS &&
                candidate < top + MAX_BRACKET_WITNESS_GAP_METERS &&
                hip?.let { candidate > it + MIN_BRACKET_WITNESS_GAP_METERS } != false
        }
        val pixelOnly = pixel?.takeIf { candidate ->
