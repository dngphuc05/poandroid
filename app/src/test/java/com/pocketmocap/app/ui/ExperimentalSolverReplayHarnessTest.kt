package com.pocketmocap.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

internal data class ReplayTruth(
    val captureId: Int,
    val expectedHeightMeters: Float?,
    val expectedDistanceRangeMeters: ClosedFloatingPointRange<Float>?,
    val stableMiddleWindow: IntRange,
    val knownGoodBaseline: Boolean = false,
    val notes: String = "",
)

internal object ReplayTruthRegistry {
    val captures: Map<Int, ReplayTruth> = (54..73).associateWith { id ->
        when (id) {
            54 -> ReplayTruth(id, 1.83f, 1.4f..2.8f, 110..680, notes = "metrics_54 physical-scene regression")
            60 -> ReplayTruth(id, 1.83f, 2.1f..2.8f, 110..128, notes = "short good window called out by capture notes")
            61 -> ReplayTruth(id, 1.83f, 2.1f..2.8f, 80..620, notes = "2.1m to 2.8m walking range")
            65 -> ReplayTruth(id, 1.83f, 1.4f..2.8f, 80..620, notes = "tall subject degraded")
            66 -> ReplayTruth(id, 1.67f, 1.4f..2.8f, 80..620, knownGoodBaseline = true, notes = "shorter subject known good")
            67 -> ReplayTruth(id, 1.67f, 1.4f..2.8f, 80..620, knownGoodBaseline = true, notes = "shorter subject known good")
            68 -> ReplayTruth(id, 1.82f, 1.4f..2.8f, 80..620, notes = "taller subject comparison")
            69 -> ReplayTruth(id, 1.83f, 1.4f..2.8f, 80..620, notes = "height stabilizes after startup")
            70 -> ReplayTruth(id, 1.83f, 1.4f..2.8f, 80..620, notes = "slow true-height lock")
            71 -> ReplayTruth(id, 1.83f, 1.4f..2.8f, 80..620, notes = "bad high startup lock")
            72 -> ReplayTruth(id, 1.83f, 1.4f..2.8f, 80..620, notes = "slow stabilization")
            73 -> ReplayTruth(id, 1.83f, 1.4f..2.8f, 80..620, notes = "height and distance regressed")
            else -> ReplayTruth(id, 1.83f, 1.4f..2.8f, 80..620, notes = "capture note pending; default tall-subject target")
        }
    }
}

internal data class ReplayMetricRow(
    val frame: Int,
    val heightMeters: Float?,
    val correctedHeightMeters: Float?,
    val distanceMeters: Float?,
    val correctedDistanceMeters: Float?,
    val heightLockState: String,
)

internal data class ReplayScore(
    val rows: Int,
    val exportRate: Float,
    val medianHeightErrorMeters: Float?,
    val p90HeightErrorMeters: Float?,
    val medianDistanceErrorMeters: Float?,
    val distanceOutOfRangeRate: Float?,
    val heightJitterP90Meters: Float?,
)

internal object CsvReplayLoader {
    fun loadLocalMetrics(captureId: Int): List<ReplayMetricRow>? {
        val file = File("C:\\Users\\Asus\\Downloads\\metrics_$captureId.csv")
        return if (file.isFile) parse(file.readText()) else null
    }

    fun parse(csv: String): List<ReplayMetricRow> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val headers = splitCsvLine(lines.first())
        val index = headers.withIndex().associate { it.value to it.index }
        return lines.drop(1).mapNotNull { line ->
            val values = splitCsvLine(line)
            val frame = values.getOrNull(index["frame"] ?: -1)?.toIntOrNull() ?: return@mapNotNull null
            ReplayMetricRow(
                frame = frame,
                heightMeters = values.floatAt(index["height_m"]),
                correctedHeightMeters = values.floatAt(index["corrected_height_m"]),
                distanceMeters = values.floatAt(index["distance_m"]),
                correctedDistanceMeters = values.floatAt(index["corrected_distance_m"]),
                heightLockState = values.getOrNull(index["height_lock_state"] ?: -1).orEmpty(),
            )
        }
    }

    private fun List<String>.floatAt(index: Int?): Float? =
        index
            ?.takeIf { it >= 0 }
            ?.let { getOrNull(it) }
            ?.toFloatOrNull()
            ?.takeIf { it.isFinite() }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }
}

internal object ReplayScorer {
    fun score(rows: List<ReplayMetricRow>, truth: ReplayTruth): ReplayScore {
        val stable = rows.filter { it.frame in truth.stableMiddleWindow }
        val exportedHeights = stable.mapNotNull { it.correctedHeightMeters ?: it.heightMeters }
        val distances = stable.mapNotNull { it.correctedDistanceMeters ?: it.distanceMeters }
        val heightErrors = truth.expectedHeightMeters?.let { expected ->
            exportedHeights.map { abs(it - expected) }
        }.orEmpty()
        val distanceMidpoint = truth.expectedDistanceRangeMeters?.let { (it.start + it.endInclusive) * 0.5f }
        val distanceErrors = distanceMidpoint?.let { expected ->
            distances.map { abs(it - expected) }
        }.orEmpty()
        val distanceOutOfRangeRate = truth.expectedDistanceRangeMeters?.let { range ->
            if (distances.isEmpty()) null else distances.count { it !in range }.toFloat() / distances.size.toFloat()
        }
        val heightDiffs = exportedHeights.zipWithNext { a, b -> abs(b - a) }
        return ReplayScore(
            rows = stable.size,
            exportRate = if (stable.isEmpty()) 0f else exportedHeights.size.toFloat() / stable.size.toFloat(),
            medianHeightErrorMeters = percentile(heightErrors, 0.50f),
            p90HeightErrorMeters = percentile(heightErrors, 0.90f),
            medianDistanceErrorMeters = percentile(distanceErrors, 0.50f),
            distanceOutOfRangeRate = distanceOutOfRangeRate,
            heightJitterP90Meters = percentile(heightDiffs, 0.90f),
        )
    }

    private fun percentile(values: List<Float>, p: Float): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val index = (sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }
}

class ExperimentalSolverReplayHarnessTest {
    @Test
    fun replayTruthRegistryCoversMetrics54Through73() {
        assertEquals((54..73).toSet(), ReplayTruthRegistry.captures.keys)
        assertEquals(1.67f, ReplayTruthRegistry.captures.getValue(67).expectedHeightMeters)
        assertTrue(ReplayTruthRegistry.captures.getValue(67).knownGoodBaseline)
        assertEquals(1.82f, ReplayTruthRegistry.captures.getValue(68).expectedHeightMeters)
    }

    @Test
    fun csvReplayParserScoresMedianP90AndDistanceRange() {
        val rows = CsvReplayLoader.parse(
            """
            frame,height_m,corrected_height_m,distance_m,corrected_distance_m,height_lock_state
            100,1.80,1.81,2.00,2.05,locked
            101,1.82,1.83,2.10,2.10,locked
            102,1.84,1.85,2.90,2.90,locked
            """.trimIndent()
        )

        val truth = ReplayTruth(99, 1.83f, 2.0f..2.8f, 100..102)
        val score = ReplayScorer.score(rows, truth)

        assertEquals(3, score.rows)
        assertEquals(1f, score.exportRate, 0f)
        assertEquals(0.02f, score.medianHeightErrorMeters ?: -1f, 1e-4f)
        assertEquals(0.02f, score.p90HeightErrorMeters ?: -1f, 1e-4f)
        assertEquals(1f / 3f, score.distanceOutOfRangeRate ?: -1f, 1e-4f)
        assertFalse(score.heightJitterP90Meters == null)
    }

