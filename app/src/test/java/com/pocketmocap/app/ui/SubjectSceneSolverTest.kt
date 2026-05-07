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

        assertEquals(raw.confidence, input.confidence, 0f)
        assertEquals(raw.distanceMeters, input.rawDistanceMeters, 0f)
        assertEquals(raw.bodyHeightMeters, input.rawHeightMeters, 0f)
        assertEquals(raw.cameraHeightMeters, input.rawCameraHeightMeters, 0f)
        assertTrue(input.rawHipDepthDistanceMeters.isNaN())
        assertEquals(raw.footPlaneDistanceMeters, input.footPlaneDistanceMeters, 0f)
        assertEquals(raw.roiDistanceMeters, input.roiDistanceMeters, 0f)
        assertEquals(raw.topRayHeightMeters, input.topRayHeightMeters, 0f)
        assertEquals(raw.pixelSpanHeightMeters, input.pixelSpanHeightMeters, 0f)
        assertEquals(raw.hipGeometryDistanceMeters, input.hipGeometryDistanceMeters, 0f)
        assertEquals(raw.hipGeometryHeightMeters, input.hipGeometryHeightMeters, 0f)
        assertEquals(raw.torsoHeightMeters, input.torsoHeightMeters, 0f)
        assertEquals(raw.groundedFootDistanceMeters, input.groundedFootDistanceMeters, 0f)
        assertEquals(raw.bodyScaleConfidence, input.bodyScaleConfidence, 0f)
        assertEquals(raw.boneLengthSpreadMeters, input.boneLengthSpreadMeters, 0f)
        assertEquals(2.14f, input.relativeScaleDistanceMeters, 0f)
        assertEquals(2.10f, input.previousDistanceMeters, 0f)
        assertEquals(1.81f, input.previousHeightMeters, 0f)
