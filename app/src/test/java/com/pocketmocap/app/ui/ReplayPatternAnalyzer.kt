package com.pocketmocap.app.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ReplayPatternSpec(
    val id: String,
    val csvFile: String,
    val expectedDistancePlateausMeters: List<Float>,
    val expectedHeightRangeMeters: ClosedFloatingPointRange<Float>,
    val trimFraction: Float,
    val minSegmentFrames: Int,
)

internal data class PlateauScore(
    val column: String,
    val medians: List<Float>,
    val deltas: List<Float>,
    val medianError: Float,
    val deltaError: Float,
    val jitter: Float,
    val missingRatio: Float,
    val monotonic: Boolean,
    val score: Float,
)

internal data class ReplayPatternAnalysis(
    val specId: String,
    val rowCount: Int,
    val trimmedRowCount: Int,
    val bestDistanceScore: PlateauScore?,
    val distanceScores: List<PlateauScore>,
    val heightMedian: Float,
    val heightP10: Float,
    val heightP90: Float,
    val diagnoses: Set<String>,
)

internal object ReplayPatternAnalyzer {
    private val distanceColumns = listOf(
        "distance_m",
        "corrected_distance_m",
        "ar_target_distance_m",
        "hip_geometry_distance_m",
        "foot_plane_distance_m",
        "grounded_foot_distance_m",
        "raw_hip_depth_distance_m",
        "roi_distance_m",
        "relative_scale_distance_m",
    )

    fun loadSpec(resourceFixtureJson: String): Pair<ReplayPatternSpec, String> {
        val metadataText = readResourceText("replay/$resourceFixtureJson")
        val spec = parseSpec(metadataText)
        val csvText = readResourceText("replay/${spec.csvFile}")
        return spec to csvText
    }

    fun analyze(csvText: String, spec: ReplayPatternSpec): ReplayPatternAnalysis {
        val rows = parseCsvRows(csvText).filter { it["scene_source"] == "arcore_floor" }
        val start = (rows.size * spec.trimFraction).toInt().coerceIn(0, rows.size)
        val end = (rows.size * (1f - spec.trimFraction)).toInt().coerceIn(start, rows.size)
        val trimmed = rows.subList(start, end)

        val scores = distanceColumns.mapNotNull { column ->
            scoreColumn(trimmed, column, spec.expectedDistancePlateausMeters, spec.minSegmentFrames)
        }.sortedBy { it.score }
        val heightValues = trimmed.mapNotNull { it["height_m"]?.toFiniteFloatOrNull() }
        val heightMedian = median(heightValues)
        val heightP10 = percentile(heightValues, 0.10f)
        val heightP90 = percentile(heightValues, 0.90f)
        val diagnoses = classify(trimmed, scores, heightMedian, heightP10, heightP90, spec)
        return ReplayPatternAnalysis(
            specId = spec.id,
            rowCount = rows.size,
            trimmedRowCount = trimmed.size,
            bestDistanceScore = scores.firstOrNull(),
            distanceScores = scores,
            heightMedian = heightMedian,
            heightP10 = heightP10,
            heightP90 = heightP90,
            diagnoses = diagnoses,
        )
    }

    private fun scoreColumn(
        rows: List<Map<String, String>>,
        column: String,
        expected: List<Float>,
        minSegmentFrames: Int,
    ): PlateauScore? {
        if (rows.size < minSegmentFrames * 3 || expected.size != 3) return null
        val values = rows.map { it[column]?.toFiniteFloatOrNull() }
        val missingRatio = values.count { it == null }.toFloat() / values.size.toFloat()
        if (missingRatio > 0.82f) return null

        val step = max(12, rows.size / 42)
        var best: PlateauScore? = null
        var c1 = minSegmentFrames
        while (c1 <= rows.size - minSegmentFrames * 2) {
            var c2 = c1 + minSegmentFrames
            while (c2 <= rows.size - minSegmentFrames) {
                val segments = listOf(
                    values.subList(0, c1),
                    values.subList(c1, c2),
                    values.subList(c2, values.size),
                )
                val medians = segments.map { median(it.filterNotNull()) }
                if (medians.all { it.isFinite() }) {
                    val deltas = listOf(medians[1] - medians[0], medians[2] - medians[1])
                    val medianError = medians.zip(expected).sumOf { (actual, target) -> abs(actual - target).toDouble() }.toFloat()
                    val deltaError = deltas.sumOf { delta -> abs(delta + 0.70f).toDouble() }.toFloat()
                    val jitter = segments.sumOf { segment ->
                        medianAbsoluteDeviation(segment.filterNotNull()).toDouble()
                    }.toFloat()
                    val monotonic = deltas.all { it < -0.15f }
