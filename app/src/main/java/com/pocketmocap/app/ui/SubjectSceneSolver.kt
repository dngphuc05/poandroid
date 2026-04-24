package com.pocketmocap.app.ui

import com.pocketmocap.app.tracking.SceneMetricSnapshot
import kotlin.math.abs

internal enum class SceneMeasurementKind {
    Distance,
    Height,
    CameraHeight,
}

internal data class SceneMeasurement(
    val kind: SceneMeasurementKind,
    val valueMeters: Float,
    val sigmaMeters: Float,
    val confidence: Float,
    val source: String,
    val valid: Boolean,
    val timestampUs: Long = 0L,
    val debugReason: String = "",
)

internal object SceneMeasurementExtractor {
    fun extract(
        raw: SceneMetricSnapshot,
        relativeScaleDistance: Float,
        timestampUs: Long = 0L,
    ): List<SceneMeasurement> {
        val clipRisk = raw.bodyClipRisk.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val clipTrust = (1f - clipRisk).coerceIn(0f, 1f)
        val topEndpointTrust = raw.topEndpointConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: raw.confidence
        val footEndpointTrust = raw.footEndpointConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: raw.confidence
        val maskEndpointTrust = raw.maskEndpointConfidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: clipTrust
        return buildList {
            addDistance("raw_distance", raw.distanceMeters, 0.40f, raw.distanceConfidence, timestampUs)
            addDistance("hip_geometry_distance", raw.hipGeometryDistanceMeters, 0.18f, raw.bodyScaleConfidence, timestampUs)
            addDistance("foot_plane_distance", raw.footPlaneDistanceMeters, 0.55f, raw.confidence * footEndpointTrust, timestampUs)
            addDistance("grounded_foot_distance", raw.groundedFootDistanceMeters, 0.16f, raw.confidence * footEndpointTrust, timestampUs)
            addDistance("roi_distance", raw.roiDistanceMeters, 0.45f, raw.confidence * 0.75f * clipTrust, timestampUs)
            addDistance("relative_scale_distance", relativeScaleDistance, 0.28f, raw.distanceConfidence, timestampUs)
            addHeight("raw_height", raw.bodyHeightMeters, 0.22f, raw.heightConfidence, timestampUs)
            addHeight("top_ray_height", raw.topRayHeightMeters, 0.10f, raw.confidence * topEndpointTrust, timestampUs)
            addHeight("pixel_span_height", raw.pixelSpanHeightMeters, 0.18f, raw.confidence * 0.70f * maskEndpointTrust, timestampUs)
            addHeight("hip_geometry_height", raw.hipGeometryHeightMeters, 0.09f, raw.bodyScaleConfidence, timestampUs)
            addHeight("torso_height", raw.torsoHeightMeters, 0.12f, raw.bodyScaleConfidence, timestampUs)
            addCameraHeight("camera_height", raw.cameraHeightMeters, 0.08f, raw.floorConfidence, timestampUs)
        }
    }

    fun optimizerInput(
        raw: SceneMetricSnapshot,
        relativeScaleDistance: Float,
        previousDistanceMeters: Float,
        previousHeightMeters: Float,
        floorHeightBiasMeters: Float,
        depthScale: Float,
        depthOffsetMeters: Float,
        heightEndpointBiasMeters: Float,
    ): PhysicalSceneOptimizerInput =
        PhysicalSceneOptimizerInput(
            confidence = raw.confidence,
            rawDistanceMeters = raw.distanceMeters,
            rawHeightMeters = raw.bodyHeightMeters,
            rawCameraHeightMeters = raw.cameraHeightMeters,
            rawHipDepthDistanceMeters = Float.NaN,
            footPlaneDistanceMeters = raw.footPlaneDistanceMeters,
            roiDistanceMeters = raw.roiDistanceMeters,
            topRayHeightMeters = raw.topRayHeightMeters,
            pixelSpanHeightMeters = raw.pixelSpanHeightMeters,
            hipGeometryDistanceMeters = raw.hipGeometryDistanceMeters,
            hipGeometryHeightMeters = raw.hipGeometryHeightMeters,
            torsoHeightMeters = raw.torsoHeightMeters,
            groundedFootDistanceMeters = raw.groundedFootDistanceMeters,
            bodyScaleConfidence = raw.bodyScaleConfidence,
            boneLengthSpreadMeters = raw.boneLengthSpreadMeters,
            relativeScaleDistanceMeters = relativeScaleDistance,
            previousDistanceMeters = previousDistanceMeters,
            previousHeightMeters = previousHeightMeters,
            floorHeightBiasMeters = floorHeightBiasMeters,
            depthScale = depthScale,
            depthOffsetMeters = depthOffsetMeters,
            heightEndpointBiasMeters = heightEndpointBiasMeters,
        )

