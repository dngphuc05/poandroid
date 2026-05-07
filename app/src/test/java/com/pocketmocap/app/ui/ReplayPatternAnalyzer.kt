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
                    val monotonicPenalty = if (monotonic) 0f else 2.0f
                    val score = medianError + deltaError + jitter + missingRatio * 3f + monotonicPenalty
                    val candidate = PlateauScore(
                        column = column,
                        medians = medians,
                        deltas = deltas,
                        medianError = medianError,
                        deltaError = deltaError,
                        jitter = jitter,
                        missingRatio = missingRatio,
                        monotonic = monotonic,
                        score = score,
                    )
                    val currentBest = best
                    if (currentBest == null || candidate.score < currentBest.score) best = candidate
                }
                c2 += step
            }
            c1 += step
        }
        return best
    }

    private fun classify(
        rows: List<Map<String, String>>,
        scores: List<PlateauScore>,
        heightMedian: Float,
        heightP10: Float,
        heightP90: Float,
        spec: ReplayPatternSpec,
    ): Set<String> {
        val diagnoses = mutableSetOf<String>()
        val distanceScore = scores.firstOrNull { it.column == "distance_m" }
        val hipScore = scores.firstOrNull { it.column == "hip_geometry_distance_m" }
        val footScore = scores.firstOrNull { it.column == "foot_plane_distance_m" }
        val best = scores.firstOrNull()
        val heightValues = rows.mapNotNull { it["height_m"]?.toFiniteFloatOrNull() }
        val serverPoseOkCount = rows.count { it["server_pose_status"] == "ok" }
        val serverDltCount = rows.count { it["technical_pose_source"] == "server_dlt" }
        val serverPosePresent = rows.isNotEmpty() &&
            serverPoseOkCount >= rows.size * 0.85f &&
            serverDltCount >= rows.size * 0.85f
        val heightMissingRatio = if (rows.isEmpty()) 0f else 1f - (heightValues.size.toFloat() / rows.size.toFloat())
        val nearTruthHeightCount = heightValues.count { it in spec.expectedHeightRangeMeters }
        val expectedDistanceMin = spec.expectedDistancePlateausMeters.minOrNull() ?: Float.NaN
        val expectedDistanceMax = spec.expectedDistancePlateausMeters.maxOrNull() ?: Float.NaN
        val distanceValues = rows.mapNotNull { it["distance_m"]?.toFiniteFloatOrNull() }
        val distanceOutsideRatio = if (
            distanceValues.isNotEmpty() &&
            expectedDistanceMin.isFinite() &&
            expectedDistanceMax.isFinite()
        ) {
            distanceValues.count { it < expectedDistanceMin || it > expectedDistanceMax }
                .toFloat() / distanceValues.size.toFloat()
        } else {
            0f
        }

        val earlyDistanceMedian = median(
            rows.take((rows.size / 4).coerceAtLeast(1))
                .mapNotNull { it["distance_m"]?.toFiniteFloatOrNull() }
        )
        val earlyExpectedDistance = spec.expectedDistancePlateausMeters.firstOrNull() ?: Float.NaN
        if (
            distanceScore != null &&
            (
                distanceScore.medians.firstOrNull()?.let { it > 3.8f } == true ||
                    (earlyDistanceMedian.isFinite() && earlyExpectedDistance.isFinite() && earlyDistanceMedian > earlyExpectedDistance + 0.85f)
                )
        ) {
            diagnoses += "stale_distance_hold"
        }
        val hipValues = rows.mapNotNull { it["hip_geometry_distance_m"]?.toFiniteFloatOrNull() }
        if ((hipValues.maxOrNull() ?: 0f) > 6.0f || hipScore?.medians?.any { it > 6.0f } == true) {
            diagnoses += "hip_geometry_explosion"
        }
        val untrustedHeightFrames = rows.count { it["height_lock_state"].orEmpty().contains("untrusted") }
        if (heightMedian !in spec.expectedHeightRangeMeters && untrustedHeightFrames > rows.size / 4) {
            diagnoses += "untrusted_height_feedback"
        }
        if (heightMedian !in spec.expectedHeightRangeMeters || heightP10 < spec.expectedHeightRangeMeters.start || heightP90 > spec.expectedHeightRangeMeters.endInclusive) {
            diagnoses += "wrong_height_lock"
        }
        if (serverPosePresent && heightMissingRatio > 0.35f) {
            diagnoses += "height_missing_with_server_pose"
        }
        if (serverPosePresent && heightValues.isNotEmpty() && heightMedian !in spec.expectedHeightRangeMeters) {
            diagnoses += "height_wrong_with_server_pose"
        }
        if (heightValues.isEmpty() || nearTruthHeightCount < max(3, (heightValues.size * 0.05f).toInt())) {
            diagnoses += "missing_truth_height_candidate"
        }
        if (distanceOutsideRatio > 0.18f) {
            diagnoses += "distance_outside_expected_band"
        }
        if (footScore != null && (!footScore.monotonic || footScore.medianError > 1.2f)) {
            diagnoses += "foot_plane_not_motion_correlated"
        }
        if (best == null || best.score > 3.0f || best.medianError > 1.15f) {
            diagnoses += "missing_truth_candidate"
        }
        return diagnoses
    }

    private fun parseSpec(text: String): ReplayPatternSpec =
        ReplayPatternSpec(
            id = jsonString(text, "id") ?: error("pattern spec missing id"),
            csvFile = jsonString(text, "csv_file") ?: error("pattern spec missing csv_file"),
            expectedDistancePlateausMeters = jsonFloatArray(text, "expected_distance_plateaus_m")
                .takeIf { it.size == 3 } ?: error("pattern spec requires 3 expected distance plateaus"),
            expectedHeightRangeMeters = jsonFloatRange(text, "expected_height_range_m")
                ?: error("pattern spec missing expected_height_range_m"),
            trimFraction = jsonFloat(text, "trim_fraction") ?: 0.08f,
            minSegmentFrames = jsonInt(text, "min_segment_frames") ?: 40,
        )

    private fun parseCsvRows(csvText: String): List<Map<String, String>> {
        val lines = csvText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size >= 2) { "csv requires header + rows" }
        val header = parseCsvLine(lines.first())
        return lines.drop(1).map { line ->
            val cells = parseCsvLine(line)
            buildMap(header.size) {
                for (i in header.indices) put(header[i], cells.getOrElse(i) { "" })
            }
        }
    }

