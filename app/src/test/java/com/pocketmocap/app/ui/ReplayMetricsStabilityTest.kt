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