    private fun MutableList<SceneMeasurement>.addDistance(
        source: String,
        valueMeters: Float,
        sigmaMeters: Float,
        confidence: Float,
        timestampUs: Long,
    ) {
        addMeasurement(SceneMeasurementKind.Distance, source, valueMeters, sigmaMeters, confidence, 0.35f, 12.0f, timestampUs)
    }

    private fun MutableList<SceneMeasurement>.addHeight(
        source: String,
        valueMeters: Float,
        sigmaMeters: Float,
        confidence: Float,
        timestampUs: Long,
    ) {
        addMeasurement(SceneMeasurementKind.Height, source, valueMeters, sigmaMeters, confidence, 1.05f, 2.35f, timestampUs)
    }

    private fun MutableList<SceneMeasurement>.addCameraHeight(
        source: String,
        valueMeters: Float,
        sigmaMeters: Float,
        confidence: Float,
        timestampUs: Long,
    ) {
        addMeasurement(SceneMeasurementKind.CameraHeight, source, valueMeters, sigmaMeters, confidence, 0.45f, 2.20f, timestampUs)
    }

    private fun MutableList<SceneMeasurement>.addMeasurement(
        kind: SceneMeasurementKind,
        source: String,
        valueMeters: Float,
        sigmaMeters: Float,
        confidence: Float,
        minValue: Float,
        maxValue: Float,
        timestampUs: Long,
    ) {
        val valid = valueMeters.isFinite() && valueMeters in minValue..maxValue
        add(
            SceneMeasurement(
                kind = kind,
                valueMeters = valueMeters,
                sigmaMeters = sigmaMeters,
                confidence = confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                source = source,
                valid = valid,
                timestampUs = timestampUs,
                debugReason = if (valid) "accepted_for_shadow" else "invalid_or_out_of_range",
            )
        )
    }
}

