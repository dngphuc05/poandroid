package com.pocketmocap.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPatternAnalyzerTest {

    @Test
    fun syntheticThreeStepMotion_isDetectedAsMonotonicPlateauPattern() {
        val csv = buildSyntheticPatternCsv(
            distances = listOf(2.8f, 2.1f, 1.4f),
            heights = listOf(1.67f, 1.67f, 1.67f),
        )
        val analysis = ReplayPatternAnalyzer.analyze(csv, threeStepSpec("synthetic"))
        val best = analysis.bestDistanceScore

        assertNotNull(best)
        assertTrue("synthetic pattern should be monotonic", best!!.monotonic)
        assertTrue("synthetic pattern should fit ground truth tightly: $best", best.score < 0.22f)
        val finalDistanceScore = analysis.distanceScores.firstOrNull { it.column == "distance_m" }
        assertNotNull(finalDistanceScore)
        assertTrue("final distance should also fit ground truth tightly: $finalDistanceScore", finalDistanceScore!!.score < 0.28f)
        assertTrue("height should stay in the expected band", "wrong_height_lock" !in analysis.diagnoses)
    }

    @Test
    fun metrics45_classifiesWrongHeightAndMissingMotionCandidate() {
        val analysis = analyzeFixture("metrics_45_three_step.json")

        assertTrue("metrics45 should expose wrong height: ${analysis.diagnoses}", "wrong_height_lock" in analysis.diagnoses)
        assertTrue("metrics45 should expose missing distance truth candidate: ${analysis.diagnoses}", "missing_truth_candidate" in analysis.diagnoses)
        assertTrue("server pose should not be the diagnosed issue", analysis.rowCount > 0)
    }

    @Test
    fun metrics46_classifiesStaleDistanceAndHipExplosion() {
        val analysis = analyzeFixture("metrics_46_three_step.json")

        assertTrue("metrics46 should expose stale distance hold: ${analysis.diagnoses}", "stale_distance_hold" in analysis.diagnoses)
        assertTrue("metrics46 should expose hip geometry explosion: ${analysis.diagnoses}", "hip_geometry_explosion" in analysis.diagnoses)
        assertTrue("metrics46 should expose untrusted height feedback: ${analysis.diagnoses}", "untrusted_height_feedback" in analysis.diagnoses)
    }

    @Test
    fun metrics47_classifiesLateHipExplosionAndNoTruthCandidate() {
        val analysis = analyzeFixture("metrics_47_three_step.json")

        assertTrue("metrics47 should expose hip geometry explosion: ${analysis.diagnoses}", "hip_geometry_explosion" in analysis.diagnoses)
        assertTrue("metrics47 should expose untrusted height feedback: ${analysis.diagnoses}", "untrusted_height_feedback" in analysis.diagnoses)
        assertTrue("metrics47 should expose missing truth candidate: ${analysis.diagnoses}", "missing_truth_candidate" in analysis.diagnoses)
    }

    @Test
    fun metrics48_classifiesHeightRatchetingWhileServerPoseIsPresent() {
        val (spec, csv) = ReplayPatternAnalyzer.loadSpec("metrics_48_height_lock.json")
        val analysis = ReplayPatternAnalyzer.analyze(csv, spec)
        val rows = csv.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        val header = rows.first().split(",")
        fun column(name: String): Int = header.indexOf(name).also { require(it >= 0) { "missing column $name" } }
        val serverPoseStatus = column("server_pose_status")
        val technicalPoseSource = column("technical_pose_source")
        val heightLockState = column("height_lock_state")
        val height = column("height_m")
        val dataRows = rows.drop(1).map { it.split(",") }

        assertTrue("metrics48 should expose wrong final height: ${analysis.diagnoses}", "wrong_height_lock" in analysis.diagnoses)
        assertTrue("metrics48 should expose untrusted height feedback: ${analysis.diagnoses}", "untrusted_height_feedback" in analysis.diagnoses)
        assertTrue("server pose should be present in the bad capture", dataRows.all { it.getOrNull(serverPoseStatus) == "ok" })
        assertTrue("technical view should still be server DLT", dataRows.all { it.getOrNull(technicalPoseSource) == "server_dlt" })
        assertTrue(
            "old capture should contain untrusted height states that the runtime fix must not acquire from",
            dataRows.count { it.getOrNull(heightLockState)?.contains("untrusted") == true } > dataRows.size / 2,
        )
        assertTrue(
            "old capture briefly visits the true 1.67m band but does not hold it",
            dataRows.count { row -> row.getOrNull(height)?.toFloatOrNull()?.let { it in 1.65f..1.69f } == true } in 1..40,
        )
    }

    @Test
    fun metrics49_classifiesMissingAndWrongHeightWhileSkeletonIsPresent() {
        val (spec, csv) = ReplayPatternAnalyzer.loadSpec("metrics_49_missing_wrong_height.json")
        val analysis = ReplayPatternAnalyzer.analyze(csv, spec)
        val rows = csvRows(csv)
        val finiteHeights = rows.mapNotNull { it["height_m"]?.toFloatOrNull() }
        val nearTruthHeights = finiteHeights.count { it in 1.65f..1.69f }
        val distanceValues = rows.mapNotNull { it["distance_m"]?.toFloatOrNull() }
        val distanceOutsideStrictBand = distanceValues.count { it < 2.0f || it > 2.8f }

        assertTrue("server pose should be present despite bad height", rows.all { it["server_pose_status"] == "ok" })
        assertTrue("technical skeleton should still be server DLT", rows.all { it["technical_pose_source"] == "server_dlt" })
        assertTrue("metrics49 should expose missing height with skeleton present: ${analysis.diagnoses}", "height_missing_with_server_pose" in analysis.diagnoses)
        assertTrue("metrics49 should expose wrong finite height with skeleton present: ${analysis.diagnoses}", "height_wrong_with_server_pose" in analysis.diagnoses)
        assertTrue("metrics49 should expose no usable 1.67m height candidate: ${analysis.diagnoses}", "missing_truth_height_candidate" in analysis.diagnoses)
        assertTrue("metrics49 should expose distance leakage outside 2.0-2.8m: ${analysis.diagnoses}", "distance_outside_expected_band" in analysis.diagnoses)
        assertTrue("finite height should be mostly missing", finiteHeights.size < rows.size / 2)
        assertTrue("no frames should land in the true 1.67m band", nearTruthHeights == 0)
        assertTrue("distance should still contain the expected walking range", distanceValues.minOrNull()!! < 2.0f && distanceValues.maxOrNull()!! > 2.8f)
        assertTrue("many distance frames should be outside strict expected band", distanceOutsideStrictBand > rows.size / 5)
    }

