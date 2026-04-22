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
            candidate.isFinite() &&
                candidate in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS &&
                candidate > top + MIN_PIXEL_ONLY_BRACKET_GAP_METERS &&
                candidate < top + MAX_PIXEL_ONLY_BRACKET_GAP_METERS &&
                hip?.let { candidate >= it - 0.04f } != false
        }
        val highWitnesses = listOfNotNull(highTorso, highPixel ?: pixelOnly)
        if (highTorso == null && highPixel == null && pixelOnly == null) return null
        if (highWitnesses.isEmpty()) return null
        val sortedWitnesses = highWitnesses.sorted()
        val upper = sortedWitnesses[(sortedWitnesses.size - 1) / 2]
        val interpolation = if (highTorso == null && highPixel == null && pixelOnly != null) {
            PIXEL_ONLY_BRACKET_HEIGHT_INTERPOLATION
        } else {
            BRACKET_HEIGHT_INTERPOLATION
        }
        val sample = top + (upper - top) * interpolation
        return sample.takeIf {
            it.isFinite() &&
                it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS &&
                it > top + MIN_BRACKET_RETARGET_LIFT_METERS
        }
    }

    private fun updateTopCorrection(
        topBase: Float?,
        hip: Float?,
        torso: Float?,
        lockedHeight: Float?,
    ) {
        val top = topBase?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return
        val reference = topCorrectionReference(
            hip = hip,
            torso = torso,
            lockedHeight = lockedHeight,
        ) ?: return
        val residual = reference - top
        if (residual < MIN_POSITIVE_TOP_CORRECTION_METERS) return
        if (abs(residual) > MAX_TOP_REFERENCE_RESIDUAL_METERS) return
        topCorrectionResiduals.addLast(residual.coerceIn(0f, MAX_LEARNED_TOP_CORRECTION_METERS))
        if (topCorrectionResiduals.size > TOP_CORRECTION_LIMIT) topCorrectionResiduals.removeFirst()
    }

    private fun topCorrectionReference(
        hip: Float?,
        torso: Float?,
        lockedHeight: Float?,
    ): Float? {
        val hipTorso = if (
            hip != null &&
            torso != null &&
            abs(hip - torso) <= MAX_HIP_TORSO_REFERENCE_SPREAD_METERS
        ) {
            hip * 0.58f + torso * 0.42f
        } else {
            null
        }
        if (hipTorso != null) return hipTorso

        val locked = lockedHeight
            ?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        val agreesWithLocked = listOfNotNull(hip, torso)
            .any { abs(it - locked) <= MAX_LOCK_REFERENCE_SPREAD_METERS }
        return if (agreesWithLocked) locked else null
    }

    private fun learnedTopCorrectionMeters(): Float {
        val sorted = topCorrectionResiduals.takeIf { it.size >= MIN_TOP_CORRECTION_OBSERVATIONS }
            ?.sorted()
            ?: return 0f
        val trim = (sorted.size * 0.20f).toInt().coerceAtMost((sorted.size - 1) / 2)
        val trimmed = sorted.subList(trim, sorted.size - trim)
        return trimmed.sum() / trimmed.size
    }

    companion object {
        private const val ENVELOPE_LIMIT = 32
        private const val TOP_CORRECTION_LIMIT = 32
        private const val MATURE_THRESHOLD = 30
        private const val ENABLE_RETARGET_AFTER_FRAMES = 10
        private const val MIN_OBSERVATIONS_TO_RETARGET = 6
        private const val MIN_OBSERVATIONS_TO_ACQUIRE = 10
        private const val MIN_BRACKET_OBSERVATIONS = 8
        private const val INITIAL_ACQUIRE_RETARGET_FRAMES = 3
        private const val LOCKED_RETARGET_FRAMES = 3
        private const val MIN_TOP_CORRECTION_OBSERVATIONS = 6
        private const val MIN_SCENE_CONFIDENCE_TO_LEARN = 0.08f
        private const val MIN_BRACKET_BODY_SCALE_CONFIDENCE = 0.62f
        private const val MIN_BRACKET_WITNESS_GAP_METERS = 0.065f
        private const val MAX_BRACKET_WITNESS_GAP_METERS = 0.32f
        private const val MIN_BRACKET_RETARGET_LIFT_METERS = 0.045f
        private const val MAX_BRACKET_STABLE_SPREAD_METERS = 0.18f
        private const val BRACKET_HEIGHT_INTERPOLATION = 0.58f
        private const val MIN_UPPER_WITNESS_GAP_METERS = 0.070f
        private const val MAX_ESTIMATE_SUPPORT_RESIDUAL_METERS = 0.10f
        private const val HIP_REJECTS_RETARGET_GAP_METERS = 0.055f
        private const val MAX_RETARGET_WITNESS_GAP_METERS = 0.115f
        private const val MAX_TOP_ONLY_ACQUIRE_SPREAD_METERS = 0.090f
        private const val MAX_HIP_TORSO_REFERENCE_SPREAD_METERS = 0.10f
        private const val MAX_TOP_TORSO_SAMPLE_SPREAD_METERS = 0.085f
        private const val MAX_TOP_TORSO_STABLE_SPREAD_METERS = 0.035f
        private const val MAX_TOP_TORSO_CONSENSUS_SPREAD_METERS = 0.060f
        private const val MAX_HIP_UPPER_CONSENSUS_SPREAD_METERS = 0.020f
        private const val MAX_LOCK_REFERENCE_SPREAD_METERS = 0.12f
        private const val MAX_TOP_ONLY_RETARGET_SPREAD_METERS = 0.055f
        private const val MIN_POSITIVE_TOP_CORRECTION_METERS = 0.015f
        private const val MAX_TOP_REFERENCE_RESIDUAL_METERS = 0.18f
        private const val MAX_LEARNED_TOP_CORRECTION_METERS = 0.12f
    }
}

internal data class PoseRoi(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
) {
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY
    val bottomGap: Float get() = (1f - maxY).coerceIn(0f, 0.40f)
}

internal data class OverlayPoseEstimate(
    val distanceMeters: Float,
    val bodyHeightMeters: Float,
    val cameraHeightMeters: Float,
    val floorPitchDegrees: Float,
    val lateralOffsetMeters: Float,
    val roi: PoseRoi,
    val source: String = "roi_fallback",
    val confidence: Float = 0.35f,
    val learnedHipVectorXNorm: Float = Float.NaN,
    val learnedHipVectorYNorm: Float = Float.NaN,
    val correctedDistanceMeters: Float = Float.NaN,
    val correctedHeightMeters: Float = Float.NaN,
    val correctedCameraHeightMeters: Float = Float.NaN,
    val localHeightCandidateMeters: Float = Float.NaN,
    val localHeightCandidateConfidence: Float = Float.NaN,
    val localHeightCandidateSource: String = "",
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
    val visualTopScanYNorm: Float = Float.NaN,
    val visualTopScanConfidence: Float = Float.NaN,
    val visualTopLiftNorm: Float = Float.NaN,
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
    fun toSceneMetricSnapshot(): SceneMetricSnapshot =
        SceneMetricSnapshot(
            source = source,
            confidence = confidence,
            distanceMeters = distanceMeters,
            bodyHeightMeters = bodyHeightMeters,
            cameraHeightMeters = cameraHeightMeters,
            floorPitchDegrees = floorPitchDegrees,
            lateralOffsetMeters = lateralOffsetMeters,
            correctedDistanceMeters = correctedDistanceMeters,
            correctedHeightMeters = correctedHeightMeters,
            correctedCameraHeightMeters = correctedCameraHeightMeters,
            localHeightCandidateMeters = localHeightCandidateMeters,
            localHeightCandidateConfidence = localHeightCandidateConfidence,
            localHeightCandidateSource = localHeightCandidateSource,
            floorSource = floorSource,
            solverConfidence = solverConfidence,
            solverResidualMeters = solverResidualMeters,
            floorHeightBiasMeters = floorHeightBiasMeters,
            depthScale = depthScale,
            depthOffsetMeters = depthOffsetMeters,
            heightEndpointBiasMeters = heightEndpointBiasMeters,
            heightLockState = heightLockState,
            distanceCandidateSpreadMeters = distanceCandidateSpreadMeters,
            heightCandidateSpreadMeters = heightCandidateSpreadMeters,
            rawHipDepthDistanceMeters = rawHipDepthDistanceMeters,
            footPlaneDistanceMeters = footPlaneDistanceMeters,
            roiDistanceMeters = roiDistanceMeters,
            topRayHeightMeters = topRayHeightMeters,
            pixelSpanHeightMeters = pixelSpanHeightMeters,
            hipGeometryDistanceMeters = hipGeometryDistanceMeters,
            hipGeometryHeightMeters = hipGeometryHeightMeters,
            torsoHeightMeters = torsoHeightMeters,
            torsoResidualMeters = torsoResidualMeters,
            groundedFootDistanceMeters = groundedFootDistanceMeters,
            footContactState = footContactState,
            leftFootRayFloorDistanceMeters = leftFootRayFloorDistanceMeters,
            rightFootRayFloorDistanceMeters = rightFootRayFloorDistanceMeters,
            feetMidpointFloorDistanceMeters = feetMidpointFloorDistanceMeters,
            nearestFootFloorDistanceMeters = nearestFootFloorDistanceMeters,
            footRayFloorSpreadMeters = footRayFloorSpreadMeters,
            topRayFloorHeightMeters = topRayFloorHeightMeters,
            distanceHeightGeometryResidualMeters = distanceHeightGeometryResidualMeters,
            rootHipRayFloorDistanceMeters = rootHipRayFloorDistanceMeters,
            boneLengthSpreadMeters = boneLengthSpreadMeters,
            bodyScaleConfidence = bodyScaleConfidence,
            distanceConfidence = distanceConfidence,
            heightConfidence = heightConfidence,
            floorConfidence = floorConfidence,
            distanceState = distanceState,
            weightHip = weightHip,
            weightHead = weightHead,
            weightFoot = weightFoot,
            weightTorso = weightTorso,
            weightBone = weightBone,
            weightDepth = weightDepth,
            weightRoi = weightRoi,
            weightTemporal = weightTemporal,
            hipCenterXNorm = hipCenterXNorm,
            hipCenterYNorm = hipCenterYNorm,
            shoulderHipSpanNorm = shoulderHipSpanNorm,
            torsoSpanNorm = torsoSpanNorm,
            bodyRoiHeightNorm = bodyRoiHeightNorm,
            relativeScaleDistanceMeters = relativeScaleDistanceMeters,
            rejectedHipReason = rejectedHipReason,
            heightSeedTrusted = heightSeedTrusted,
            activeFactors = activeFactors,
            bodyClipRisk = bodyClipRisk,
            topEndpointConfidence = topEndpointConfidence,
            footEndpointConfidence = footEndpointConfidence,
            maskEndpointConfidence = maskEndpointConfidence,
            visualTopScanYNorm = visualTopScanYNorm,
            visualTopScanConfidence = visualTopScanConfidence,
            visualTopLiftNorm = visualTopLiftNorm,
            experimentalHeightMeters = experimentalHeightMeters,
            experimentalHeightSigmaMeters = experimentalHeightSigmaMeters,
            experimentalHeightConfidence = experimentalHeightConfidence,
            experimentalHeightState = experimentalHeightState,
            experimentalDistanceMeters = experimentalDistanceMeters,
            experimentalDistanceSigmaMeters = experimentalDistanceSigmaMeters,
            experimentalDistanceConfidence = experimentalDistanceConfidence,
            experimentalSolverCost = experimentalSolverCost,
            experimentalSolverStatus = experimentalSolverStatus,
            experimentalFactorSummary = experimentalFactorSummary,
            baselineExperimentalHeightDeltaMeters = baselineExperimentalHeightDeltaMeters,
            baselineExperimentalDistanceDeltaMeters = baselineExperimentalDistanceDeltaMeters,
            promotedSolverSource = promotedSolverSource,
            rawKeypointGeometry = rawKeypointGeometry,
        )
}

internal data class PhysicalSceneBias(
    val floorHeightBiasMeters: Float = 0f,
    val depthScale: Float = 1f,
    val depthOffsetMeters: Float = 0f,
    val heightEndpointBiasMeters: Float = 0f,
)

