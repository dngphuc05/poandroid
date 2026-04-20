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
