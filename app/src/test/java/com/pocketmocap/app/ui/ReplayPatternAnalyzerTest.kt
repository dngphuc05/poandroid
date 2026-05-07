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