internal class PhysicalSceneFactorGraph(initialBias: PhysicalSceneBias = PhysicalSceneBias()) {
    private var floorHeightBiasMeters = initialBias.floorHeightBiasMeters.coerceIn(-0.20f, 0.20f)
    private var depthScale = initialBias.depthScale.takeIf { it.isFinite() }?.coerceIn(0.92f, 1.08f) ?: 1f
    private var depthOffsetMeters = initialBias.depthOffsetMeters.coerceIn(-0.30f, 0.30f)
    private var heightEndpointBiasMeters = initialBias.heightEndpointBiasMeters
        .coerceIn(MIN_HEIGHT_ENDPOINT_BIAS_METERS, MAX_HEIGHT_ENDPOINT_BIAS_METERS)
    private var lockedDistanceMeters = Float.NaN
    private var distanceRetargetFrames = 0
    private var lockedHeightMeters = Float.NaN
    private var pendingHeightMeters = Float.NaN
    private var pendingHeightFrames = 0
    private var stableHeightFrames = 0
    private var heightLockAnchorMeters = Float.NaN
    private var relativeAnchorDistanceMeters = Float.NaN
    private var relativeAnchorTorsoSpanNorm = Float.NaN
    private val subjectHeightEstimator = SubjectHeightEstimator()
    private val subjectSceneSolver = SubjectSceneSolver()

    val currentBias: PhysicalSceneBias
        get() = PhysicalSceneBias(
            floorHeightBiasMeters = floorHeightBiasMeters,
            depthScale = depthScale,
            depthOffsetMeters = depthOffsetMeters,
            heightEndpointBiasMeters = heightEndpointBiasMeters,
        )

    fun resetRuntimeState() {
        lockedDistanceMeters = Float.NaN
        distanceRetargetFrames = 0
        lockedHeightMeters = Float.NaN
        pendingHeightMeters = Float.NaN
        pendingHeightFrames = 0
        stableHeightFrames = 0
        heightLockAnchorMeters = Float.NaN
        relativeAnchorDistanceMeters = Float.NaN
        relativeAnchorTorsoSpanNorm = Float.NaN
        subjectHeightEstimator.reset()
        subjectSceneSolver.reset()
    }

    fun solve(raw: SceneMetricSnapshot): SceneMetricSnapshot {
        if (raw.source != "arcore_floor") return raw
        val relativeScaleDistance = estimateRelativeScaleDistance(raw)
        val typedMeasurements = SceneMeasurementExtractor.extract(raw, relativeScaleDistance)
        val optimized = PhysicalSceneOptimizer.optimize(
            SceneMeasurementExtractor.optimizerInput(
                raw = raw,
                relativeScaleDistance = relativeScaleDistance,
                previousDistanceMeters = lockedDistanceMeters,
                previousHeightMeters = lockedHeightMeters,
                floorHeightBiasMeters = floorHeightBiasMeters,
                depthScale = depthScale,
                depthOffsetMeters = depthOffsetMeters,
                heightEndpointBiasMeters = heightEndpointBiasMeters,
            )
        )

        floorHeightBiasMeters = optimized.floorHeightBiasMeters
        depthScale = optimized.depthScale
        depthOffsetMeters = optimized.depthOffsetMeters
        heightEndpointBiasMeters = optimized.heightEndpointBiasMeters
            .coerceIn(MIN_HEIGHT_ENDPOINT_BIAS_METERS, MAX_HEIGHT_ENDPOINT_BIAS_METERS)
        lockedDistanceMeters = optimized.distanceMeters
        val heightQualityTrusted = isSemanticHeightTrusted(raw, optimized)
        val lockedSilhouetteNoise = hasLockedHeightSilhouetteNoise(raw)
        val strongLockedHeightConflict = hasStrongLockedHeightConflict(raw)
        val blockHeightRetarget = lockedSilhouetteNoise || strongLockedHeightConflict
        val matureSubjectRetarget = if (blockHeightRetarget) null else subjectHeightEstimator.update(
            topRayHeight = raw.topRayHeightMeters,
            hipGeometryHeight = raw.hipGeometryHeightMeters,
            torsoHeight = raw.torsoHeightMeters,
            pixelSpanHeight = raw.pixelSpanHeightMeters,
            bodyScaleConfidence = raw.bodyScaleConfidence,
            endpointBias = heightEndpointBiasMeters,
            distanceTrusted = optimized.distanceTrusted,
            sceneConfidence = raw.confidence,
            currentLocked = lockedHeightMeters,
            lowerAnchor = heightLockAnchorMeters,
        )
        val baseSemanticTarget = if (blockHeightRetarget) null else estimateSemanticHeightRetarget(raw, optimized)
        val bracketSpanTarget = if (blockHeightRetarget) null else estimateBracketedSpanHeightRetarget(raw)
            ?: estimateLooseBracketedSpanHeightRetarget(raw)
        val startupTopCandidate = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?.let { it + heightEndpointBiasMeters.coerceAtLeast(0f) }
            ?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val startupTopFallback = startupTopCandidate
            ?.takeIf {
                !lockedHeightMeters.isFinite() &&
                    matureSubjectRetarget == null &&
                    raw.confidence >= 0.50f &&
                    optimized.heightMeters.isFinite() &&
                    optimized.heightMeters in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS &&
                    it > optimized.heightMeters + INITIAL_TOP_FALLBACK_GAP_METERS
            }
        // Combine relative semantic witnesses with the rolling latent estimate.
        // The max only lets stronger current evidence lift an immature lock; no
        // stature-specific offset is added to any candidate.
        val semanticHeightTarget = listOfNotNull(
            matureSubjectRetarget,
            bracketSpanTarget,
            baseSemanticTarget,
            startupTopFallback,
        ).maxOrNull()
        val subjectRetargetActive = matureSubjectRetarget != null || bracketSpanTarget != null || startupTopFallback != null
        val bracketHeightTrusted = bracketSpanTarget != null &&
            raw.confidence >= STARTUP_MIN_LOCK_CONFIDENCE
        val lockMeasuredHeight = if (bracketHeightTrusted) {
            bracketSpanTarget ?: optimized.heightMeters
        } else {
            optimized.heightMeters
        }
        val heightQualityTrustedForLock = heightQualityTrusted || bracketHeightTrusted
        val quarantineInitialHeightLock = shouldQuarantineInitialHeightLock(
            raw = raw,
            optimized = optimized,
            retargetHeight = semanticHeightTarget,
            subjectRetargetActive = subjectRetargetActive,
        )
        val startupCorrectionHeight = estimateStartupBadLockCorrectionHeight(raw, optimized)
        val initialTopHeightConflict = startupTopFallback == null &&
            matureSubjectRetarget == null &&
            !lockedHeightMeters.isFinite() &&
            raw.confidence >= 0.50f &&
            startupTopCandidate != null &&
            optimized.heightMeters.isFinite() &&
            optimized.heightMeters in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS &&
            startupTopCandidate > optimized.heightMeters + INITIAL_TOP_CONFLICT_GAP_METERS
        val heightLock = updateHeightLock(
            measuredHeight = lockMeasuredHeight,
            confidence = raw.confidence,
            qualityTrusted = heightQualityTrustedForLock,
            stickyTrusted = shouldKeepLockedHeightConstraint(raw, optimized, heightQualityTrusted),
            retargetHeight = semanticHeightTarget,
            matureRetargetActive = subjectRetargetActive,
            delayInitialLowLock = initialTopHeightConflict,
            quarantineInitialLock = quarantineInitialHeightLock,
            startupCorrectionHeight = startupCorrectionHeight,
        )
        val heightConstraintTrusted = heightQualityTrusted || heightLock.exportAsConstraint
        maybeUpdateRelativeScaleAnchor(raw, optimized, heightLock, heightConstraintTrusted)
        val correctedCameraHeight = optimized.cameraHeightMeters
        val exportedCorrectedHeight = heightLock.heightMeters
            .takeIf { optimized.exportHeightConstraint && heightLock.exportAsConstraint }
            ?: Float.NaN
        val distanceState = when {
            optimized.distanceTrusted -> "tracking"
            lockedDistanceMeters.isFinite() -> "held_low_confidence"
            else -> "reacquiring"
        }

        val exportedHeightConfidence = when {
            !heightLock.exportAsConstraint -> optimized.heightConfidence
            heightQualityTrusted -> maxOf(optimized.heightConfidence, minOf(raw.confidence, 0.62f))
            heightLock.state == "locked" -> maxOf(optimized.heightConfidence, minOf(raw.confidence, 0.56f))
            else -> maxOf(optimized.heightConfidence, minOf(raw.confidence, 0.48f))
        }.coerceIn(0f, 1f)
        val localHeightCandidate = estimateLocalHeightCandidate(
            raw = raw,
            optimized = optimized,
            heightLock = heightLock,
            exportedCorrectedHeight = exportedCorrectedHeight,
            exportedHeightConfidence = exportedHeightConfidence,
        )
        val exportedSolverConfidence = when {
            !heightLock.exportAsConstraint -> optimized.solverConfidence
            optimized.distanceTrusted -> maxOf(optimized.solverConfidence, exportedHeightConfidence)
            else -> maxOf(optimized.solverConfidence, exportedHeightConfidence * 0.82f)
        }.coerceIn(0f, 1f)
        val optimizedFactorLabels = if (heightLock.exportAsConstraint) {
            removeFactorLabels(optimized.activeFactors, setOf("untrusted_height_spread"))
        } else {
            optimized.activeFactors
        }
        val activeFactors = appendFactorLabels(
            optimizedFactorLabels,
            heightTrustRejectionLabels(raw, optimized, heightConstraintTrusted) +
                relativeScaleAnchorLabels(relativeScaleDistance, heightLock, heightConstraintTrusted),
        )

        val baselineScene = raw.copy(
            confidence = minOf(raw.confidence, exportedSolverConfidence).coerceIn(0f, 1f),
            distanceMeters = optimized.distanceMeters,
            bodyHeightMeters = heightLock.heightMeters,
            cameraHeightMeters = correctedCameraHeight,
            correctedDistanceMeters = optimized.distanceMeters,
            correctedHeightMeters = exportedCorrectedHeight,
            correctedCameraHeightMeters = correctedCameraHeight,
            localHeightCandidateMeters = localHeightCandidate?.meters ?: Float.NaN,
            localHeightCandidateConfidence = localHeightCandidate?.confidence ?: Float.NaN,
            localHeightCandidateSource = localHeightCandidate?.source ?: "",
            floorSource = raw.floorSource.ifBlank { "arcore_floor" },
            solverConfidence = exportedSolverConfidence,
            solverResidualMeters = optimized.solverResidualMeters,
            floorHeightBiasMeters = floorHeightBiasMeters,
            depthScale = depthScale,
            depthOffsetMeters = depthOffsetMeters,
            heightEndpointBiasMeters = heightEndpointBiasMeters,
            heightLockState = heightLock.state,
            distanceCandidateSpreadMeters = optimized.distanceCandidateSpreadMeters,
            heightCandidateSpreadMeters = optimized.heightCandidateSpreadMeters,
            distanceConfidence = optimized.distanceConfidence,
            heightConfidence = exportedHeightConfidence,
            floorConfidence = raw.confidence,
            distanceState = distanceState,
            weightHip = optimized.weightHip,
            weightHead = optimized.weightHead,
            weightFoot = optimized.weightFoot,
            weightTorso = optimized.weightTorso,
            weightBone = optimized.weightBone,
            weightDepth = optimized.weightDepth,
            weightRoi = optimized.weightRoi,
            weightTemporal = optimized.weightTemporal,
            relativeScaleDistanceMeters = relativeScaleDistance,
            rejectedHipReason = raw.rejectedHipReason.ifBlank {
                if ("rejected_hip_geometry" in optimized.activeFactors) "optimizer_outlier" else ""
            },
            activeFactors = activeFactors,
        )
        return subjectSceneSolver.solveShadow(baselineScene, typedMeasurements)
    }

    private fun estimateRelativeScaleDistance(raw: SceneMetricSnapshot): Float {
        val currentSpan = raw.shoulderHipSpanNorm
            .takeIf { it.isFinite() && it in 0.035f..0.75f }
            ?: raw.torsoSpanNorm.takeIf { it.isFinite() && it in 0.035f..0.55f }
            ?: return Float.NaN
        val anchorSpan = relativeAnchorTorsoSpanNorm.takeIf { it.isFinite() && it in 0.035f..0.75f } ?: return Float.NaN
        val anchorDistance = relativeAnchorDistanceMeters.takeIf { it.isFinite() && it in 0.70f..8.0f } ?: return Float.NaN
        val ratio = (anchorSpan / currentSpan).coerceIn(0.45f, 1.85f)
        return (anchorDistance * ratio).coerceIn(0.35f, 12.0f)
    }

