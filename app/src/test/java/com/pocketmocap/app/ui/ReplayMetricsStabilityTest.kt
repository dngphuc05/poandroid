package com.pocketmocap.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayMetricsStabilityTest {

    @Test
    fun stableFixture_passesStrictReplayGate() {
        val (meta, csv) = ReplayFixtureAnalyzer.loadFixture("stable_metrics_12_f121_140.json")
        val analysis = ReplayFixtureAnalyzer.analyze(csv, meta)
        val gate = ReplayFixtureAnalyzer.evaluateStrictGate(meta, analysis)

        assertTrue("stable fixture should pass strict gate: ${gate.failures}", gate.pass)
        assertTrue("stable fixture should have no source switches", analysis.sourceSwitchCount == 0)
    }

    @Test
    fun pushPullFixture_distanceMovesWhileHeightRemainsBounded() {
        val (meta, csv) = ReplayFixtureAnalyzer.loadFixture("pushpull_metrics_12_f40_79.json")
        val analysis = ReplayFixtureAnalyzer.analyze(csv, meta)
        val gate = ReplayFixtureAnalyzer.evaluateStrictGate(meta, analysis)

        assertTrue("push/pull fixture should pass strict gate: ${gate.failures}", gate.pass)
        assertTrue("distance should show movement in push/pull", analysis.distanceSpanMeters >= 0.80f)
        assertTrue("height should stay bounded while distance moves", analysis.heightSpanMeters <= 0.35f)
    }

    @Test
    fun occlusionFixture_toleratesSmallDropoutWithoutCatastrophicLoss() {
        val (meta, csv) = ReplayFixtureAnalyzer.loadFixture("occlusion_metrics_12_f20_39.json")
        val analysis = ReplayFixtureAnalyzer.analyze(csv, meta)
        val gate = ReplayFixtureAnalyzer.evaluateStrictGate(meta, analysis)

        assertTrue("occlusion fixture should pass strict gate: ${gate.failures}", gate.pass)
        assertTrue("occlusion fixture should keep server pose mostly present", analysis.missingServerPoseRatio <= 0.06f)
        assertTrue("occlusion fixture should not thrash source switching", analysis.sourceSwitchCount <= 2)
    }

    @Test
    fun degradedFixture_isDetectedAsUnstableByStrictGate() {
        val (meta, csv) = ReplayFixtureAnalyzer.loadFixture("degraded_metrics_12_f0_19.json")
        val analysis = ReplayFixtureAnalyzer.analyze(csv, meta)
        val gate = ReplayFixtureAnalyzer.evaluateStrictGate(meta, analysis)

        assertFalse("degraded fixture must fail strict gate", gate.pass)
        assertTrue(
            "degraded fixture should fail by geometry instability or missing ratio: ${gate.failures}",
            gate.failures.any { reason ->
                reason.startsWith("distance_range_out_of_band") ||
                    reason.startsWith("height_range_out_of_band") ||
                    reason.startsWith("distance_step_p95_exceeded") ||
                    reason.startsWith("missing_server_pose_ratio_exceeded")
            }
        )
    }

    @Test
    fun crossStackConsistency_hasNoContradictoryPoseState() {
        val fixtures = listOf(
            "stable_metrics_12_f121_140.json",
            "pushpull_metrics_12_f40_79.json",
            "occlusion_metrics_12_f20_39.json",
        )
        for (fixture in fixtures) {
            val (meta, csv) = ReplayFixtureAnalyzer.loadFixture(fixture)
