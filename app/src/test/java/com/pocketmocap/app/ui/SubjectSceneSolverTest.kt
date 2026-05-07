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
        assertEquals(0.02f, input.floorHeightBiasMeters, 0f)
        assertEquals(1.01f, input.depthScale, 0f)
        assertEquals(-0.03f, input.depthOffsetMeters, 0f)
        assertEquals(0.04f, input.heightEndpointBiasMeters, 0f)
    }

    @Test
    fun physicalSceneFactorGraphLogsNoopExperimentalShadowFields() {
        val graph = PhysicalSceneFactorGraph()
        val solved = graph.solve(
            rawScene(
                distance = 2.12f,
                height = 1.82f,
                footPlaneDistance = 2.11f,
                roiDistance = 2.15f,
                topRayHeight = 1.83f,
                pixelSpanHeight = 1.81f,
                hipGeometryDistance = 2.12f,
                hipGeometryHeight = 1.82f,
                torsoHeight = 1.81f,
                groundedFootDistance = 2.11f,
            )
        )

        assertEquals("baseline", solved.promotedSolverSource)
        assertEquals("shadow_sliding_window", solved.experimentalSolverStatus)
        assertTrue(solved.experimentalFactorSummary.contains("measurements="))
        assertTrue(solved.experimentalHeightMeters.isFinite())
        assertTrue(solved.experimentalDistanceMeters.isFinite())
        assertTrue(solved.experimentalHeightSigmaMeters in 0.015f..0.80f)
        assertTrue(solved.experimentalDistanceSigmaMeters in 0.02f..2.50f)
        assertEquals(0f, solved.baselineExperimentalDistanceDeltaMeters, 1e-4f)
    }

    @Test
    fun slidingWindowLearnsHighHipHeightBiasAndStaysNearStableTopRay() {
        val solver = SubjectSceneSolver(windowSize = 30)
        var solved = rawScene(
            distance = 2.10f,
            height = 1.88f,
            footPlaneDistance = 2.22f,
            roiDistance = 2.38f,
            topRayHeight = 1.83f,
            pixelSpanHeight = 1.68f,
            hipGeometryDistance = 1.92f,
            hipGeometryHeight = 1.88f,
            torsoHeight = 1.47f,
            groundedFootDistance = 2.22f,
        )
        repeat(35) {
            val raw = rawScene(
                distance = 2.08f,
                height = 1.88f,
                footPlaneDistance = 2.22f + ((it % 3) - 1) * 0.01f,
                roiDistance = 2.38f,
                topRayHeight = 1.83f + ((it % 5) - 2) * 0.003f,
                pixelSpanHeight = 1.68f,
                hipGeometryDistance = 1.92f,
                hipGeometryHeight = 1.88f + ((it % 4) - 2) * 0.004f,
                torsoHeight = 1.47f,
                groundedFootDistance = 2.22f,
            )
            solved = solver.solveShadow(raw, SceneMeasurementExtractor.extract(raw, Float.NaN))
        }

        assertEquals("shadow_sliding_window", solved.experimentalSolverStatus)
        assertTrue(solved.promotedSolverSource in setOf("baseline", "experimental_distance"))
        assertTrue(solved.experimentalHeightState == "shadow_window_ready")
        assertTrue(
            "expected ${solved.experimentalHeightMeters} near stable top ray, not high hip geometry",
            solved.experimentalHeightMeters in 1.81f..1.85f,
        )
        assertTrue(solved.experimentalFactorSummary.contains("hip_bias=0."))
    }

    @Test
    fun slidingWindowDistancePrefersStableGroundedFootOverLowHipGeometry() {
        val solver = SubjectSceneSolver(windowSize = 30)
        var solved = rawScene(
            distance = 2.02f,
            height = 1.83f,
            footPlaneDistance = 2.25f,
            roiDistance = 2.40f,
            topRayHeight = 1.83f,
            pixelSpanHeight = 1.70f,
            hipGeometryDistance = 1.90f,
            hipGeometryHeight = 1.83f,
            torsoHeight = 1.48f,
            groundedFootDistance = 2.25f,
        )
        repeat(35) {
            val raw = rawScene(