    private fun maybeUpdateRelativeScaleAnchor(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
        heightLock: HeightLockResult,
        heightQualityTrusted: Boolean,
    ) {
        val span = raw.shoulderHipSpanNorm
            .takeIf { it.isFinite() && it in 0.035f..0.75f }
            ?: raw.torsoSpanNorm.takeIf { it.isFinite() && it in 0.035f..0.55f }
            ?: return
        val heightAnchorTrusted =
            heightQualityTrusted &&
                optimized.heightTrusted &&
                heightLock.state == "locked" &&
                heightLock.exportAsConstraint
        val distanceOnlyAnchorTrusted =
            optimized.distanceTrusted &&
                optimized.distanceCandidateSpreadMeters <= 0.45f &&
                raw.bodyScaleConfidence.isFinite() &&
                raw.bodyScaleConfidence >= 0.58f &&
                raw.confidence >= 0.64f
        if (!heightAnchorTrusted && !distanceOnlyAnchorTrusted) {
            if (!heightLock.heightMeters.isFinite() || heightLock.state.contains("untrusted")) {
                relativeAnchorTorsoSpanNorm = Float.NaN
                relativeAnchorDistanceMeters = Float.NaN
            }
            return
        }
        if (!optimized.distanceTrusted || optimized.distanceCandidateSpreadMeters > 0.65f) return
        if (!optimized.distanceMeters.isFinite() || optimized.distanceMeters !in 0.70f..8.0f) return
        relativeAnchorTorsoSpanNorm = span
        relativeAnchorDistanceMeters = optimized.distanceMeters
    }

    private fun learnDepthBias(raw: SceneMetricSnapshot, correctedHipDepth: Float?) {
        val hipRaw = raw.rawHipDepthDistanceMeters.takeIf { it.isFinite() } ?: return
        val foot = raw.footPlaneDistanceMeters.takeIf { it.isFinite() } ?: return
        val corrected = correctedHipDepth ?: return
        if (abs(corrected - foot) > 0.45f || raw.confidence < 0.58f) return
        val residual = (foot - corrected).coerceIn(-0.08f, 0.08f)
        depthOffsetMeters = (depthOffsetMeters + residual * 0.030f).coerceIn(-0.30f, 0.30f)
        if (hipRaw > 0.6f) {
            val scaleResidual = ((foot - depthOffsetMeters) / hipRaw).coerceIn(0.92f, 1.08f) - depthScale
            depthScale = (depthScale + scaleResidual * 0.010f).coerceIn(0.92f, 1.08f)
        }
    }

    private fun learnHeightEndpointBias(raw: SceneMetricSnapshot) {
        val locked = lockedHeightMeters.takeIf { it.isFinite() } ?: return
        val top = raw.topRayHeightMeters.takeIf { it.isFinite() } ?: return
        if (raw.confidence < 0.60f || abs(locked - top) > 0.28f) return
        val residual = (locked - top - heightEndpointBiasMeters).coerceIn(-0.04f, 0.04f)
        heightEndpointBiasMeters = (heightEndpointBiasMeters + residual * 0.010f)
            .coerceIn(MIN_HEIGHT_ENDPOINT_BIAS_METERS, MAX_HEIGHT_ENDPOINT_BIAS_METERS)
        // A persistent top-ray residual can also mean the AR floor/camera-height
        // relation is biased. Learn it much slower than endpoint bias so we do
        // not chase ordinary pose noise.
        floorHeightBiasMeters = (floorHeightBiasMeters + residual * 0.004f).coerceIn(-0.20f, 0.20f)
    }

    private fun updateDistanceLock(
        measuredDistance: Float,
        spreadMeters: Float,
        confidence: Float,
        hasTrustedHipDepth: Boolean,
        qualityTrusted: Boolean,
        fallbackDistance: Float?,
    ): Float {
        val measured = measuredDistance.takeIf { it.isFinite() && it in 0.35f..12.0f }
            ?: return lockedDistanceMeters.takeIf { it.isFinite() } ?: measuredDistance
        if (!qualityTrusted) {
            distanceRetargetFrames = 0
            val fallback = fallbackDistance?.takeIf { it.isFinite() && it in 0.70f..12.0f }
            if (lockedDistanceMeters.isFinite()) {
                if (fallback != null && !candidateAgreement(lockedDistanceMeters, fallback)) {
                    val delta = (fallback - lockedDistanceMeters).coerceIn(-0.24f, 0.24f)
                    lockedDistanceMeters = (lockedDistanceMeters + delta * 0.18f).coerceIn(0.35f, 12.0f)
                }
                return lockedDistanceMeters
            }
            return fallback ?: measured
        }
        if (!lockedDistanceMeters.isFinite()) {
            lockedDistanceMeters = measured
            return measured
        }

        val retargeting =
            abs(measured - lockedDistanceMeters) > 0.45f &&
                confidence >= 0.55f &&
                spreadMeters <= 1.60f
        distanceRetargetFrames = if (retargeting) distanceRetargetFrames + 1 else 0
        val sustainedRetarget = distanceRetargetFrames >= 10

        val alpha = when {
            sustainedRetarget -> 0.28f
            hasTrustedHipDepth && spreadMeters <= 0.55f -> 0.30f
            spreadMeters <= 0.45f && confidence >= 0.62f -> 0.24f
            spreadMeters <= 0.85f -> 0.14f
            else -> 0.055f
        }
        val maxStep = when {
            sustainedRetarget -> 0.34f
            hasTrustedHipDepth && spreadMeters <= 0.55f -> 0.32f
            spreadMeters <= 0.45f -> 0.22f
            spreadMeters <= 0.85f -> 0.14f
            else -> 0.075f
        }
        val delta = (measured - lockedDistanceMeters).coerceIn(-maxStep, maxStep)
        lockedDistanceMeters = (lockedDistanceMeters + delta * alpha).coerceIn(0.35f, 12.0f)
        return lockedDistanceMeters
    }

    private fun updateHeightLock(
        measuredHeight: Float,
        confidence: Float,
        qualityTrusted: Boolean,
        stickyTrusted: Boolean,
        retargetHeight: Float?,
        matureRetargetActive: Boolean = false,
        delayInitialLowLock: Boolean = false,
        quarantineInitialLock: Boolean = false,
        startupCorrectionHeight: Float? = null,
    ): HeightLockResult {
        val measured = measuredHeight.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val startupCorrection = startupCorrectionHeight
            ?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        if (!qualityTrusted) {
            val matureRetargetCandidate = retargetHeight
                ?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            if (matureRetargetActive && matureRetargetCandidate != null && !lockedHeightMeters.isFinite()) {
                if (
                    !pendingHeightMeters.isFinite() ||
                    abs(matureRetargetCandidate - pendingHeightMeters) > 0.075f
                ) {
                    pendingHeightMeters = matureRetargetCandidate
                    pendingHeightFrames = 1
                } else {
                    pendingHeightMeters = pendingHeightMeters * 0.82f + matureRetargetCandidate * 0.18f
                    pendingHeightFrames += 1
                }
                if (pendingHeightFrames >= 8 && !quarantineInitialLock) {
                    lockedHeightMeters = pendingHeightMeters
                    heightLockAnchorMeters = pendingHeightMeters
                    stableHeightFrames = pendingHeightFrames
                    return HeightLockResult(lockedHeightMeters, "locked", exportAsConstraint = true)
                }
                return HeightLockResult(pendingHeightMeters, "acquiring", exportAsConstraint = false)
            }
            pendingHeightFrames = 0
            pendingHeightMeters = Float.NaN
            if (startupCorrection != null && lockedHeightMeters.isFinite()) {
                applyStartupBadLockCorrection(startupCorrection)
                return HeightLockResult(lockedHeightMeters, "holding_untrusted", exportAsConstraint = false)
            }
            if (matureRetargetActive && matureRetargetCandidate != null && lockedHeightMeters.isFinite()) {
                val constrainedTarget = if (matureRetargetCandidate > lockedHeightMeters) {
                    matureRetargetCandidate
                } else {
                    constrainHeightTargetToAnchor(matureRetargetCandidate, matureRetargetActive)
                }
                val delta = constrainedTarget - lockedHeightMeters
                val previousLocked = lockedHeightMeters
                val maxStep = if (delta >= 0f) 0.180f else 0.040f
                val alpha = if (delta >= 0f) 0.280f else 0.050f
                lockedHeightMeters = (lockedHeightMeters + delta.coerceIn(-maxStep, maxStep) * alpha)
                    .coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
                if (lockedHeightMeters > previousLocked) {
                    heightLockAnchorMeters = lockedHeightMeters
                } else {
                    rememberLowerHeightAnchor(lockedHeightMeters)
                }
                stableHeightFrames = (stableHeightFrames + 1).coerceAtMost(60)
                val closeEnoughToRetarget = abs(matureRetargetCandidate - lockedHeightMeters) <= 0.10f
                return HeightLockResult(
                    lockedHeightMeters,
                    if (closeEnoughToRetarget) "locked" else "acquiring",
                    exportAsConstraint = closeEnoughToRetarget,
                )
            }
            if (stickyTrusted && lockedHeightMeters.isFinite()) {
                retargetHeight
                    ?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
                    ?.let { target ->
                        val constrainedTarget = constrainHeightTargetToAnchor(target, matureRetargetActive)
                        val delta = constrainedTarget - lockedHeightMeters
                        val alpha = when {
                            matureRetargetActive && delta > 0.020f -> 0.170f
                            abs(delta) <= 0.045f -> 0.045f
                            delta > 0.010f -> 0.030f
                            else -> 0.026f
                        }
                        val matureAlpha = if (
                            !matureRetargetActive &&
                            delta > 0.035f &&
                            stableHeightFrames >= 48
                        ) {
                            minOf(alpha, 0.006f)
                        } else {
                            alpha
                        }
                        val maxStep = when {
                            matureRetargetActive && delta > 0f -> MATURE_RETARGET_UPWARD_MARGIN_METERS
                            delta >= 0f -> 0.030f
                            else -> 0.040f
                        }
                        val previousLocked = lockedHeightMeters
                        lockedHeightMeters = (lockedHeightMeters + delta.coerceIn(-maxStep, maxStep) * matureAlpha)
                            .coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
                        if (matureRetargetActive && lockedHeightMeters > previousLocked) {
                            // Raise the lower anchor along with the lock so the
                            // anchor's downward pull doesn't reel the lock back
                            // down once the estimator stops firing.
                            heightLockAnchorMeters = lockedHeightMeters
                        } else {
                            rememberLowerHeightAnchor(lockedHeightMeters)
                        }
                    }
                stableHeightFrames = (stableHeightFrames + 1).coerceAtMost(60)
                return HeightLockResult(lockedHeightMeters, "locked", exportAsConstraint = true)
            }
            // When we have no locked height yet, surface the optimizer's best estimate as a
            // diagnostic so the UI doesn't go blank. It is never exported as a constraint.
            val diagnostic = lockedHeightMeters.takeIf { it.isFinite() } ?: measured
            return HeightLockResult(
                heightMeters = diagnostic ?: Float.NaN,
                state = if (lockedHeightMeters.isFinite()) "holding_untrusted" else "acquiring_untrusted",
                exportAsConstraint = false,
            )
        }
        if (measured == null) {
            return HeightLockResult(
                heightMeters = lockedHeightMeters.takeIf { it.isFinite() } ?: Float.NaN,
                state = "holding_low_confidence",
                exportAsConstraint = lockedHeightMeters.isFinite(),
            )
        }
        val anthropometricObservation = retargetHeight
            ?.takeIf {
                it.isFinite() &&
                    it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS &&
                    abs(it - measured) <= if (matureRetargetActive) 0.48f else 0.30f
            }
        val lockObservation = anthropometricObservation ?: measured
        if (!lockedHeightMeters.isFinite()) {
            if (!pendingHeightMeters.isFinite() || abs(lockObservation - pendingHeightMeters) > 0.06f) {
                pendingHeightMeters = lockObservation
                pendingHeightFrames = 1
            } else {
                pendingHeightMeters = pendingHeightMeters * 0.78f + lockObservation * 0.22f
                pendingHeightFrames += 1
            }
            val requiredPendingFrames = if (delayInitialLowLock) {
                CONFLICTED_INITIAL_LOCK_FRAMES
            } else {
                DEFAULT_INITIAL_LOCK_FRAMES
            }
            if (pendingHeightFrames >= requiredPendingFrames && !quarantineInitialLock) {
                lockedHeightMeters = pendingHeightMeters
                heightLockAnchorMeters = pendingHeightMeters
                stableHeightFrames = pendingHeightFrames
                return HeightLockResult(lockedHeightMeters, "locked", exportAsConstraint = true)
            }
            return HeightLockResult(pendingHeightMeters, "acquiring", exportAsConstraint = false)
        }
        if (startupCorrection != null && lockedHeightMeters.isFinite()) {
            applyStartupBadLockCorrection(startupCorrection)
            return HeightLockResult(lockedHeightMeters, "acquiring", exportAsConstraint = false)
        }
        val unconstrainedLockTarget = anthropometricObservation ?: measured
        val lockTarget = if (matureRetargetActive && unconstrainedLockTarget > lockedHeightMeters) {
            unconstrainedLockTarget
        } else {
            constrainHeightTargetToAnchor(
                unconstrainedLockTarget,
                matureRetargetActive,
            )
        }
        val delta = lockTarget - lockedHeightMeters
        val absDelta = abs(delta)
        val baseAlpha = when {
            matureRetargetActive && delta > 0.020f -> 0.170f
            confidence < 0.55f -> 0.015f
            anthropometricObservation != null && absDelta <= 0.090f -> 0.060f
            anthropometricObservation != null && absDelta <= 0.18f && stableHeightFrames > 12 -> 0.030f
            absDelta <= 0.025f -> 0.080f
            absDelta <= 0.075f -> 0.032f
            absDelta <= 0.16f && stableHeightFrames > 16 -> 0.012f
            else -> 0.004f
        }
        val alpha = if (
            !matureRetargetActive &&
            delta > 0.035f
        ) {
            minOf(baseAlpha, if (stableHeightFrames >= 48) 0.006f else 0.004f)
        } else {
            baseAlpha
        }
        val previousLocked = lockedHeightMeters
        lockedHeightMeters = (lockedHeightMeters + delta * alpha).coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
        if (matureRetargetActive && lockedHeightMeters > previousLocked) {
            heightLockAnchorMeters = lockedHeightMeters
        } else {
            rememberLowerHeightAnchor(lockedHeightMeters)
        }
        stableHeightFrames = if (absDelta <= 0.075f) stableHeightFrames + 1 else (stableHeightFrames - 1).coerceAtLeast(0)
        return HeightLockResult(
            heightMeters = lockedHeightMeters,
            state = when {
                confidence < 0.55f -> "holding_low_confidence"
                stableHeightFrames >= 12 -> "locked"
                else -> "acquiring"
            },
            exportAsConstraint = true,
        )
    }

