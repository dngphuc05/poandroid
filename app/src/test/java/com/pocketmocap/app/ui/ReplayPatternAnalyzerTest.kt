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
