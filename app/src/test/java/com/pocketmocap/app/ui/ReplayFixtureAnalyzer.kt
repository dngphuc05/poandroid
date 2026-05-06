package com.pocketmocap.app.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ReplayFixtureMetadata(
    val id: String,
    val csvFile: String,
    val expectedDistanceRangeMeters: ClosedFloatingPointRange<Float>?,
    val expectedHeightRangeMeters: ClosedFloatingPointRange<Float>?,
    val maxMissingServerPoseRatio: Float,
    val maxDistanceStepP95Meters: Float,
    val maxHeightStepP95Meters: Float,
    val analysisWindow: IntRange?,