    private fun shouldQuarantineInitialHeightLock(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
        retargetHeight: Float?,
        subjectRetargetActive: Boolean,
    ): Boolean {
        if (lockedHeightMeters.isFinite()) return false
        val heightSpread = optimized.heightCandidateSpreadMeters
            .takeIf { it.isFinite() }
            ?: Float.POSITIVE_INFINITY
        val heightSpreadHard = heightSpread > STARTUP_HEIGHT_SPREAD_QUARANTINE_METERS
        val heightSpreadSuspect = heightSpread > STARTUP_HEIGHT_SPREAD_SUSPECT_METERS
        val unsupportedHighHip = hasUnsupportedStartupHighHip(raw)
        val distanceReady = startupDistanceReady(raw, optimized)
        val retargetReady =
            subjectRetargetActive &&
                retargetHeight?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS } != null &&
                distanceReady &&
                !unsupportedHighHip &&
                !heightSpreadHard

        if (retargetReady) return false
        if (!distanceReady || raw.confidence < STARTUP_MIN_LOCK_CONFIDENCE) return true
        return heightSpreadHard ||
            unsupportedHighHip ||
            (heightSpreadSuspect && !distanceReady)
    }

    private fun estimateStartupBadLockCorrectionHeight(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
    ): Float? {
        val locked = lockedHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        if (stableHeightFrames > STARTUP_BAD_LOCK_CORRECTION_FRAMES) return null
        val top = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        if (locked <= top + HEIGHT_LOCK_UPWARD_MARGIN_METERS) return null
        if (hasBracketedUpperSpanSupport(raw, top)) return null
        val heightSpread = optimized.heightCandidateSpreadMeters
            .takeIf { it.isFinite() }
            ?: Float.POSITIVE_INFINITY
        val spreadStillBad = heightSpread > STARTUP_HEIGHT_SPREAD_SUSPECT_METERS
        val unsupportedHighHip = hasUnsupportedStartupHighHip(raw)
        if (!spreadStillBad && !unsupportedHighHip) return null

        val supportedEndpointBias = if (unsupportedHighHip) {
            0f
        } else {
            optimized.heightEndpointBiasMeters
                .takeIf { it.isFinite() }
                ?.coerceIn(MIN_HEIGHT_ENDPOINT_BIAS_METERS, MAX_HEIGHT_ENDPOINT_BIAS_METERS)
                ?.coerceAtLeast(0f)
                ?: heightEndpointBiasMeters.coerceAtLeast(0f)
        }
        val correctionTarget = (top + supportedEndpointBias)
            .coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
        return correctionTarget.takeIf { it < locked - 0.015f }
    }

    private fun applyStartupBadLockCorrection(target: Float) {
        val validTarget = target.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return
        if (!lockedHeightMeters.isFinite()) return
        val delta = (validTarget - lockedHeightMeters)
            .coerceIn(-STARTUP_BAD_LOCK_CORRECTION_MAX_STEP_METERS, 0f)
        if (delta >= 0f) return
        lockedHeightMeters = (lockedHeightMeters + delta * STARTUP_BAD_LOCK_CORRECTION_ALPHA)
            .coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
        rememberLowerHeightAnchor(lockedHeightMeters)
        stableHeightFrames = (stableHeightFrames - 2).coerceAtLeast(0)
    }

    private fun hasUnsupportedStartupHighHip(raw: SceneMetricSnapshot): Boolean {
        val hip = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return false
        val top = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return false
        if (hip <= top + STARTUP_HIGH_HIP_TOP_GAP_METERS) return false
        val reference = maxOf(hip, top)
        val torsoCollapsed = raw.torsoHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?.let { it < reference - STARTUP_COLLAPSED_WITNESS_GAP_METERS }
            ?: true
        val pixelCollapsed = raw.pixelSpanHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?.let { it < reference - STARTUP_COLLAPSED_WITNESS_GAP_METERS }
            ?: true
        return torsoCollapsed && pixelCollapsed
    }

    private fun startupDistanceReady(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
    ): Boolean {
        val distance = optimized.distanceMeters
            .takeIf { it.isFinite() && it in 0.35f..12.0f }
            ?: return false
        return optimized.distanceTrusted &&
            raw.confidence >= STARTUP_MIN_LOCK_CONFIDENCE &&
            distance.isFinite() &&
            (
                optimized.distanceCandidateSpreadMeters.isFinite() &&
                    optimized.distanceCandidateSpreadMeters <= STARTUP_DISTANCE_SPREAD_MAX_METERS ||
                    groundedFootRoiDistanceReady(raw)
                )
    }

    private fun groundedFootRoiDistanceReady(raw: SceneMetricSnapshot): Boolean {
        val foot = raw.groundedFootDistanceMeters
            .takeIf { it.isFinite() && it in 0.35f..12.0f }
            ?: raw.footPlaneDistanceMeters.takeIf {
                raw.footContactState == "grounded" || raw.footContactState == "grounded_roi_supported"
            }?.takeIf { it.isFinite() && it in 0.35f..12.0f }
            ?: return false
        val roi = raw.roiDistanceMeters
            .takeIf { it.isFinite() && it in 0.35f..12.0f }
            ?: return false
        return candidateAgreement(foot, roi, maxAbsDelta = 0.28f, maxRatio = 0.12f)
    }

    private fun hasBracketedUpperSpanSupport(raw: SceneMetricSnapshot, top: Float): Boolean =
        bracketedUpperSpanTarget(raw, top) != null

    private fun bracketedUpperSpanTarget(raw: SceneMetricSnapshot, top: Float): Float? {
        if (raw.bodyScaleConfidence.isFinite() && raw.bodyScaleConfidence < SPAN_BRACKET_MIN_BODY_SCALE_CONFIDENCE) {
            return null
        }
        val hip = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        if (hip != null && hip > top + STARTUP_HIGH_HIP_TOP_GAP_METERS) return null
        val highTorso = raw.torsoHeightMeters.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                candidate > top + SPAN_BRACKET_MIN_GAP_METERS &&
                candidate < top + SPAN_BRACKET_MAX_GAP_METERS &&
                hip?.let { candidate > it + SPAN_BRACKET_MIN_GAP_METERS } != false
        }
        val highPixel = raw.pixelSpanHeightMeters.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                candidate > top + SPAN_BRACKET_MIN_GAP_METERS &&
                candidate < top + SPAN_BRACKET_MAX_GAP_METERS &&
                hip?.let { candidate > it + SPAN_BRACKET_MIN_GAP_METERS } != false
        }
        val pixelOnly = raw.pixelSpanHeightMeters.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                candidate > top + MIN_PIXEL_ONLY_BRACKET_GAP_METERS &&
                candidate < top + MAX_PIXEL_ONLY_BRACKET_GAP_METERS &&
                hip?.let { candidate >= it - 0.04f } != false
        }
        val witnesses = listOfNotNull(highTorso, highPixel ?: pixelOnly)
        val upper = witnesses.minOrNull() ?: return null
        val interpolation = if (highTorso == null && highPixel == null && pixelOnly != null) {
            PIXEL_ONLY_BRACKET_HEIGHT_INTERPOLATION
        } else {
            SPAN_BRACKET_INTERPOLATION
        }
        return (top + (upper - top) * interpolation)
            .coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
    }

    private fun constrainHeightTargetToAnchor(
        target: Float,
        matureRetargetActive: Boolean = false,
    ): Float {
        val validTarget = target.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return target
        val anchor = heightLockAnchorMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: lockedHeightMeters.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return validTarget
        // When the latent subject-height estimator is actively lifting the lock
        // we relax the anchor floor so lower-anchor memory does not pin it back
        // down. This is evidence-relative, not tied to any particular stature.
        val downwardMargin = if (
            matureRetargetActive &&
            validTarget > anchor
        ) {
            HEIGHT_LOCK_DOWNWARD_MARGIN_METERS + 0.040f
        } else {
            HEIGHT_LOCK_DOWNWARD_MARGIN_METERS
        }
        return validTarget.coerceIn(
            anchor - downwardMargin,
            anchor + heightLockUpwardMarginMeters(anchor, validTarget, matureRetargetActive),
        )
    }

    private fun rememberLowerHeightAnchor(height: Float) {
        val validHeight = height.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return
        heightLockAnchorMeters = heightLockAnchorMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?.let { minOf(it, validHeight) }
            ?: validHeight
    }

    private data class FactorValue(val name: String, val value: Float, val weight: Float)
    private data class HeightLockResult(
        val heightMeters: Float,
        val state: String,
        val exportAsConstraint: Boolean,
    )
    private data class LocalHeightCandidate(
        val meters: Float,
        val confidence: Float,
        val source: String,
    )

    private fun estimateLocalHeightCandidate(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
        heightLock: HeightLockResult,
        exportedCorrectedHeight: Float,
        exportedHeightConfidence: Float,
    ): LocalHeightCandidate? {
        val candidate = heightLock.heightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: optimized.heightMeters.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        val topValues = listOf(raw.topRayFloorHeightMeters, raw.topRayHeightMeters)
            .filter { it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS }
        val topHeight = topValues.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val topGapMeters = topHeight?.let { abs(candidate - it) }
        val tightTopSupport = topGapMeters != null && topGapMeters <= 0.075f
        val topSupport = topGapMeters != null && topGapMeters <= 0.18f
        val hipSupport = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS }
            ?.let { abs(candidate - it) <= 0.18f } == true
        val correctedSupport = exportedCorrectedHeight
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?.let { abs(candidate - it) <= 0.12f } == true
        val bodyScaleSupport = raw.bodyScaleConfidence.isFinite() && raw.bodyScaleConfidence >= 0.80f
        val trustedLockSupport = heightLock.exportAsConstraint && !heightLock.state.contains("untrusted")
        val strongIndependentSupport = correctedSupport || hipSupport || tightTopSupport || trustedLockSupport
        val endpointSupport = raw.topEndpointConfidence >= 0.35f ||
            raw.maskEndpointConfidence >= 0.35f ||
            raw.confidence >= 0.50f
        if (!endpointSupport) return null
        if (!(correctedSupport || topSupport || hipSupport || bodyScaleSupport || trustedLockSupport)) {
            return null
        }
        var confidence = maxOf(
            exportedHeightConfidence.takeIf { it.isFinite() } ?: 0f,
            if (bodyScaleSupport && strongIndependentSupport) {
                (raw.bodyScaleConfidence.takeIf { it.isFinite() } ?: 0f) * 0.72f
            } else if (bodyScaleSupport && topSupport) {
                0.38f
            } else {
                0f
            },
            if (correctedSupport) 0.70f else 0f,
            if (hipSupport) 0.62f else 0f,
            if (tightTopSupport) 0.60f else if (topSupport) 0.42f else 0f,
            if (trustedLockSupport) 0.62f else 0f,
        ).coerceIn(0f, 1f)
        val weakAcquiring = !trustedLockSupport &&
            (heightLock.state.startsWith("acquiring") || heightLock.state.contains("untrusted")) &&
            exportedHeightConfidence < 0.20f
        if (weakAcquiring && !strongIndependentSupport) {
            confidence = minOf(confidence, 0.42f)
        }
        if (weakAcquiring && topSupport && bodyScaleSupport && !tightTopSupport && !correctedSupport && !hipSupport) {
            confidence = minOf(confidence, 0.42f)
        }
        if (confidence < 0.28f) return null
        val source = when {
            correctedSupport -> "corrected_height_continuity"
            trustedLockSupport -> "height_lock"
            hipSupport -> "local_display_hip_supported"
            tightTopSupport && bodyScaleSupport -> "local_display_top_tight"
            topSupport && bodyScaleSupport -> "local_display_top_supported"
            else -> "local_display_height"
        }
        return LocalHeightCandidate(candidate, confidence, source)
    }

    private fun isSemanticHeightTrusted(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
    ): Boolean {
        if (!optimized.heightTrusted) return false
        if (hasLockedHeightSilhouetteNoise(raw)) return false
        if (hasStrongLockedHeightConflict(raw)) return false
        if (optimized.heightCandidateSpreadMeters > 0.22f) return false
        if (optimized.solverResidualMeters > 0.55f) return false
        if ("untrusted_height_spread" in optimized.activeFactors) return false
        return hasSemanticHeightAgreement(raw)
    }

    private fun hasStrongLockedHeightConflict(raw: SceneMetricSnapshot): Boolean {
        val locked = lockedHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return false
        val primaryWitnesses = listOfNotNull(
            raw.hipGeometryHeightMeters.takeIf { it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS },
            raw.topRayHeightMeters.takeIf { it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS },
            raw.torsoHeightMeters.takeIf { it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS },
        )
        if (primaryWitnesses.size < 2) return false
        if (primaryWitnesses.any { abs(it - locked) <= 0.16f }) return false
        return primaryWitnesses.count { abs(it - locked) > 0.35f } >= 2
    }

    private fun hasLockedHeightSilhouetteNoise(raw: SceneMetricSnapshot): Boolean {
        val locked = lockedHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return false
        val hip = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return false
        val top = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return false
        if (abs(hip - locked) > 0.08f || top <= locked + 0.055f) return false
        val torso = raw.torsoHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val pixel = raw.pixelSpanHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val pixelHigh = pixel?.let { it > locked + 0.20f } == true
        val torsoHighUnsupported = torso != null &&
            torso > locked + 0.12f &&
            abs(torso - top) > 0.10f
        return pixelHigh || torsoHighUnsupported
    }

    private fun shouldKeepLockedHeightConstraint(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
        heightQualityTrusted: Boolean,
    ): Boolean {
        if (heightQualityTrusted) return false
        val locked = lockedHeightMeters.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return false
        if (hasStrongLockedHeightConflict(raw)) return false
        val endpointBias = (optimized.heightEndpointBiasMeters.takeIf { it.isFinite() } ?: heightEndpointBiasMeters)
            .coerceIn(MIN_HEIGHT_ENDPOINT_BIAS_METERS, MAX_HEIGHT_ENDPOINT_BIAS_METERS)
        val semanticEndpointBias = endpointBias.coerceAtLeast(0f)
        val hip = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val rawTop = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val rawPixel = raw.pixelSpanHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val supportedEndpointBias = semanticEndpointBias.takeIf { bias ->
            bias > 0f &&
                rawTop != null &&
                (
                    rawTop >= locked - MAX_TOP_LOW_BIAS_MASK_GAP_METERS ||
                        listOfNotNull(rawPixel, raw.torsoHeightMeters.takeIf { it.isFinite() })
                            .any { abs(it - (rawTop + bias)) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP_METERS }
                    )
        } ?: 0f
        val top = ((rawTop ?: Float.NaN) + supportedEndpointBias)
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val torso = raw.torsoHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val reliablePrimaryWitnesses = buildList {
            hip?.let { add(it) }
            top
                ?.takeIf { candidate ->
                    hip?.let { abs(candidate - it) <= 0.22f } == true ||
                        abs(candidate - locked) <= 0.22f
                }
                ?.let { add(it) }
            torso
                ?.takeIf { candidate ->
                    listOfNotNull(hip, top, locked).any { abs(candidate - it) <= 0.22f }
                }
                ?.let { add(it) }
        }
        rawTop
            ?.let { bracketedUpperSpanTarget(raw, it) }
            ?.takeIf { abs(it - locked) <= 0.14f }
            ?.let { return true }
        if (reliablePrimaryWitnesses.none { abs(it - locked) <= 0.16f }) return false
        val strongPrimaryConflict =
            hip?.let { abs(it - locked) > 0.42f } == true &&
                top?.let { abs(it - locked) > 0.42f } == true
        return !strongPrimaryConflict
    }

    private fun estimateSemanticHeightRetarget(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
    ): Float? {
        val endpointBias = (optimized.heightEndpointBiasMeters.takeIf { it.isFinite() } ?: heightEndpointBiasMeters)
            .coerceIn(MIN_HEIGHT_ENDPOINT_BIAS_METERS, MAX_HEIGHT_ENDPOINT_BIAS_METERS)
        val locked = lockedHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val rawHip = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val torso = raw.torsoHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val rawTop = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val rawPixel = raw.pixelSpanHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val positiveEndpointBias = endpointBias.coerceAtLeast(0f)
        val semanticEndpointBias = positiveEndpointBias.takeIf { bias ->
            bias > 0f &&
                rawTop != null &&
                (
                    locked == null ||
                        rawTop >= locked - MAX_TOP_LOW_BIAS_MASK_GAP_METERS ||
                        listOfNotNull(torso, rawPixel)
                            .any { abs(it - (rawTop + bias)) <= MAX_ENDPOINT_BIAS_SUPPORT_GAP_METERS }
                    )
        } ?: 0f
        val topCandidate = rawTop
            ?.let { it + semanticEndpointBias }
            ?.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val upperBodyLift = if (
            locked == null &&
            rawHip != null &&
            torso != null &&
            topCandidate != null &&
            topCandidate > rawHip + 0.070f &&
            torso > rawHip + 0.035f &&
            abs(topCandidate - torso) <= 0.095f
        ) {
            val upperBody = (torso * 0.66f + topCandidate * 0.34f)
                .coerceIn(rawHip, topCandidate - 0.020f)
            (rawHip * 0.45f + upperBody * 0.55f)
                .coerceIn(rawHip, topCandidate - 0.030f)
        } else {
            null
        }
        val hip = (upperBodyLift ?: rawHip)
            ?.let { candidate ->
                locked?.let {
                    candidate.coerceIn(
                        it - HEIGHT_LOCK_DOWNWARD_MARGIN_METERS,
                        it + heightLockUpwardMarginMeters(it, candidate),
                    )
                } ?: candidate
            }
        val primaryAnchor = locked ?: hip
        val top = topCandidate?.takeIf { candidate ->
            primaryAnchor?.let { abs(candidate - it) <= 0.22f } == true
        }?.let { candidate ->
            val anchor = primaryAnchor ?: candidate
            when {
                candidate > anchor -> candidate.coerceAtMost(anchor + heightLockUpwardMarginMeters(anchor, candidate))
                else -> candidate.coerceAtLeast(anchor - 0.025f)
            }
        }
        val pixel = (raw.pixelSpanHeightMeters + semanticEndpointBias)
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?.takeIf { candidate ->
                listOfNotNull(hip, top, locked).any { abs(candidate - it) <= 0.18f }
            }
            ?.let { candidate ->
                val anchor = locked ?: hip ?: top ?: candidate
                when {
                    candidate > anchor -> candidate.coerceAtMost(anchor + heightLockUpwardMarginMeters(anchor, candidate))
                    else -> candidate.coerceAtLeast(anchor - 0.025f)
                }
            }
        val anchors = listOfNotNull(hip, top)
        if (anchors.isEmpty()) return null

        val weighted = mutableListOf<Pair<Float, Float>>()
        hip?.let { weighted += it to 0.72f }
        top?.let { weighted += it to if (hip != null) 0.08f else 0.22f }
        torso
            ?.takeIf { candidate -> anchors.any { abs(candidate - it) <= 0.22f } }
            ?.let { candidate ->
                val low = (anchors.minOrNull() ?: candidate) - 0.035f
                val high = (anchors.maxOrNull() ?: candidate) + 0.025f
                weighted += candidate.coerceIn(low, high) to 0.18f
            }
        pixel?.let { weighted += it to 0.02f }
        if (weighted.size < 2) return null
        val totalWeight = weighted.sumOf { it.second.toDouble() }.toFloat()
        if (totalWeight <= 1e-5f) return null
        val target = weighted.sumOf { (value, weight) -> (value * weight).toDouble() }.toFloat() / totalWeight
        return target.coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
    }

    private fun estimateBracketedSpanHeightRetarget(raw: SceneMetricSnapshot): Float? {
        val locked = lockedHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        val top = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        val topAboveLock = top > locked + SPAN_BRACKET_MIN_TOP_LOCK_GAP_METERS
        val hip = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        if (hip != null && hip > top + STARTUP_HIGH_HIP_TOP_GAP_METERS) return null
        if (raw.bodyScaleConfidence.isFinite() && raw.bodyScaleConfidence < SPAN_BRACKET_MIN_BODY_SCALE_CONFIDENCE) {
            return null
        }
        val highTorso = raw.torsoHeightMeters.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                candidate > top + SPAN_BRACKET_MIN_GAP_METERS &&
                candidate < top + SPAN_BRACKET_MAX_GAP_METERS &&
                hip?.let { candidate > it + SPAN_BRACKET_MIN_GAP_METERS } != false
        }
        val highPixel = raw.pixelSpanHeightMeters.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                candidate > top + SPAN_BRACKET_MIN_GAP_METERS &&
                candidate < top + SPAN_BRACKET_MAX_GAP_METERS &&
                hip?.let { candidate > it + SPAN_BRACKET_MIN_GAP_METERS } != false
        }
        val pixelOnly = raw.pixelSpanHeightMeters.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                topAboveLock &&
                candidate > top + MIN_PIXEL_ONLY_BRACKET_GAP_METERS &&
                candidate < top + MAX_PIXEL_ONLY_BRACKET_GAP_METERS &&
                hip?.let { candidate >= it - 0.04f } != false
        }
        val upperWitnesses = listOfNotNull(highTorso, highPixel ?: pixelOnly)
        if (upperWitnesses.isEmpty()) return null
        val sortedWitnesses = upperWitnesses.sorted()
        val upper = sortedWitnesses[(sortedWitnesses.size - 1) / 2]
        val interpolation = if (highTorso == null && highPixel == null && pixelOnly != null) {
            PIXEL_ONLY_BRACKET_HEIGHT_INTERPOLATION
        } else {
            SPAN_BRACKET_INTERPOLATION
        }
        val target = (top + (upper - top) * interpolation)
            .coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
        val highBracketSupport = highTorso != null
        if (!topAboveLock && (!highBracketSupport || top < locked - SPAN_BRACKET_TOP_BELOW_LOCK_TOLERANCE_METERS)) {
            return null
        }
        return target.takeIf { it > locked + HEIGHT_LOCK_UPWARD_MARGIN_METERS }
    }

    private fun estimateLooseBracketedSpanHeightRetarget(raw: SceneMetricSnapshot): Float? {
        val locked = lockedHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        val top = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
            ?: return null
        val topAboveLock = top > locked + SPAN_BRACKET_MIN_TOP_LOCK_GAP_METERS
        val hip = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        if (hip != null && hip > top + STARTUP_HIGH_HIP_TOP_GAP_METERS) return null
        val highTorso = raw.torsoHeightMeters.takeIf {
            it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                it > top + 0.20f && it < top + 0.95f
        }
        val highPixel = raw.pixelSpanHeightMeters.takeIf {
            it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                it > top + 0.20f && it < top + 0.95f
        }
        val pixelOnly = raw.pixelSpanHeightMeters.takeIf { candidate ->
            candidate.isFinite() &&
                candidate in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                topAboveLock &&
                candidate > top + MIN_PIXEL_ONLY_BRACKET_GAP_METERS &&
                candidate < top + MAX_PIXEL_ONLY_BRACKET_GAP_METERS &&
                hip?.let { candidate >= it - 0.04f } != false
        }
        val upper = listOfNotNull(highTorso, highPixel ?: pixelOnly)
            .minOrNull()
            ?: return null
        val interpolation = if (highTorso == null && highPixel == null && pixelOnly != null) {
            PIXEL_ONLY_BRACKET_HEIGHT_INTERPOLATION
        } else {
            SPAN_BRACKET_INTERPOLATION
        }
        val target = (top + (upper - top) * interpolation)
            .coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
        val highBracketSupport = highTorso != null
        if (!topAboveLock && (!highBracketSupport || top < locked - SPAN_BRACKET_TOP_BELOW_LOCK_TOLERANCE_METERS)) {
            return null
        }
        return target.takeIf { it > locked + HEIGHT_LOCK_UPWARD_MARGIN_METERS }
    }

    private fun hasSemanticHeightAgreement(raw: SceneMetricSnapshot): Boolean {
        // Hip geometry already fuses the top-ray and hip-ray observations through
        // an LSQ that enforces the hip-center:height anthropometric ratio (with a
        // 0.18 ratio-error rejection). When it converges to an in-range value
        // and is INDEPENDENT of torso (not seeded from the same torso ratio),
        // it is itself multi-evidence and can stand alone — torso (low bias)
        // and top-ray (high bias) frequently never align with each other
        // within 8 cm. The duplication guard (hip ≈ torso ⇒ same source)
        // remains so a single torso-derived value does not falsely trust.
        val hip = raw.hipGeometryHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val torso = raw.torsoHeightMeters.takeIf { it.isFinite() }
        val top = raw.topRayHeightMeters
            .takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val hipIsIndependent = hip != null && (torso == null || abs(hip - torso) > 0.015f)
        val hipDoesNotConflict = hip != null && (torso == null || abs(hip - torso) <= 0.22f)
        val hipDoesNotMaskLowTop = hip != null &&
            (top == null || hip <= top + 0.100f)
        if (hipIsIndependent && hipDoesNotConflict && hipDoesNotMaskLowTop) return true

        val values = independentSemanticHeightValues(
            hipGeometryHeight = raw.hipGeometryHeightMeters,
            torsoHeight = raw.torsoHeightMeters,
            topRayHeight = raw.topRayHeightMeters,
        )
        if (values.size < 2) return false
        var closePairs = 0
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                if (abs(values[i] - values[j]) <= 0.08f) closePairs += 1
            }
        }
        if (closePairs > 0) return true
        return values.size >= 3 && values.maxOrNull()!! - values.minOrNull()!! <= 0.14f
    }

    private fun heightTrustRejectionLabels(
        raw: SceneMetricSnapshot,
        optimized: PhysicalSceneOptimizerResult,
        heightQualityTrusted: Boolean,
    ): List<String> {
        if (heightQualityTrusted) return emptyList()
        return buildList {
            if (optimized.heightCandidateSpreadMeters > 0.22f || "untrusted_height_spread" in optimized.activeFactors) {
                add("rejected_height_spread")
            }
            if (!hasSemanticHeightAgreement(raw)) {
                add("rejected_height_semantic_agreement")
            }
            if (optimized.solverResidualMeters > 0.55f) {
                add("rejected_height_residual")
            }
            add("rejected_torso_fallback_lock")
        }
    }

    private fun independentSemanticHeightValues(
        hipGeometryHeight: Float,
        torsoHeight: Float,
        topRayHeight: Float,
    ): List<Float> {
        val torso = torsoHeight.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val hip = hipGeometryHeight.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        val top = topRayHeight.takeIf { it.isFinite() && it in MIN_BODY_HEIGHT_METERS..MAX_BODY_HEIGHT_METERS }
        return buildList {
            torso?.let { add(it) }
            // In the current geometry path hipGeometryHeight can be seeded by the
            // torso ratio estimate. If it is numerically the same as torso height,
            // it is not an independent witness and must not create false trust.
            if (hip != null && (torso == null || abs(hip - torso) > 0.015f)) {
                add(hip)
            }
            top?.let { add(it) }
        }
    }

    private fun relativeScaleAnchorLabels(
        relativeScaleDistance: Float,
        heightLock: HeightLockResult,
        heightQualityTrusted: Boolean,
    ): List<String> =
        if (relativeScaleDistance.isFinite() && (!heightQualityTrusted || heightLock.state != "locked")) {
            listOf("relative_anchor_untrusted")
        } else {
            emptyList()
        }

    private fun removeFactorLabels(activeFactors: String, blocked: Set<String>): String =
        activeFactors
            .split("|")
            .filter { it.isNotBlank() && it !in blocked }
            .joinToString("|")

    private fun appendFactorLabels(activeFactors: String, labels: List<String>): String {
        if (labels.isEmpty()) return activeFactors
        return (activeFactors.split("|").filter { it.isNotBlank() } + labels)
            .distinct()
            .joinToString("|")
    }

    private fun candidateAgreement(a: Float, b: Float): Boolean {
        if (!a.isFinite() || !b.isFinite()) return false
        val absDelta = abs(a - b)
        val ratio = absDelta / maxOf(a, b, 1e-4f)
        return absDelta <= 1.25f || ratio <= 0.35f
    }

    private fun robustWeightedAverage(factors: List<FactorValue>): Float? {
        val valid = factors.filter { it.value.isFinite() && it.weight > 0f }
        if (valid.isEmpty()) return null
        val median = valid.map { it.value }.sorted()[valid.size / 2]
        var totalWeight = 0f
        var weighted = 0f
        for (factor in valid) {
            val residual = abs(factor.value - median)
            val robustWeight = factor.weight / (1f + (residual / 0.22f) * (residual / 0.22f))
            weighted += factor.value * robustWeight
            totalWeight += robustWeight
        }
        return if (totalWeight > 1e-5f) weighted / totalWeight else median
    }

    private fun factorSpread(factors: List<FactorValue>): Float {
        val values = factors.mapNotNull { it.value.takeIf { value -> value.isFinite() } }
        if (values.size < 2) return 0f
        return (values.maxOrNull() ?: 0f) - (values.minOrNull() ?: 0f)
    }

    private fun weightedResidual(factors: List<FactorValue>, target: Float): Float {
        if (!target.isFinite()) return 0f
        var total = 0f
        var weight = 0f
        for (factor in factors) {
            if (!factor.value.isFinite()) continue
            total += abs(factor.value - target) * factor.weight
            weight += factor.weight
        }
        return if (weight > 1e-5f) total / weight else 0f
    }
}

