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
    val distanceMaxMeters: Float,
    val heightMinMeters: Float,
    val heightMaxMeters: Float,
)

internal data class ReplayGateResult(
    val pass: Boolean,
    val failures: List<String>,
)

internal object ReplayFixtureAnalyzer {
    fun loadFixture(resourceFixtureJson: String): Pair<ReplayFixtureMetadata, String> {
        val metadataText = readResourceText("replay/$resourceFixtureJson")
        val meta = parseMetadata(metadataText)
        val csvText = readResourceText("replay/${meta.csvFile}")
        return meta to csvText
    }

    fun analyze(csvText: String, metadata: ReplayFixtureMetadata): ReplayAnalysis {
        val rows = parseCsvRows(csvText)
        val frames = mutableListOf<ReplayFrame>()

        for (row in rows) {
            val frame = row["frame"]?.toIntOrNull() ?: continue
            if (metadata.analysisWindow != null && frame !in metadata.analysisWindow) continue
            if (row["scene_source"] != "arcore_floor") continue
            val distance = row["distance_m"]?.toFiniteFloatOrNull() ?: continue
            val height = row["height_m"]?.toFiniteFloatOrNull() ?: continue

            frames += ReplayFrame(
                frame = frame,
                distanceMeters = distance,
                heightMeters = height,
                technicalPoseSource = row["technical_pose_source"].orEmpty(),
                serverPoseStatus = row["server_pose_status"].orEmpty(),
                rejectedServerReason = row["rejected_server_reason"].orEmpty(),
                arTargetDistanceMeters = row["ar_target_distance_m"]?.toFiniteFloatOrNull(),
                arTargetHeightMeters = row["ar_target_height_m"]?.toFiniteFloatOrNull(),
                constrainedServerDistanceMeters = row["constrained_server_distance_m"]?.toFiniteFloatOrNull(),
                constrainedServerHeightMeters = row["constrained_server_height_m"]?.toFiniteFloatOrNull(),
            )
        }

