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
            currentMeasurements
                .filter {
                    it.kind == SceneMeasurementKind.Height &&
                        it.valid &&
                        it.confidence >= 0.35f &&
                        it.source in setOf("top_ray_height", "pixel_span_height", "torso_height")
                }
                .map { it.valueMeters }
        )
        if (upperWitnessMedian != null && upperWitnessMedian > posterior.valueMeters + 0.085f) return false
        return baselineDelta.isFinite() && (abs(baselineDelta) >= 0.025f || !baselineTrusted)
    }

    private fun shouldPromoteDistance(
        baseline: SceneMetricSnapshot,
        posterior: PosteriorEstimate,
        currentMeasurements: List<SceneMeasurement>,
    ): Boolean {
        if (posterior.state != "shadow_window_ready") return false
        if (!posterior.valueMeters.isFinite() || posterior.valueMeters !in 0.35f..12.0f) return false
        if (posterior.confidence < 0.56f || posterior.sigmaMeters > 0.30f || posterior.support < 10) return false
        val baselineDelta = finiteDelta(baseline.correctedDistanceMeters, posterior.valueMeters)
            ?: finiteDelta(baseline.distanceMeters, posterior.valueMeters)
            ?: 0f
        val strongConflict = currentMeasurements
            .filter { it.kind == SceneMeasurementKind.Distance && it.valid && it.confidence >= 0.55f }
            .any { abs(it.valueMeters - posterior.valueMeters) > 0.85f }
        if (strongConflict) return false
        return baselineDelta.isFinite() && abs(baselineDelta) >= 0.08f
    }

    private fun remember(measurements: List<SceneMeasurement>) {
        val valid = measurements.filter { it.valid && it.valueMeters.isFinite() }
        if (valid.isEmpty()) return
        measurementWindow.addLast(valid)
        while (measurementWindow.size > windowSize) {
            measurementWindow.removeFirst()
        }
    }

    private fun solvePosterior(
        kind: SceneMeasurementKind,
        fallback: Float,
        defaultSigma: Float,
        minSigma: Float,
        maxSigma: Float,
    ): PosteriorEstimate {
        val samples = measurementWindow
            .flatten()
            .filter { it.kind == kind && it.valid && it.confidence > 0f && it.valueMeters.isFinite() }
        if (samples.size < MIN_WINDOW_SAMPLES) {
            return PosteriorEstimate(
                valueMeters = fallback,
                sigmaMeters = defaultSigma.coerceIn(minSigma, maxSigma),
                confidence = 0.20f,
                support = samples.size,
                state = "shadow_warming",
                summary = "warming:n=${samples.size}",
            )
        }
        val sourceBias = estimateSourceBias(samples, kind)
        val corrected = samples.map { sample ->
            WeightedSample(
                source = sample.source,
                valueMeters = sample.valueMeters - (sourceBias[sample.source] ?: 0f),
                sigmaMeters = sample.sigmaMeters.coerceIn(minSigma, maxSigma),
                confidence = sample.confidence.coerceIn(0f, 1f),
                prior = sourcePrior(sample.source, kind),
            )
        }.filter { it.prior > 0f && it.confidence > 0f }
        if (corrected.isEmpty()) {
            return PosteriorEstimate(
                valueMeters = fallback,
                sigmaMeters = defaultSigma.coerceIn(minSigma, maxSigma),
                confidence = 0.15f,
                support = 0,
                state = "shadow_no_valid_factors",
                summary = "empty_after_quality",
            )
        }
        val seed = weightedMedian(corrected) ?: fallback
        val posterior = robustMean(corrected, seed, kind)
        val residuals = corrected.map { abs(it.valueMeters - posterior) }
        val robustSpread = percentile(residuals, 0.70f) ?: defaultSigma
        val sigma = maxOf(minSigma, minOf(maxSigma, maxOf(robustSpread, informationSigma(corrected))))
        val support = corrected.count { abs(it.valueMeters - posterior) <= supportGate(kind) }
        val confidence = (
            support.toFloat() / corrected.size.toFloat() *
                (1f - (sigma / maxSigma)).coerceIn(0f, 1f)
            ).coerceIn(0.05f, 0.95f)
        val topBias = sourceBias["top_ray_height"]
        val hipBias = sourceBias["hip_geometry_height"]
        return PosteriorEstimate(
            valueMeters = posterior,
            sigmaMeters = sigma,
            confidence = confidence,
            support = support,
            state = if (measurementWindow.size >= windowSize / 2) "shadow_window_ready" else "shadow_warming",
            summary = "n=${corrected.size},support=$support,top_bias=${topBias.format3()},hip_bias=${hipBias.format3()}",
        )
    }

    private fun estimateSourceBias(
        samples: List<SceneMeasurement>,
        kind: SceneMeasurementKind,
    ): Map<String, Float> {
        val anchorSources = when (kind) {
            SceneMeasurementKind.Height -> listOf("top_ray_height", "raw_height")
            SceneMeasurementKind.Distance -> listOf("grounded_foot_distance", "foot_plane_distance", "relative_scale_distance")
            SceneMeasurementKind.CameraHeight -> listOf("camera_height")
        }
        val anchor = anchorSources
            .asSequence()
            .mapNotNull { source -> median(samples.filter { it.source == source }.map { it.valueMeters }) }
            .firstOrNull()
            ?: median(samples.map { it.valueMeters })
            ?: return emptyMap()
        return samples
            .groupBy { it.source }
            .mapValues { (_, sourceSamples) ->
                val sourceMedian = median(sourceSamples.map { it.valueMeters }) ?: anchor
                val rawBias = sourceMedian - anchor
                val gate = if (kind == SceneMeasurementKind.Height) 0.16f else 0.85f
                if (abs(rawBias) >= learnBiasGate(kind)) rawBias.coerceIn(-gate, gate) else 0f
            }
    }

    private fun robustMean(
        samples: List<WeightedSample>,
        seed: Float,
        kind: SceneMeasurementKind,
    ): Float {
        var center = seed
        repeat(3) {
            var weighted = 0f
            var total = 0f
            for (sample in samples) {
                val residual = abs(sample.valueMeters - center)
                val huber = minOf(1f, huberGate(kind) / maxOf(residual, 1e-4f))
                val weight = sample.prior * sample.confidence * huber / (sample.sigmaMeters * sample.sigmaMeters)
                if (weight.isFinite() && weight > 0f) {
                    weighted += sample.valueMeters * weight
                    total += weight
                }
            }
            if (total > 0f) center = weighted / total
        }
        return center
    }

    private fun weightedMedian(samples: List<WeightedSample>): Float? {
        val sorted = samples
            .map { it.valueMeters to (it.prior * it.confidence / (it.sigmaMeters * it.sigmaMeters)) }
            .filter { it.first.isFinite() && it.second.isFinite() && it.second > 0f }
            .sortedBy { it.first }
        if (sorted.isEmpty()) return null
        val total = sorted.sumOf { it.second.toDouble() }.toFloat()
        var acc = 0f
        for ((value, weight) in sorted) {
            acc += weight
            if (acc >= total * 0.5f) return value
        }
        return sorted.last().first
    }

    private fun informationSigma(samples: List<WeightedSample>): Float {
        val info = samples.sumOf {
            (it.prior * it.confidence / (it.sigmaMeters * it.sigmaMeters)).toDouble()
        }.toFloat()
        return if (info > 1e-4f) kotlin.math.sqrt(1f / info) else 1f
    }

    private fun robustWindowSpread(values: List<Float>): Float? {
        if (values.size < 3) return null
        val sorted = values.sorted()
        val p10 = sorted[(sorted.lastIndex * 0.10f).toInt().coerceIn(0, sorted.lastIndex)]
        val p90 = sorted[(sorted.lastIndex * 0.90f).toInt().coerceIn(0, sorted.lastIndex)]
        return abs(p90 - p10)
    }

    private fun median(values: List<Float>): Float? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        return sorted[sorted.size / 2]
    }

    private fun percentile(values: List<Float>, p: Float): Float? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        return sorted[(sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)]
    }

    private fun sourcePrior(source: String, kind: SceneMeasurementKind): Float =
        when (kind) {
            SceneMeasurementKind.Height -> when (source) {
                "top_ray_height" -> 1.45f
                "hip_geometry_height" -> 0.95f
                "raw_height" -> 0.70f
                "pixel_span_height" -> 0.42f
                "torso_height" -> 0.30f
                else -> 0.20f
            }
            SceneMeasurementKind.Distance -> when (source) {
                "grounded_foot_distance" -> 1.60f
                "foot_plane_distance" -> 1.15f
                "relative_scale_distance" -> 0.85f
                "roi_distance" -> 0.45f
                "hip_geometry_distance" -> 0.42f
                "raw_distance" -> 0.30f
                else -> 0.20f
            }
            SceneMeasurementKind.CameraHeight -> 1.0f
        }

    private fun huberGate(kind: SceneMeasurementKind): Float =
        when (kind) {
            SceneMeasurementKind.Height -> 0.075f
            SceneMeasurementKind.Distance -> 0.32f
            SceneMeasurementKind.CameraHeight -> 0.08f
        }

    private fun supportGate(kind: SceneMeasurementKind): Float =
        when (kind) {
            SceneMeasurementKind.Height -> 0.090f
            SceneMeasurementKind.Distance -> 0.42f
            SceneMeasurementKind.CameraHeight -> 0.10f
        }

    private fun learnBiasGate(kind: SceneMeasurementKind): Float =
        when (kind) {
            SceneMeasurementKind.Height -> 0.024f
            SceneMeasurementKind.Distance -> 0.18f
            SceneMeasurementKind.CameraHeight -> 0.05f
        }

    private fun finiteDelta(a: Float, b: Float): Float? =
        if (a.isFinite() && b.isFinite()) b - a else null

    private fun finiteOrZero(value: Float): Float =
        if (value.isFinite()) value else 0f

    private fun Float?.format3(): String =
        if (this != null && isFinite()) "%.3f".format(this) else "nan"

    private data class WeightedSample(
        val source: String,
        val valueMeters: Float,
        val sigmaMeters: Float,
        val confidence: Float,
        val prior: Float,
    )

    private data class PosteriorEstimate(
        val valueMeters: Float,
        val sigmaMeters: Float,
        val confidence: Float,
        val support: Int,
        val state: String,
        val summary: String,
    )

    private companion object {
        private const val DEFAULT_WINDOW_SIZE = 60
        private const val MIN_WINDOW_SAMPLES = 12
    }
}