internal data class PoseOverlayRectPx(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal fun computePoseRoi(
    screenX: FloatArray?,
    screenY: FloatArray?,
    visibility: FloatArray?,
): PoseRoi? {
    if (screenX == null || screenY == null || screenX.size < 33 || screenY.size < 33) {
        return null
    }

    var minX = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY

    for (i in 0 until 33) {
        val visible = visibility?.getOrNull(i) ?: 1f
        if (visible <= 0.2f) continue
        minX = minOf(minX, screenX[i])
        maxX = maxOf(maxX, screenX[i])
        minY = minOf(minY, screenY[i])
        maxY = maxOf(maxY, screenY[i])
    }

    if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite()) {
        return null
    }
    if (maxX <= minX || maxY <= minY) {
        return null
    }

    return PoseRoi(
        minX = minX.coerceIn(0f, 1f),
        maxX = maxX.coerceIn(0f, 1f),
        minY = minY.coerceIn(0f, 1f),
        maxY = maxY.coerceIn(0f, 1f),
    )
}

internal fun deriveOverlayPoseEstimate(
    roi: PoseRoi?,
    rawSubjectHeightMeters: Float,
    screenX: FloatArray? = null,
    screenY: FloatArray? = null,
    visibility: FloatArray? = null,
    viewportAspect: Float = DEFAULT_VIEWPORT_ASPECT,
    worldTracking: WorldTrackingSnapshot? = null,
    intrinsics: CameraIntrinsics? = null,
    previousHipVectorXNorm: Float = Float.NaN,
    previousHipVectorYNorm: Float = Float.NaN,
    visualTopYNorm: Float = Float.NaN,
    visualTopConfidence: Float = Float.NaN,
): OverlayPoseEstimate? {
    roi ?: return null
    val effectiveIntrinsics = worldTracking?.intrinsics ?: intrinsics
    val hipProxy = selectHipAnchorProxy(
        roi = roi,
        screenX = screenX,
        screenY = screenY,
        visibility = visibility,
        previousVectorXNorm = previousHipVectorXNorm,
        previousVectorYNorm = previousHipVectorYNorm,
    )
    val arEstimate = deriveArCoreFloorEstimate(
        roi = roi,
        screenX = screenX,
        screenY = screenY,
        visibility = visibility,
        worldTracking = worldTracking,
        intrinsics = effectiveIntrinsics,
        rawSubjectHeightMeters = rawSubjectHeightMeters,
        hipProxy = hipProxy,
        visualTopYNorm = visualTopYNorm,
        visualTopConfidence = visualTopConfidence,
    )
    val arBodyHeight = arEstimate?.bodyHeightMeters
        ?.takeIf { it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS }

    val trackingBodyHeight = worldTracking?.subjectHeightMeters?.takeIf { it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS }
    val bodyHeightMeters = when {
        arBodyHeight != null -> arBodyHeight
        trackingBodyHeight != null -> trackingBodyHeight
        rawSubjectHeightMeters.isFinite() &&
            rawSubjectHeightMeters in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS -> {
            rawSubjectHeightMeters
        }
        else -> Float.NaN
    }

    val roiHeight = roi.height.coerceIn(0.18f, 0.94f)
    val verticalFovHalfTangent = effectiveIntrinsics
        ?.takeIf { it.fy > 1f && it.imageHeight > 1 }
        ?.let { it.imageHeight.toFloat() / (2f * it.fy) }
        ?: tan(Math.toRadians((ASSUMED_VERTICAL_FOV_DEGREES * 0.5f).toDouble())).toFloat()
    val horizontalFovHalfTangent = effectiveIntrinsics
        ?.takeIf { it.fx > 1f && it.imageWidth > 1 }
        ?.let { it.imageWidth.toFloat() / (2f * it.fx) }
        ?: (verticalFovHalfTangent * viewportAspect.coerceIn(0.45f, 1.20f))

    val roiDistanceMeters = if (bodyHeightMeters.isFinite()) {
        (bodyHeightMeters / (2f * verticalFovHalfTangent * roiHeight)).coerceIn(0.70f, 12.0f)
    } else {
        Float.NaN
    }
    val distanceMeters = arEstimate?.distanceMeters
        ?: worldTracking?.subjectDistanceMeters
        ?.takeIf { it.isFinite() && it in 0.45f..12.0f }
        ?: roiDistanceMeters

    // ROI geometry remains only the non-AR fallback path. AR Technical metrics use
    // visible foot landmarks first, then ROI bottom only when foot contact is missing.
    val roiFloorPitchDegrees = (5f + roi.bottomGap * 68f).coerceIn(4f, 32f)
    val floorPitchDegrees = arEstimate?.floorPitchDegrees
        ?: worldTracking?.floorPitchDegrees
        ?.takeIf { it.isFinite() }
        ?.let { kotlin.math.abs(it).coerceIn(4f, 42f) }
        ?: roiFloorPitchDegrees
    val roiCameraHeightMeters = if (bodyHeightMeters.isFinite()) {
        (bodyHeightMeters * (0.60f + roi.bottomGap * 1.05f)).coerceIn(0.65f, 2.40f)
    } else {
        Float.NaN
    }
    val cameraHeightMeters = arEstimate?.cameraHeightMeters
        ?: worldTracking?.cameraHeightMeters
        ?.takeIf { it.isFinite() && it in 0.25f..2.80f }
        ?: roiCameraHeightMeters
    val lateralOffsetMeters = arEstimate?.lateralOffsetMeters ?: (
        (roi.centerX - 0.5f) * 2f * distanceMeters * horizontalFovHalfTangent
    ).coerceIn(-2.75f, 2.75f)
    val hasArFloorEstimate = arEstimate != null
    val source = when {
        hasArFloorEstimate && arEstimate?.floorSource == "arcore_floor_provisional" -> "arcore_floor_provisional"
        hasArFloorEstimate -> "arcore_floor"
        worldTracking?.source?.startsWith("imu") == true -> "imu_roi_fallback"
        else -> "roi_fallback"
    }
    val confidence = when {
        arEstimate != null -> arEstimate.confidence
        worldTracking?.source?.startsWith("imu") == true -> 0.50f
        else -> 0.35f
    }

    return OverlayPoseEstimate(
        distanceMeters = distanceMeters,
        bodyHeightMeters = bodyHeightMeters,
        cameraHeightMeters = cameraHeightMeters,
        floorPitchDegrees = floorPitchDegrees,
        lateralOffsetMeters = lateralOffsetMeters,
        roi = roi,
        source = source,
        confidence = confidence,
        learnedHipVectorXNorm = arEstimate?.learnedHipVectorXNorm ?: hipProxy?.learnedVectorXNorm ?: Float.NaN,
        learnedHipVectorYNorm = arEstimate?.learnedHipVectorYNorm ?: hipProxy?.learnedVectorYNorm ?: Float.NaN,
        correctedDistanceMeters = arEstimate?.distanceMeters ?: Float.NaN,
        correctedHeightMeters = arEstimate?.bodyHeightMeters ?: Float.NaN,
        correctedCameraHeightMeters = arEstimate?.cameraHeightMeters ?: Float.NaN,
        floorSource = arEstimate?.floorSource ?: source,
        solverConfidence = arEstimate?.confidence ?: Float.NaN,
        rawHipDepthDistanceMeters = arEstimate?.rawHipDepthDistanceMeters ?: Float.NaN,
        footPlaneDistanceMeters = arEstimate?.footPlaneDistanceMeters ?: Float.NaN,
        roiDistanceMeters = arEstimate?.roiDistanceMeters ?: roiDistanceMeters,
        topRayHeightMeters = arEstimate?.topRayHeightMeters ?: Float.NaN,
        pixelSpanHeightMeters = arEstimate?.pixelSpanHeightMeters ?: Float.NaN,
        hipGeometryDistanceMeters = arEstimate?.hipGeometryDistanceMeters ?: Float.NaN,
        hipGeometryHeightMeters = arEstimate?.hipGeometryHeightMeters ?: Float.NaN,
        torsoHeightMeters = arEstimate?.torsoHeightMeters ?: Float.NaN,
        torsoResidualMeters = arEstimate?.torsoResidualMeters ?: Float.NaN,
        groundedFootDistanceMeters = arEstimate?.groundedFootDistanceMeters ?: Float.NaN,
        footContactState = arEstimate?.footContactState ?: "",
        leftFootRayFloorDistanceMeters = arEstimate?.leftFootRayFloorDistanceMeters ?: Float.NaN,
        rightFootRayFloorDistanceMeters = arEstimate?.rightFootRayFloorDistanceMeters ?: Float.NaN,
        feetMidpointFloorDistanceMeters = arEstimate?.feetMidpointFloorDistanceMeters ?: Float.NaN,
        nearestFootFloorDistanceMeters = arEstimate?.nearestFootFloorDistanceMeters ?: Float.NaN,
        footRayFloorSpreadMeters = arEstimate?.footRayFloorSpreadMeters ?: Float.NaN,
        topRayFloorHeightMeters = arEstimate?.topRayFloorHeightMeters ?: Float.NaN,
        distanceHeightGeometryResidualMeters = arEstimate?.distanceHeightGeometryResidualMeters ?: Float.NaN,
        rootHipRayFloorDistanceMeters = arEstimate?.rootHipRayFloorDistanceMeters ?: Float.NaN,
        boneLengthSpreadMeters = arEstimate?.boneLengthSpreadMeters ?: Float.NaN,
        bodyScaleConfidence = arEstimate?.bodyScaleConfidence ?: Float.NaN,
        hipCenterXNorm = arEstimate?.hipCenterXNorm ?: hipProxy?.centerXNorm ?: Float.NaN,
        hipCenterYNorm = arEstimate?.hipCenterYNorm ?: hipProxy?.centerYNorm ?: Float.NaN,
        shoulderHipSpanNorm = arEstimate?.shoulderHipSpanNorm ?: Float.NaN,
        torsoSpanNorm = arEstimate?.torsoSpanNorm ?: Float.NaN,
        bodyRoiHeightNorm = arEstimate?.bodyRoiHeightNorm ?: roi.height,
        relativeScaleDistanceMeters = arEstimate?.relativeScaleDistanceMeters ?: Float.NaN,
        rejectedHipReason = arEstimate?.rejectedHipReason ?: "",
        heightSeedTrusted = rawSubjectHeightMeters.isFinite(),
        bodyClipRisk = arEstimate?.bodyClipRisk ?: Float.NaN,
        topEndpointConfidence = arEstimate?.topEndpointConfidence ?: Float.NaN,
        footEndpointConfidence = arEstimate?.footEndpointConfidence ?: Float.NaN,
        maskEndpointConfidence = arEstimate?.maskEndpointConfidence ?: Float.NaN,
        visualTopScanYNorm = arEstimate?.visualTopScanYNorm ?: Float.NaN,
        visualTopScanConfidence = arEstimate?.visualTopScanConfidence ?: Float.NaN,
        visualTopLiftNorm = arEstimate?.visualTopLiftNorm ?: Float.NaN,
        rawKeypointGeometry = buildRawKeypointGeometryMap(screenX, screenY, visibility),
    )
}