internal class SubjectSceneSolver(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {
    private val measurementWindow = ArrayDeque<List<SceneMeasurement>>()

    fun reset() {
        measurementWindow.clear()
    }

    fun solveShadow(
        baseline: SceneMetricSnapshot,
        measurements: List<SceneMeasurement>,
    ): SceneMetricSnapshot {
        remember(measurements)
        val heightPosterior = solvePosterior(
            kind = SceneMeasurementKind.Height,
            fallback = baseline.correctedHeightMeters
                .takeIf { it.isFinite() }
                ?: baseline.bodyHeightMeters.takeIf { it.isFinite() }
                ?: Float.NaN,
            defaultSigma = 0.25f,
            minSigma = 0.015f,
            maxSigma = 0.80f,
        )
        val distancePosterior = solvePosterior(
            kind = SceneMeasurementKind.Distance,
            fallback = baseline.correctedDistanceMeters
                .takeIf { it.isFinite() }
                ?: baseline.distanceMeters.takeIf { it.isFinite() }
                ?: Float.NaN,
            defaultSigma = 0.50f,
            minSigma = 0.02f,
            maxSigma = 2.50f,
        )
        val experimentalHeight = heightPosterior.valueMeters
        val experimentalDistance = distancePosterior.valueMeters
        val heightSigma = heightPosterior.sigmaMeters
        val distanceSigma = distancePosterior.sigmaMeters
        val heightDelta = finiteDelta(baseline.correctedHeightMeters, experimentalHeight)
            ?: finiteDelta(baseline.bodyHeightMeters, experimentalHeight)
            ?: Float.NaN
        val distanceDelta = finiteDelta(baseline.correctedDistanceMeters, experimentalDistance)
            ?: finiteDelta(baseline.distanceMeters, experimentalDistance)
            ?: Float.NaN
        val validCount = measurements.count { it.valid }
        val promoteHeight = shouldPromoteHeight(baseline, heightPosterior, measurements)
        val promoteDistance = shouldPromoteDistance(baseline, distancePosterior, measurements)
        val promotedSource = when {
            promoteHeight && promoteDistance -> "experimental_height_distance"
            promoteHeight -> "experimental_height"
            promoteDistance -> "experimental_distance"
            else -> "baseline"
        }
        val factorSummary = measurements
            .filter { it.valid }
            .groupingBy { it.source }
            .eachCount()
            .entries
            .joinToString("|") { "${it.key}:${it.value}" }
            .ifBlank { "no_valid_measurements" }

        return baseline.copy(
            confidence = if (promoteHeight || promoteDistance) {
                maxOf(baseline.confidence, minOf(heightPosterior.confidence, distancePosterior.confidence) * 0.92f)
            } else {
                baseline.confidence
            }.coerceIn(0f, 1f),
            distanceMeters = if (promoteDistance) experimentalDistance else baseline.distanceMeters,
            bodyHeightMeters = if (promoteHeight) experimentalHeight else baseline.bodyHeightMeters,
            correctedDistanceMeters = if (promoteDistance) experimentalDistance else baseline.correctedDistanceMeters,
            correctedHeightMeters = if (promoteHeight) experimentalHeight else baseline.correctedHeightMeters,
            heightLockState = if (promoteHeight && baseline.heightLockState != "locked") {
                "locked"
            } else {
                baseline.heightLockState
            },
            solverConfidence = if (promoteHeight || promoteDistance) {
                maxOf(finiteOrZero(baseline.solverConfidence), minOf(heightPosterior.confidence, distancePosterior.confidence) * 0.90f)
            } else {
                baseline.solverConfidence
            }.coerceIn(0f, 1f),
            distanceConfidence = if (promoteDistance) {
                maxOf(finiteOrZero(baseline.distanceConfidence), distancePosterior.confidence)
            } else {
                baseline.distanceConfidence
            }.coerceIn(0f, 1f),
            heightConfidence = if (promoteHeight) {
                maxOf(finiteOrZero(baseline.heightConfidence), heightPosterior.confidence)
            } else {
                baseline.heightConfidence
            }.coerceIn(0f, 1f),
            experimentalHeightMeters = experimentalHeight,
            experimentalHeightSigmaMeters = heightSigma,
            experimentalHeightConfidence = heightPosterior.confidence,
            experimentalHeightState = heightPosterior.state.ifBlank { baseline.heightLockState.ifBlank { "unknown" } },
            experimentalDistanceMeters = experimentalDistance,
            experimentalDistanceSigmaMeters = distanceSigma,
            experimentalDistanceConfidence = distancePosterior.confidence,
            experimentalSolverCost = (heightSigma + distanceSigma) * maxOf(1, validCount).toFloat(),
            experimentalSolverStatus = "shadow_sliding_window",
            experimentalFactorSummary = "measurements=$validCount|promote=$promotedSource|height=${heightPosterior.summary}|distance=${distancePosterior.summary}|$factorSummary",
            baselineExperimentalHeightDeltaMeters = heightDelta,
            baselineExperimentalDistanceDeltaMeters = distanceDelta,
            promotedSolverSource = promotedSource,
        )
    }

    private fun shouldPromoteHeight(
        baseline: SceneMetricSnapshot,
        posterior: PosteriorEstimate,
        currentMeasurements: List<SceneMeasurement>,
    ): Boolean {
        if (posterior.state != "shadow_window_ready") return false
        if ("rejected_height_semantic_agreement" in baseline.activeFactors) return false
        if (!posterior.valueMeters.isFinite() || posterior.valueMeters !in 1.05f..2.35f) return false
        if (posterior.confidence < 0.58f || posterior.sigmaMeters > 0.070f || posterior.support < 10) return false
        val baselineTrusted = baseline.heightLockState == "locked" ||
            (baseline.heightLockState.startsWith("holding") && "untrusted" !in baseline.heightLockState)
        val baselineDelta = finiteDelta(baseline.correctedHeightMeters, posterior.valueMeters)
            ?: finiteDelta(baseline.bodyHeightMeters, posterior.valueMeters)
            ?: 0f
        if (baselineTrusted && abs(baselineDelta) > 0.11f) return false
        val strongConflict = currentMeasurements
            .filter { it.kind == SceneMeasurementKind.Height && it.valid && it.confidence >= 0.55f }
            .any { abs(it.valueMeters - posterior.valueMeters) > 0.20f }
        if (strongConflict) return false
        val upperWitnessMedian = median(
