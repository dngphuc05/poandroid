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