private data class ArFloorEstimate(
    val distanceMeters: Float,
    val bodyHeightMeters: Float,
    val cameraHeightMeters: Float,
    val floorPitchDegrees: Float,
    val lateralOffsetMeters: Float,
    val confidence: Float,
    val learnedHipVectorXNorm: Float = Float.NaN,
    val learnedHipVectorYNorm: Float = Float.NaN,
    val floorSource: String = "arcore_floor",
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
    val distanceHeightGeometryResidualMeters: Float = Float.NaN,
    val rootHipRayFloorDistanceMeters: Float = Float.NaN,
    val boneLengthSpreadMeters: Float = Float.NaN,
    val bodyScaleConfidence: Float = Float.NaN,
    val hipCenterXNorm: Float = Float.NaN,
    val hipCenterYNorm: Float = Float.NaN,
    val shoulderHipSpanNorm: Float = Float.NaN,
    val torsoSpanNorm: Float = Float.NaN,
    val bodyRoiHeightNorm: Float = Float.NaN,
    val relativeScaleDistanceMeters: Float = Float.NaN,
    val rejectedHipReason: String = "",
    val heightSeedTrusted: Boolean = false,
    val bodyClipRisk: Float = Float.NaN,
    val topEndpointConfidence: Float = Float.NaN,
    val footEndpointConfidence: Float = Float.NaN,
    val maskEndpointConfidence: Float = Float.NaN,
    val visualTopScanYNorm: Float = Float.NaN,
    val visualTopScanConfidence: Float = Float.NaN,
    val visualTopLiftNorm: Float = Float.NaN,
    val rawKeypointGeometry: Map<String, Float> = emptyMap(),
)

