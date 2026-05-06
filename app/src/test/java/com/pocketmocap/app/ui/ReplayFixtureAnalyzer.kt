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
)

internal data class ReplayFrame(
    val frame: Int,
    val distanceMeters: Float,
    val heightMeters: Float,
    val technicalPoseSource: String,
    val serverPoseStatus: String,
    val rejectedServerReason: String,
    val arTargetDistanceMeters: Float?,
    val arTargetHeightMeters: Float?,
    val constrainedServerDistanceMeters: Float?,
    val constrainedServerHeightMeters: Float?,
)

internal data class ReplayAnalysis(
    val fixtureId: String,
    val rowCount: Int,
    val arFrameCount: Int,
    val missingServerPoseRatio: Float,
    val distanceSpanMeters: Float,
    val heightSpanMeters: Float,
    val distanceStepP95Meters: Float,
    val heightStepP95Meters: Float,
    val sourceSwitchCount: Int,
    val contradictionCount: Int,
    val arSceneMismatchCount: Int,
    val constrainedTargetMismatchCount: Int,
    val distanceMinMeters: Float,
