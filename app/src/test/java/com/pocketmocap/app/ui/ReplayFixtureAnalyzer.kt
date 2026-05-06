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

        if (frames.isEmpty()) {
            return ReplayAnalysis(
                fixtureId = metadata.id,
                rowCount = rows.size,
                arFrameCount = 0,
                missingServerPoseRatio = 1f,
                distanceSpanMeters = Float.POSITIVE_INFINITY,
                heightSpanMeters = Float.POSITIVE_INFINITY,
                distanceStepP95Meters = Float.POSITIVE_INFINITY,
                heightStepP95Meters = Float.POSITIVE_INFINITY,
                sourceSwitchCount = 0,
                contradictionCount = 0,
                arSceneMismatchCount = 0,
                constrainedTargetMismatchCount = 0,
                distanceMinMeters = Float.POSITIVE_INFINITY,
                distanceMaxMeters = Float.POSITIVE_INFINITY,
                heightMinMeters = Float.POSITIVE_INFINITY,
                heightMaxMeters = Float.POSITIVE_INFINITY,
            )
        }

        val distances = frames.map { it.distanceMeters }
        val heights = frames.map { it.heightMeters }
        val distanceSteps = mutableListOf<Float>()
        val heightSteps = mutableListOf<Float>()
        var sourceSwitches = 0
        var contradictionCount = 0
        var arSceneMismatchCount = 0
        var constrainedTargetMismatchCount = 0

        for (i in frames.indices) {
            val frame = frames[i]
            if (i > 0) {
                val previous = frames[i - 1]
                distanceSteps += abs(frame.distanceMeters - previous.distanceMeters)
                heightSteps += abs(frame.heightMeters - previous.heightMeters)
                if (frame.technicalPoseSource.isNotBlank() &&
                    previous.technicalPoseSource.isNotBlank() &&
                    frame.technicalPoseSource != previous.technicalPoseSource
                ) {
                    sourceSwitches += 1
                }
            }

            val missing = frame.serverPoseStatus == "missing_server_pose" ||
                frame.rejectedServerReason == "missing_server_pose"
            if (frame.technicalPoseSource == "server_dlt" && missing) {
                contradictionCount += 1
            }

            val arDistance = frame.arTargetDistanceMeters
            val arHeight = frame.arTargetHeightMeters
            if (arDistance != null && abs(frame.distanceMeters - arDistance) > 0.35f) {
                arSceneMismatchCount += 1
            }
            if (arHeight != null && abs(frame.heightMeters - arHeight) > 0.30f) {
                arSceneMismatchCount += 1
            }

            val constrainedDistance = frame.constrainedServerDistanceMeters
            val constrainedHeight = frame.constrainedServerHeightMeters
            if (arDistance != null && constrainedDistance != null && abs(constrainedDistance - arDistance) > 1.50f) {
                constrainedTargetMismatchCount += 1
            }
            if (arHeight != null && constrainedHeight != null && abs(constrainedHeight - arHeight) > 1.20f) {
                constrainedTargetMismatchCount += 1
            }
        }

        val missingCount = frames.count {
            it.serverPoseStatus == "missing_server_pose" || it.rejectedServerReason == "missing_server_pose"
        }

        return ReplayAnalysis(
            fixtureId = metadata.id,
            rowCount = rows.size,
            arFrameCount = frames.size,
            missingServerPoseRatio = missingCount.toFloat() / frames.size.toFloat(),
            distanceSpanMeters = distances.maxOrNull()!! - distances.minOrNull()!!,
            heightSpanMeters = heights.maxOrNull()!! - heights.minOrNull()!!,
            distanceStepP95Meters = percentile(distanceSteps, 0.95f),
            heightStepP95Meters = percentile(heightSteps, 0.95f),
            sourceSwitchCount = sourceSwitches,
            contradictionCount = contradictionCount,
            arSceneMismatchCount = arSceneMismatchCount,
            constrainedTargetMismatchCount = constrainedTargetMismatchCount,
            distanceMinMeters = distances.minOrNull()!!,
            distanceMaxMeters = distances.maxOrNull()!!,
            heightMinMeters = heights.minOrNull()!!,
            heightMaxMeters = heights.maxOrNull()!!,
        )
    }

    fun evaluateStrictGate(
        metadata: ReplayFixtureMetadata,
        analysis: ReplayAnalysis,
    ): ReplayGateResult {
        val failures = mutableListOf<String>()
        if (analysis.arFrameCount <= 0) {
            failures += "no_arcore_floor_frames"
        }

        if (analysis.missingServerPoseRatio > metadata.maxMissingServerPoseRatio) {
            failures += "missing_server_pose_ratio_exceeded:${analysis.missingServerPoseRatio}"
        }
        if (analysis.distanceStepP95Meters > metadata.maxDistanceStepP95Meters) {
            failures += "distance_step_p95_exceeded:${analysis.distanceStepP95Meters}"
        }
        if (analysis.heightStepP95Meters > metadata.maxHeightStepP95Meters) {
            failures += "height_step_p95_exceeded:${analysis.heightStepP95Meters}"
        }
        metadata.expectedDistanceRangeMeters?.let { range ->
            if (analysis.distanceMinMeters < range.start || analysis.distanceMaxMeters > range.endInclusive) {
                failures += "distance_range_out_of_band:[${analysis.distanceMinMeters},${analysis.distanceMaxMeters}]"
            }
        }
        metadata.expectedHeightRangeMeters?.let { range ->
            if (analysis.heightMinMeters < range.start || analysis.heightMaxMeters > range.endInclusive) {
                failures += "height_range_out_of_band:[${analysis.heightMinMeters},${analysis.heightMaxMeters}]"
            }
        }
        if (analysis.contradictionCount > 0) {
            failures += "technical_source_contradictions:${analysis.contradictionCount}"
        }
        return ReplayGateResult(pass = failures.isEmpty(), failures = failures)
    }

    private fun parseMetadata(text: String): ReplayFixtureMetadata {
        val id = jsonString(text, "id") ?: error("metadata missing id")
        val csvFile = jsonString(text, "csv_file") ?: error("metadata missing csv_file")
        return ReplayFixtureMetadata(
            id = id,
            csvFile = csvFile,
            expectedDistanceRangeMeters = jsonFloatRange(text, "expected_distance_range_m"),
            expectedHeightRangeMeters = jsonFloatRange(text, "expected_height_range_m"),
            maxMissingServerPoseRatio = jsonFloat(text, "max_missing_server_pose_ratio")
                ?: error("metadata missing max_missing_server_pose_ratio"),
            maxDistanceStepP95Meters = jsonFloat(text, "max_distance_step_p95_m")
                ?: error("metadata missing max_distance_step_p95_m"),
            maxHeightStepP95Meters = jsonFloat(text, "max_height_step_p95_m")
                ?: error("metadata missing max_height_step_p95_m"),
            analysisWindow = jsonWindow(text)?.let { (start, end) ->
                start..end
            },
        )
    }

    private fun parseCsvRows(csvText: String): List<Map<String, String>> {
        val lines = csvText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size >= 2) { "csv requires header + rows" }
        val header = parseCsvLine(lines.first())
        return lines.drop(1).map { line ->
            val cells = parseCsvLine(line)
            buildMap(header.size) {
                for (i in header.indices) {
                    put(header[i], cells.getOrElse(i) { "" })
                }
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        var quoted = false
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i += 1
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    cells += sb.toString()
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i += 1
        }
        cells += sb.toString()
        return cells
    }

    private fun readResourceText(path: String): String {
        val stream = ReplayFixtureAnalyzer::class.java.classLoader?.getResourceAsStream(path)
            ?: error("missing test resource: $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun percentile(values: List<Float>, p: Float): Float {