private data class GroundContactProxy(
    val xNorm: Float,
    val yNorm: Float,
    val confidence: Float,
)

private data class BodyTopProxy(
    val xNorm: Float,
    val yNorm: Float,
    val confidence: Float,
)

private data class HipAnchorProxy(
    val centerXNorm: Float,
    val centerYNorm: Float,
    val confidence: Float,
    val learnedVectorXNorm: Float,
    val learnedVectorYNorm: Float,
)

private data class HipVerticalEstimate(
    val distanceMeters: Float,
    val bodyHeightMeters: Float,
    val confidence: Float,
)

private data class DepthPatchEstimate(
    val horizontalDistanceMeters: Float,
    val hipHeightMeters: Float,
    val confidence: Float,
)

private data class TorsoSpanDiagnostics(
    val shoulderHipSpanNorm: Float,
    val torsoSpanNorm: Float,
)

private data class HipGeometrySelection(
    val distanceMeters: Float?,
    val rejectedReason: String,
)

private fun deriveArCoreFloorEstimate(
    roi: PoseRoi,
    screenX: FloatArray?,
    screenY: FloatArray?,
    visibility: FloatArray?,
    worldTracking: WorldTrackingSnapshot?,
    intrinsics: CameraIntrinsics?,
    rawSubjectHeightMeters: Float,
    hipProxy: HipAnchorProxy?,
    visualTopYNorm: Float,
    visualTopConfidence: Float,
): ArFloorEstimate? {
    worldTracking ?: return null
    intrinsics ?: return null
    if (!worldTracking.hasGroundPlane) return null
    val camera = worldTracking.cameraPosition?.takeIf { it.size >= 3 } ?: return null
    val rotation = worldTracking.cameraRotation?.takeIf { it.size >= 4 } ?: return null
    val planePoint = worldTracking.groundPoint?.takeIf { it.size >= 3 } ?: return null
    val planeNormal = worldTracking.groundNormal?.takeIf { it.size >= 3 } ?: return null
    if (intrinsics.fx <= 1f || intrinsics.fy <= 1f) return null

    val bodyHeightGuess = rawSubjectHeightMeters
        .takeIf { it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS }
        ?: worldTracking.subjectHeightMeters.takeIf { it.isFinite() && it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS }
    val torsoSpanDiagnostics = estimateTorsoSpanDiagnostics(screenX, screenY, visibility)
    val footContact = selectGroundContactProxy(roi, screenX, screenY, visibility)
    val footRayDiagnostics = computeFootRayDiagnostics(screenX, screenY, visibility, intrinsics, camera, rotation, planePoint, planeNormal)
    val footU = footContact.xNorm * intrinsics.imageWidth
    val footV = footContact.yNorm * intrinsics.imageHeight
    val footHit = rayPlaneHit(footU, footV, intrinsics, camera, rotation, planePoint, planeNormal)
    val blendedFootDistance = footHit?.let {
        val dx = it[0] - camera[0]
        val dz = it[2] - camera[2]
        sqrt(dx * dx + dz * dz).coerceIn(0.35f, 12.0f)
    } ?: Float.NaN
    val footDistance = footRayDiagnostics.midpointDistance.takeIf { it.isFinite() } ?: blendedFootDistance
    val cameraHeight = abs(dot3(
        camera[0] - planePoint[0],
        camera[1] - planePoint[1],
        camera[2] - planePoint[2],
        planeNormal[0],
        planeNormal[1],
        planeNormal[2],
    )).coerceIn(0.05f, 3.0f)
    val roiDistance = if (bodyHeightGuess != null) {
        (
            bodyHeightGuess / (2f * (intrinsics.imageHeight.toFloat() / (2f * intrinsics.fy)) * roi.height.coerceIn(0.18f, 0.94f))
        ).coerceIn(0.70f, 12.0f)
    } else {
        Float.NaN
    }
    // Hip-depth distance via the depth-map patch is intentionally diagnostic-
    // only (see comment near hipSelection below). On real captures it either
    // reports values >> the true subject distance (file 52: mean 3.19 m vs
    // 2.10 m truth, all frames out-of-range) or fails to read the depth map
    // entirely (file 53: 100% NaN). The optimizer already gates it behind
    // FLAG_REJECTED_HIP, so the depth sampling is pure overhead. Skip it.
    val hipDepthEstimate: DepthPatchEstimate? = null
    val torsoHeightPre = estimateTorsoHeightCandidate(screenX, screenY, visibility, 2.50f, intrinsics)
    val landmarkBodyTop = selectBodyTopProxy(roi, screenX, screenY, visibility)
    val visualTop = visualTopYNorm
        .takeIf {
            it.isFinite() &&
                visualTopConfidence >= 0.52f &&
                it in 0f..1f &&
                it < landmarkBodyTop.yNorm - 0.004f &&
                it >= landmarkBodyTop.yNorm - 0.060f &&
                edgeClipRisk(it) <= 0.10f
        }
    val bodyTop = if (visualTop != null) {
        landmarkBodyTop.copy(
            yNorm = visualTop,
            confidence = maxOf(landmarkBodyTop.confidence, visualTopConfidence.coerceIn(0.20f, 0.98f)),
        )
    } else {
        landmarkBodyTop
    }
    val visualTopRawLiftNorm = (landmarkBodyTop.yNorm - bodyTop.yNorm).coerceAtLeast(0f)
    val crownLiftNorm = if (visualTop != null) {
        estimateVisualTopResidualCrownLiftNorm(
            roi = roi,
            bodyTop = bodyTop,
            visualTopLiftNorm = visualTopRawLiftNorm,
            visualTopConfidence = visualTopConfidence,
            footRays = footRayDiagnostics,
        )
    } else {
        estimateCrownEndpointLiftNorm(roi, screenX, screenY, visibility, bodyTop, footRayDiagnostics)
    }
    val visualTopLiftNorm = visualTopRawLiftNorm + crownLiftNorm
    val topYForGeometry = (bodyTop.yNorm - crownLiftNorm).coerceIn(0f, 1f)
    val topRay = cameraRayWorld(
        u = bodyTop.xNorm * intrinsics.imageWidth,
        v = topYForGeometry * intrinsics.imageHeight,
        intrinsics = intrinsics,
        rotation = rotation,
    )
    val hipRay = hipProxy?.let {
        cameraRayWorld(
            u = it.centerXNorm * intrinsics.imageWidth,
            v = it.centerYNorm * intrinsics.imageHeight,
            intrinsics = intrinsics,
            rotation = rotation,
        )
    }
    val hipVerticalEstimate = if (topRay != null && hipRay != null) {
        estimateBodyFromTopAndHipRays(
            camera = camera,
            topRay = topRay,
            hipRay = hipRay,
            planePoint = planePoint,
            planeNormal = planeNormal,
            hipConfidence = hipProxy?.confidence ?: 0f,
        )
    } else {
        null
    }
    val torsoSeedDistance = if (hipRay != null && torsoHeightPre != null) {
        estimateDistanceFromHipRay(
            cameraHeight = cameraHeight,
            bodyHeightMeters = torsoHeightPre,
            hipRay = hipRay,
            planeNormal = planeNormal,
        )
    } else {
        null
    }
    val lockedHeightDistance = if (
        hipRay != null &&
        rawSubjectHeightMeters.isFinite() &&
        rawSubjectHeightMeters in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS
    ) {
        estimateDistanceFromHipRay(
            cameraHeight = cameraHeight,
            bodyHeightMeters = rawSubjectHeightMeters,
            hipRay = hipRay,
            planeNormal = planeNormal,
        )
    } else {
        null
    }
    // Hip depth is intentionally diagnostic-only. On real captures it often spikes
    // to 9-12m while the subject is actually 2-3m away, so it must not become a
    // physical ruler for distance/height or server DLT scaling.
    val hipSelection = selectHipGeometryDistance(
        torsoDistance = torsoSeedDistance,
        lockedDistance = lockedHeightDistance,
        topSolvedDistance = hipVerticalEstimate?.distanceMeters,
        footDistance = footDistance,
        torsoHeight = torsoHeightPre,
        topSolvedHeight = hipVerticalEstimate?.bodyHeightMeters,
    )
    val hipDistance = hipSelection.distanceMeters
    val footContactState = classifyFootContact(
        footDistance = footDistance,
        hipDistance = hipDistance,
        roiDistance = roiDistance,
        footConfidence = footContact.confidence,
    )
    val footSupportedByGeometry = footDistance.isFinite() &&
        (
            hipDistance?.let { candidateAgreement(footDistance, it, 0.30f, 0.12f) } == true ||
                (roiDistance.isFinite() && candidateAgreement(footDistance, roiDistance, 0.48f, 0.16f))
            )
    val footUsableForFusion =
        footContactState == "grounded" ||
            footContactState == "grounded_roi_supported" ||
            footSupportedByGeometry
    val trustedFootDistance = footDistance.takeIf { footUsableForFusion }
    val distance = fuseDistanceEstimate(
        footDistance = trustedFootDistance ?: Float.NaN,
        hipDistance = hipDistance,
        hipConfidence = hipVerticalEstimate?.confidence ?: 0f,
        roiDistance = roiDistance,
    )
    val bodyScaleDiagnostics = estimateBodyScaleDiagnostics(screenX, screenY, visibility, distance, intrinsics)
    val torsoHeightCandidate = estimateTorsoHeightCandidate(screenX, screenY, visibility, distance, intrinsics)
    val groundedFootDistance = footRayDiagnostics.nearestDistance
        .takeIf { it.isFinite() && (footContactState == "grounded" || footContactState == "grounded_roi_supported") }
        ?: footDistance.takeIf { footContactState == "grounded" || footContactState == "grounded_roi_supported" }
        ?: Float.NaN
    val correctedCameraHeight = inferCameraHeightFromFootRayScale(
        rawCameraHeight = cameraHeight,
        footPlaneDistance = footDistance,
        subjectDistance = distance,
        floorSource = worldTracking.source,
        footConfidence = footContact.confidence,
    )

    val bodyHeightNorm = (footContact.yNorm - topYForGeometry).coerceIn(0.10f, 0.98f)
    val vTan = intrinsics.imageHeight.toFloat() / (2f * intrinsics.fy)
    val pixelSpanHeight = bodyHeightNorm * 2f * vTan * distance
    val heightFromVerticalRay = if (footHit != null) {
        topRay?.let {
            estimateVerticalHeightFromTopRay(
                camera = camera,
                topRay = it,
                footPoint = footHit,
                planeNormal = planeNormal,
            )
        }
    } else {
        null
    }
    val heightFromDistanceAngle = topRay?.let {
        estimateHeightFromDistanceAndTopAngle(
            cameraHeight = correctedCameraHeight,
            distanceMeters = distance,
            topRay = it,
            planeNormal = planeNormal,
        )
    }
    val topRayFloorHeight = heightFromVerticalRay ?: heightFromDistanceAngle
    val rootHipRayFloorDistance = hipVerticalEstimate?.distanceMeters ?: hipDistance ?: Float.NaN
    val distanceHeightGeometryResidual = listOfNotNull(
        footRayDiagnostics.midpointDistance.takeIf { it.isFinite() },
        rootHipRayFloorDistance.takeIf { it.isFinite() },
        roiDistance.takeIf { it.isFinite() },
    ).let { values -> if (values.size >= 2) values.maxOrNull()!! - values.minOrNull()!! else Float.NaN }
    val floorLikelyBiasCorrected = correctedCameraHeight.isFinite() &&
        cameraHeight.isFinite() &&
        abs(correctedCameraHeight - cameraHeight) > 0.10f
    val topRayHeightCandidate = if (floorLikelyBiasCorrected) {
        heightFromDistanceAngle ?: heightFromVerticalRay
    } else {
        heightFromVerticalRay ?: heightFromDistanceAngle
    }
    val pixelHeightCandidate = if (pixelSpanHeight in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS) {
        // Pixel span underestimates true top-to-floor when feet/head landmarks sit inside body silhouette.
        // Keep this as a backup candidate, not a dominant one.
        pixelSpanHeight * 1.10f
    } else {
        Float.NaN
    }
    val heightCandidates = mutableListOf<Float>()
    val hipGeometryHeight = selectHipGeometryHeight(
        torsoHeight = torsoHeightCandidate,
        lockedHeight = rawSubjectHeightMeters,
        topSolvedHeight = hipVerticalEstimate?.bodyHeightMeters,
    )
        ?.takeIf { it in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS }
    if (hipGeometryHeight != null) {
        heightCandidates += hipGeometryHeight
    }
    val semanticHeightRefs = listOfNotNull(hipGeometryHeight, torsoHeightCandidate)
    val trustedTopRayHeight = topRayHeightCandidate
        ?.takeIf { candidate ->
            candidate in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS &&
                semanticHeightRefs.any { abs(it - candidate) <= 0.14f }
        }
    if (trustedTopRayHeight != null) {
        heightCandidates += trustedTopRayHeight
    }
    if (torsoHeightCandidate != null) {
        heightCandidates += torsoHeightCandidate
    }

    val measuredHeight = when (heightCandidates.size) {
        0 -> Float.NaN
        1 -> heightCandidates[0]
        2 -> {
            val a = heightCandidates[0]
            val b = heightCandidates[1]
            val deltaRatio = abs(a - b) / maxOf(a, b, 1e-4f)
            when {
                deltaRatio <= 0.16f -> a * 0.52f + b * 0.48f
                deltaRatio <= 0.38f -> (a + b) * 0.5f
                else -> {
                    val low = minOf(a, b)
                    val high = maxOf(a, b)
                    low * 0.35f + high * 0.65f
                }
            }
        }
        else -> {
            val sorted = heightCandidates.sorted()
            sorted[sorted.size / 2]
        }
    }
    val measuredBodyHeight = stabilizeBodyHeight(
        measuredHeight = measuredHeight,
        previousHeight = rawSubjectHeightMeters,
        roiHeight = pixelHeightCandidate,
        hipDistance = hipDistance,
        footDistance = footDistance,
    )
    if (!measuredBodyHeight.isFinite() || measuredBodyHeight !in MIN_MEASURED_HEIGHT_METERS..MAX_MEASURED_HEIGHT_METERS) {
        return null
    }
    val bodyHeight = measuredBodyHeight.coerceIn(MIN_BODY_HEIGHT_METERS, MAX_BODY_HEIGHT_METERS)
    val lateralAnchorX = hipProxy?.centerXNorm ?: footContact.xNorm
    val lateral = (((lateralAnchorX - 0.5f) * intrinsics.imageWidth) / intrinsics.fx * distance)
        .coerceIn(-3.0f, 3.0f)

    val footSupportConfidence = if (footHit != null) footContact.confidence else 0.08f
    val bodyClipRisk = estimateBodyClipRisk(roi, bodyTop, footContact)
    val topEndpointConfidence = (bodyTop.confidence * (1f - edgeClipRisk(bodyTop.yNorm))).coerceIn(0f, 1f)
    val footEndpointConfidence = (footContact.confidence * (1f - edgeClipRisk(1f - footContact.yNorm))).coerceIn(0f, 1f)
    val maskEndpointConfidence = (minOf(topEndpointConfidence, footEndpointConfidence) * (1f - bodyClipRisk)).coerceIn(0f, 1f)

    return ArFloorEstimate(
        distanceMeters = distance,
        bodyHeightMeters = bodyHeight,
        cameraHeightMeters = correctedCameraHeight,
        floorPitchDegrees = worldTracking.floorPitchDegrees.takeIf { it.isFinite() } ?: 0f,
        lateralOffsetMeters = lateral,
        confidence = (
            worldTracking.confidence * 0.46f +
                (hipProxy?.confidence ?: 0f) * 0.22f +
                footSupportConfidence * 0.20f
        ).coerceIn(0f, 1f),
        learnedHipVectorXNorm = hipProxy?.learnedVectorXNorm ?: Float.NaN,
        learnedHipVectorYNorm = hipProxy?.learnedVectorYNorm ?: Float.NaN,
        floorSource = worldTracking.source,
        rawHipDepthDistanceMeters = hipDepthEstimate?.horizontalDistanceMeters ?: Float.NaN,
        footPlaneDistanceMeters = footDistance,
        roiDistanceMeters = roiDistance,
        topRayHeightMeters = topRayHeightCandidate ?: Float.NaN,
        pixelSpanHeightMeters = pixelHeightCandidate,
        hipGeometryDistanceMeters = hipDistance ?: Float.NaN,
        hipGeometryHeightMeters = hipGeometryHeight ?: Float.NaN,
        torsoHeightMeters = torsoHeightCandidate ?: Float.NaN,
        torsoResidualMeters = if (torsoHeightCandidate != null && bodyHeight.isFinite()) abs(torsoHeightCandidate - bodyHeight) else Float.NaN,
        groundedFootDistanceMeters = groundedFootDistance,
        footContactState = footContactState,
        leftFootRayFloorDistanceMeters = footRayDiagnostics.leftDistance,
        rightFootRayFloorDistanceMeters = footRayDiagnostics.rightDistance,
        feetMidpointFloorDistanceMeters = footRayDiagnostics.midpointDistance,
        nearestFootFloorDistanceMeters = footRayDiagnostics.nearestDistance,
        footRayFloorSpreadMeters = footRayDiagnostics.spread,
        topRayFloorHeightMeters = topRayFloorHeight ?: Float.NaN,
        distanceHeightGeometryResidualMeters = distanceHeightGeometryResidual,
        rootHipRayFloorDistanceMeters = rootHipRayFloorDistance,
        boneLengthSpreadMeters = bodyScaleDiagnostics.spreadMeters,
        bodyScaleConfidence = bodyScaleDiagnostics.confidence,
        hipCenterXNorm = hipProxy?.centerXNorm ?: Float.NaN,
        hipCenterYNorm = hipProxy?.centerYNorm ?: Float.NaN,
        shoulderHipSpanNorm = torsoSpanDiagnostics?.shoulderHipSpanNorm ?: Float.NaN,
        torsoSpanNorm = torsoSpanDiagnostics?.torsoSpanNorm ?: Float.NaN,
        bodyRoiHeightNorm = roi.height,
        rejectedHipReason = hipSelection.rejectedReason,
        heightSeedTrusted = rawSubjectHeightMeters.isFinite(),
        bodyClipRisk = bodyClipRisk,
        topEndpointConfidence = topEndpointConfidence,
        footEndpointConfidence = footEndpointConfidence,
        maskEndpointConfidence = maskEndpointConfidence,
        visualTopScanYNorm = visualTopYNorm,
        visualTopScanConfidence = visualTopConfidence,
        visualTopLiftNorm = visualTopLiftNorm,
        rawKeypointGeometry = buildRawKeypointGeometryMap(screenX, screenY, visibility),
    )
