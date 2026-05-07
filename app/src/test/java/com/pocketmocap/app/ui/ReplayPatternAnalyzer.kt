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

