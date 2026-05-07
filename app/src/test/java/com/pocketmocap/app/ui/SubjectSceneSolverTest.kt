package com.pocketmocap.app.ui

import com.pocketmocap.app.tracking.SceneMetricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectSceneSolverTest {
    @Test
    fun typedMeasurementExtractorReconstructsBaselineOptimizerInput() {
        val raw = rawScene(
            distance = 2.18f,
            height = 1.83f,
            footPlaneDistance = 2.21f,
            roiDistance = 2.05f,
            topRayHeight = 1.86f,
            pixelSpanHeight = 1.74f,
            hipGeometryDistance = 2.12f,
            hipGeometryHeight = 1.82f,
            torsoHeight = 1.80f,
            groundedFootDistance = 2.17f,
        )

        val input = SceneMeasurementExtractor.optimizerInput(
            raw = raw,
            relativeScaleDistance = 2.14f,
            previousDistanceMeters = 2.10f,
            previousHeightMeters = 1.81f,
            floorHeightBiasMeters = 0.02f,
            depthScale = 1.01f,
            depthOffsetMeters = -0.03f,
            heightEndpointBiasMeters = 0.04f,
        )

