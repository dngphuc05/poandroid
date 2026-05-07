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
