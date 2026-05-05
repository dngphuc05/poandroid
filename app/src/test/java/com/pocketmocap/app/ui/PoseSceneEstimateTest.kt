package com.pocketmocap.app.ui

import com.pocketmocap.app.tracking.CameraIntrinsics
import com.pocketmocap.app.tracking.DepthMapSnapshot
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PoseSceneEstimateTest {

    @Test
    fun computePoseRoiReturnsBoundsForVisibleLandmarks() {
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 1f }
        x[0] = 0.40f
        y[0] = 0.20f
        x[31] = 0.62f
        y[31] = 0.93f

        val roi = computePoseRoi(x, y, v)
        assertNotNull(roi)
        assertTrue(roi!!.minX <= 0.40f)
        assertTrue(roi.maxX >= 0.62f)
        assertTrue(roi.minY <= 0.20f)
        assertTrue(roi.maxY >= 0.93f)
    }

    @Test
    fun deriveOverlayPoseEstimateFallsBackToRoiWithoutTracking() {
        val (x, y, v) = buildPoseLandmarks()
        val roi = computePoseRoi(x, y, v)
        val estimate = deriveOverlayPoseEstimate(
            roi = roi,
            rawSubjectHeightMeters = 1.80f,
            screenX = x,
            screenY = y,
            visibility = v,
            worldTracking = null,
        )
        assertNotNull(estimate)
        assertEquals("roi_fallback", estimate!!.source)
        assertTrue(estimate.distanceMeters.isFinite())
        assertTrue(estimate.bodyHeightMeters.isFinite())
    }

    @Test
    fun deriveOverlayPoseEstimateUsesArcoreFloorWithHipDepthPatch() {
        val (x, y, v) = buildPoseLandmarks()
        val roi = computePoseRoi(x, y, v)
        val tracking = buildTrackingSnapshot()
        val estimate = deriveOverlayPoseEstimate(
            roi = roi,
            rawSubjectHeightMeters = 1.78f,
            screenX = x,
            screenY = y,
            visibility = v,
            worldTracking = tracking,
            intrinsics = tracking.intrinsics,
        )
        assertNotNull(estimate)
        assertEquals("arcore_floor", estimate!!.source)
        assertTrue(estimate.confidence >= 0.5f)
        assertTrue(estimate.distanceMeters in 0.35f..12.0f)
        assertTrue(estimate.bodyHeightMeters in 1.15f..2.15f)
    }

    @Test
    fun physicalSceneFactorGraphKeepsHipDepthDiagnosticOnly() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = 2.82f,
            footPlane = 2.62f,
            roiDistance = 2.64f,
            height = 1.80f,
        )
        repeat(80) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = 2.82f,
                    footPlane = 2.62f,
                    roiDistance = 2.64f,
                    height = 1.80f,
                )
            )
        }

        assertTrue("distance should move toward supporting foot/ROI factors", solved.distanceMeters < 2.76f)
        assertEquals("hip depth must not train a physical depth offset", 0f, graph.currentBias.depthOffsetMeters, 1e-5f)
        assertEquals("hip depth must not train a physical depth scale", 1f, graph.currentBias.depthScale, 1e-5f)
        assertTrue("solver confidence should remain usable", solved.solverConfidence > 0.45f)
    }

    @Test
    fun physicalSceneFactorGraphKeepsHeightStableWhenDistanceChanges() {
        val graph = PhysicalSceneFactorGraph()
        repeat(12) {
            graph.solve(
                rawSceneMetric(
                    hipDepth = 2.45f,
                    footPlane = 2.48f,
                    roiDistance = 2.50f,
                    height = 1.82f,
                    topRayHeight = 1.83f,
                )
            )
        }
        val heights = mutableListOf<Float>()
        val distances = listOf(2.25f, 2.55f, 2.95f, 3.20f, 2.70f, 2.35f)
        for (distance in distances) {
            val solved = graph.solve(
                rawSceneMetric(
                    hipDepth = distance,
                    footPlane = distance + 0.03f,
                    roiDistance = distance - 0.02f,
                    height = 1.82f + if (distance > 3.0f) -0.10f else 0.02f,
                )
            )
            heights += solved.bodyHeightMeters
        }

        val heightSpan = heights.maxOrNull()!! - heights.minOrNull()!!
        assertTrue("height should stay locked while distance moves", heightSpan < 0.10f)
    }

    @Test
    fun physicalSceneFactorGraphDoesNotChaseFootPlaneWhenHipDepthMissing() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.46f,
            roiDistance = 2.52f,
            height = 1.80f,
        )
        val driftingFoot = listOf(2.55f, 2.80f, 3.25f, 3.80f, 4.20f, 3.70f, 3.20f)
        val spreads = mutableListOf<Float>()
        for (foot in driftingFoot) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = foot,
                    roiDistance = 2.56f,
                    height = 1.80f,
                )
            )
            spreads += solved.distanceCandidateSpreadMeters
        }

        assertTrue(
            "without hip depth, a drifting foot ray should not pull distance far away from ROI support",
            solved.distanceMeters < 2.85f,
        )
        assertTrue(
            "large foot/ROI disagreement should be visible in diagnostics",
            spreads.maxOrNull()!! > 1.0f,
        )
    }

    @Test
    fun physicalSceneFactorGraphRetargetsAfterBadStartupDistance() {
        val graph = PhysicalSceneFactorGraph()
        var solved = graph.solve(
            rawSceneMetric(
                hipDepth = 1.68f,
                footPlane = 1.32f,
                roiDistance = 2.26f,
                height = 1.80f,
            )
        )

        repeat(36) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.62f,
                    roiDistance = 2.54f,
                    height = 1.80f,
                )
            )
        }

        assertTrue("distance should recover after sustained stable evidence", solved.distanceMeters > 2.25f)
        assertTrue("distance recovery should not overshoot the supported range", solved.distanceMeters < 2.75f)
    }

    @Test
    fun physicalSceneFactorGraphKeepsDistanceStableDuringSideHandMotion() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.10f,
            roiDistance = 2.12f,
            height = 1.83f,
            topRayHeight = 1.84f,
            hipGeometryDistance = 2.10f,
            hipGeometryHeight = 1.84f,
            torsoHeight = 1.82f,
            groundedFootDistance = 2.10f,
            footContactState = "grounded_roi_supported",
            distance = 2.10f,
        )
        repeat(20) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.10f,
                    roiDistance = 2.12f,
                    height = 1.83f,
                    topRayHeight = 1.84f,
                    hipGeometryDistance = 2.10f,
                    hipGeometryHeight = 1.84f,
                    torsoHeight = 1.82f,
                    groundedFootDistance = 2.10f,
                    footContactState = "grounded_roi_supported",
                    distance = 2.10f,
                )
            )
        }

        val distances = mutableListOf<Float>()
        val noisyHipDistances = listOf(1.82f, 1.48f, 1.28f, 1.36f, 1.70f, 1.56f, 1.90f, 1.42f)
        repeat(48) { index ->
            val hip = noisyHipDistances[index % noisyHipDistances.size]
            val foot = 2.10f + if (index % 2 == 0) 0.03f else -0.02f
            val roi = 2.12f + if (index % 3 == 0) 0.03f else -0.01f
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = foot,
                    roiDistance = roi,
                    height = 1.83f,
                    topRayHeight = 1.84f,
                    hipGeometryDistance = hip,
                    hipGeometryHeight = 1.84f,
                    torsoHeight = 1.82f,
                    groundedFootDistance = foot,
                    footContactState = "grounded_roi_supported",
                    distance = hip * 0.78f + foot * 0.22f,
                )
            )
            distances += solved.distanceMeters
        }

        val distanceSpan = distances.maxOrNull()!! - distances.minOrNull()!!
        assertTrue("hand-driven hip distance noise should be rejected", solved.activeFactors.contains("rejected_hip_geometry"))
        assertTrue("distance should stay near the stable 2.1m working position", distances.minOrNull()!! > 1.95f)
        assertTrue("side hand motion should not create large distance flicker", distanceSpan < 0.18f)
    }

    @Test
    fun physicalSceneFactorGraphReportsLowConfidenceForDisagreeingFactors() {
        val graph = PhysicalSceneFactorGraph()
        val solved = graph.solve(
            rawSceneMetric(
                hipDepth = 5.20f,
                footPlane = 2.10f,
                roiDistance = 3.40f,
                height = 1.20f,
                topRayHeight = 2.05f,
                hipGeometryHeight = 2.05f,
                pixelSpanHeight = 2.05f,
            )
        )

        assertTrue(solved.distanceCandidateSpreadMeters > 1.0f)
        assertTrue(solved.heightCandidateSpreadMeters > 0.7f)
        assertTrue(solved.solverConfidence < 0.55f)
    }

    @Test
    fun physicalSceneFactorGraphRejectsWildHipAndFootAgainstRoi() {
        val graph = PhysicalSceneFactorGraph()
        val solved = graph.solve(
            rawSceneMetric(
                hipDepth = 8.0f,
                footPlane = 7.0f,
                roiDistance = 3.5f,
                height = 1.92f,
                topRayHeight = 2.45f,
            )
        )

