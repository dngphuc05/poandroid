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

