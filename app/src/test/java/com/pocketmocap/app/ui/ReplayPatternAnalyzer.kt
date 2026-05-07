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

