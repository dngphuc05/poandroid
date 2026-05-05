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

        assertFalse("hip depth should not be an active physical factor", solved.activeFactors.contains("hip_depth"))
        assertTrue("bad foot plane should be visible as rejected", solved.activeFactors.contains("rejected_foot_plane"))
        assertTrue("wild disagreement should lower solver confidence", solved.solverConfidence < 0.45f)
        assertEquals("height must not lock from untrusted evidence", "acquiring_untrusted", solved.heightLockState)
        assertFalse("untrusted first frame should not export corrected height", solved.correctedHeightMeters.isFinite())
        assertTrue("distance should stay near ROI support instead of bad AR/depth factors", solved.distanceMeters in 3.0f..4.0f)
    }

    @Test
    fun physicalSceneOptimizerRejectsHipGeometryExplosionAgainstTemporalSupport() {
        val solved = PhysicalSceneOptimizer.optimize(
            optimizerInput(
                previousDistance = 2.80f,
                previousHeight = 1.67f,
                rawDistance = 8.10f,
                rawHeight = 2.06f,
                hipGeometryDistance = 8.25f,
                footDistance = 3.48f,
                relativeScaleDistance = 2.10f,
            )
        )

        assertTrue("exploded hip geometry should be diagnosed", solved.activeFactors.contains("rejected_hip_geometry"))
        assertTrue("rejected hip geometry should not pull the fallback to 8m", solved.distanceMeters < 3.20f)
        assertFalse("bad untrusted 2.06m height should not become an exported trusted solve", solved.heightTrusted)
    }

    @Test
    fun physicalSceneOptimizerRejectsLowHipGeometryAgainstGroundedFootRoiSupport() {
        val solved = PhysicalSceneOptimizer.optimize(
            optimizerInput(
                previousDistance = 2.10f,
                previousHeight = 1.83f,
                rawDistance = 1.52f,
                rawHeight = 1.83f,
                hipGeometryDistance = 1.24f,
                footDistance = 2.18f,
                relativeScaleDistance = 2.08f,
                roiDistance = 2.20f,
                groundedFootDistance = 2.18f,
            )
        )

        assertTrue("low hip geometry should be diagnosed", solved.activeFactors.contains("rejected_hip_geometry"))
        assertEquals("rejected hip geometry must not be a distance factor", 0f, solved.weightHip, 1e-5f)
        assertTrue("stable non-hip evidence should keep distance near the 2.1m anchor", solved.distanceMeters in 2.05f..2.22f)
    }

    @Test
    fun physicalSceneOptimizerUsesGroundedFootRoiWhenHipGeometryCollapses() {
        var previousDistance = 2.08f
        var solved = PhysicalSceneOptimizer.optimize(
            optimizerInput(
                previousDistance = previousDistance,
                previousHeight = 1.83f,
                rawDistance = 2.05f,
                rawHeight = 1.83f,
                hipGeometryDistance = 1.66f,
                footDistance = 2.34f,
                relativeScaleDistance = 2.05f,
                roiDistance = 2.29f,
                groundedFootDistance = 2.34f,
                topHeight = 1.84f,
                hipGeometryHeight = 1.83f,
                torsoHeight = 1.82f,
                pixelHeight = 1.85f,
            )
        )
        repeat(16) {
            previousDistance = solved.distanceMeters
            solved = PhysicalSceneOptimizer.optimize(
                optimizerInput(
                    previousDistance = previousDistance,
                    previousHeight = 1.83f,
                    rawDistance = 2.05f,
                    rawHeight = 1.83f,
                    hipGeometryDistance = 1.66f,
                    footDistance = 2.34f,
                    relativeScaleDistance = 2.05f,
                    roiDistance = 2.29f,
                    groundedFootDistance = 2.34f,
                    topHeight = 1.84f,
                    hipGeometryHeight = 1.83f,
                    torsoHeight = 1.82f,
                    pixelHeight = 1.85f,
                )
            )
        }

        assertTrue("grounded foot+ROI agreement should pull distance back into the 2.1m+ capture range", solved.distanceMeters > 2.16f)
        assertTrue("stable foot+ROI support should not overshoot the user's 2.8m range", solved.distanceMeters < 2.80f)
        assertTrue(solved.activeFactors.contains("rejected_hip_geometry"))
    }

    @Test
    fun physicalSceneOptimizerUsesRelativeScaleAsWeakRepeatedMotionCue() {
        var previousDistance = 2.80f
        repeat(36) {
            val solved = PhysicalSceneOptimizer.optimize(
                optimizerInput(
                    previousDistance = previousDistance,
                    previousHeight = 1.67f,
                    rawDistance = Float.NaN,
                    rawHeight = 1.67f,
                    hipGeometryDistance = Float.NaN,
                    footDistance = Float.NaN,
                    relativeScaleDistance = 2.10f,
                )
            )
            previousDistance = solved.distanceMeters
        }

        assertTrue("repeated relative torso scale should retarget toward the closer step", previousDistance in 1.95f..2.25f)
    }

    @Test
    fun physicalSceneOptimizerRejectsHighSpreadSemanticHeight() {
        val solved = PhysicalSceneOptimizer.optimize(
            optimizerInput(
                previousDistance = 2.60f,
                previousHeight = Float.NaN,
                rawDistance = 2.62f,
                rawHeight = 2.10f,
                hipGeometryDistance = 2.64f,
                footDistance = 2.60f,
                relativeScaleDistance = Float.NaN,
                topHeight = 2.00f,
                hipGeometryHeight = 2.20f,
                torsoHeight = 1.80f,
                pixelHeight = 2.55f,
            )
        )

        assertFalse("high-spread semantic height should not be trusted", solved.heightTrusted)
        assertTrue("height rejection should be visible", solved.activeFactors.contains("untrusted_height_spread"))
    }

    @Test
    fun physicalSceneOptimizerDropsOutOfRangeTopAndPixelHeightFactors() {
        val solved = PhysicalSceneOptimizer.optimize(
            optimizerInput(
                previousDistance = 2.10f,
                previousHeight = 1.83f,
                rawDistance = 2.10f,
                rawHeight = 1.83f,
                hipGeometryDistance = 2.10f,
                footDistance = Float.NaN,
                relativeScaleDistance = Float.NaN,
                topHeight = 2.72f,
                hipGeometryHeight = 1.84f,
                torsoHeight = 1.82f,
                pixelHeight = 2.78f,
            )
        )

        assertTrue("out-of-range top/pixel must not inflate height spread", solved.heightCandidateSpreadMeters < 0.04f)
        assertFalse("top-ray outlier should be excluded from trusted height factors", solved.activeFactors.contains("top_ray"))
        assertFalse("pixel outlier should be excluded from trusted height factors", solved.activeFactors.contains("pixel_span"))
    }

    @Test
    fun physicalSceneFactorGraphDoesNotAcquireUntrustedTorsoFallback() {
        val graph = PhysicalSceneFactorGraph()
        var solved = graph.solve(
            rawSceneMetric(
                hipDepth = Float.NaN,
                footPlane = 2.60f,
                roiDistance = 2.62f,
                height = 2.10f,
                topRayHeight = 2.00f,
                hipGeometryHeight = 2.20f,
                torsoHeight = 1.80f,
                pixelSpanHeight = 2.55f,
            )
        )
        repeat(14) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.60f,
                    roiDistance = 2.62f,
                    height = 2.10f,
                    topRayHeight = 2.00f,
                    hipGeometryHeight = 2.20f,
                    torsoHeight = 1.80f,
                    pixelSpanHeight = 2.55f,
                )
            )
        }

        assertEquals("acquiring_untrusted", solved.heightLockState)
        // The factor graph publishes a diagnostic body-height during the
        // untrusted state so the UI does not blank out, but it must not
        // export a constraint to the server.
        assertFalse(
            "untrusted fallback must not export server height constraint",
            solved.correctedHeightMeters.isFinite(),
        )
        assertTrue(solved.activeFactors.contains("rejected_torso_fallback_lock"))
    }

    @Test
    fun physicalSceneFactorGraphDoesNotTrustDuplicatedTorsoHipHeight() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.56f,
            roiDistance = Float.NaN,
            height = 1.846f,
            topRayHeight = 1.97f,
            hipGeometryHeight = 1.846f,
            torsoHeight = 1.846f,
            pixelSpanHeight = 2.15f,
        )

        repeat(12) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.56f,
                    roiDistance = Float.NaN,
                    height = 1.846f,
                    topRayHeight = 1.97f,
                    hipGeometryHeight = 1.846f,
                    torsoHeight = 1.846f,
                    pixelSpanHeight = 2.15f,
                )
            )
        }

        assertEquals("acquiring_untrusted", solved.heightLockState)
        // Diagnostic body-height may surface for the UI, but the server
        // constraint export must remain gated when hip and torso heights are
        // numerically duplicated (i.e., the same torso-ratio seed counted
        // twice does not constitute independent semantic agreement).
        assertFalse(
            "duplicated torso/hip height must not constrain the server",
            solved.correctedHeightMeters.isFinite(),
        )
        assertTrue(solved.activeFactors.contains("rejected_height_semantic_agreement"))
    }

    @Test
    fun physicalSceneFactorGraphBuildsRelativeMotionAnchorFromTrustedDistanceOnly() {
        val graph = PhysicalSceneFactorGraph()
        graph.solve(
            rawSceneMetric(
                hipDepth = Float.NaN,
                footPlane = 2.80f,
                roiDistance = 2.80f,
                height = 1.846f,
                topRayHeight = 1.97f,
                hipGeometryHeight = 1.846f,
                torsoHeight = 1.846f,
                torsoSpanNorm = 0.10f,
                pixelSpanHeight = 2.15f,
            )
        )

        val closer = graph.solve(
            rawSceneMetric(
                hipDepth = Float.NaN,
                footPlane = Float.NaN,
                roiDistance = Float.NaN,
                height = 1.846f,
                topRayHeight = 1.97f,
                hipGeometryHeight = 1.846f,
                torsoHeight = 1.846f,
                torsoSpanNorm = 0.14f,
                pixelSpanHeight = 2.15f,
            )
        )

        assertTrue("relative torso scale should become available even while height is untrusted", closer.relativeScaleDistanceMeters.isFinite())
        assertTrue("larger torso span should imply moving closer", closer.relativeScaleDistanceMeters < 2.35f)
        assertEquals("acquiring_untrusted", closer.heightLockState)
    }

    @Test
    fun physicalSceneFactorGraphDoesNotSeedDistanceFromUngroundedFootOnly() {
        val graph = PhysicalSceneFactorGraph()
        val solved = graph.solve(
            rawSceneMetric(
                hipDepth = Float.NaN,
                footPlane = 8.40f,
                roiDistance = Float.NaN,
                height = 1.83f,
                topRayHeight = 1.84f,
                hipGeometryDistance = Float.NaN,
                distance = Float.NaN,
            )
        )

        assertTrue(
            "an unreferenced moving foot hit must not become the primary distance lock",
            !solved.distanceMeters.isFinite() || solved.distanceMeters < 4.0f,
        )
        assertTrue("raw foot should remain visible as a rejected diagnostic", solved.activeFactors.contains("rejected_foot_plane"))
    }

    @Test
    fun physicalSceneFactorGraphHoldsGoodHeightThroughLaterBadCandidates() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.58f,
            roiDistance = 2.60f,
            height = 1.67f,
            topRayHeight = 1.67f,
            hipGeometryHeight = 1.67f,
            torsoHeight = 1.67f,
            pixelSpanHeight = 1.72f,
        )
        repeat(14) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.58f,
                    roiDistance = 2.60f,
                    height = 1.67f,
                    topRayHeight = 1.67f,
                    hipGeometryHeight = 1.67f,
                    torsoHeight = 1.67f,
                    pixelSpanHeight = 1.72f,
                )
            )
        }
        val lockedHeight = solved.bodyHeightMeters

        repeat(20) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.63f,
                    roiDistance = 2.66f,
                    height = 2.05f,
                    topRayHeight = 2.02f,
                    hipGeometryHeight = 2.24f,
                    torsoHeight = 2.18f,
                    pixelSpanHeight = 2.60f,
                )
            )
        }

        assertEquals("holding_untrusted", solved.heightLockState)
        assertTrue("locked height should move less than 2cm", kotlin.math.abs(solved.bodyHeightMeters - lockedHeight) < 0.02f)
        assertFalse("held untrusted height must not be sent as a constraint", solved.correctedHeightMeters.isFinite())
    }

    @Test
    fun physicalSceneFactorGraphKeepsLockedHeightExportThroughMildCandidateGaps() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.08f,
            roiDistance = 2.10f,
            height = 1.83f,
            topRayHeight = 1.84f,
            hipGeometryDistance = 2.10f,
            hipGeometryHeight = 1.83f,
            torsoHeight = 1.82f,
            pixelSpanHeight = 1.84f,
        )
        repeat(14) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.08f,
                    roiDistance = 2.10f,
                    height = 1.83f,
                    topRayHeight = 1.84f,
                    hipGeometryDistance = 2.10f,
                    hipGeometryHeight = 1.83f,
                    torsoHeight = 1.82f,
                    pixelSpanHeight = 1.84f,
                )
            )
        }
        assertTrue(
            "bracket recovery should leave the graph locked or acquiring a trusted retarget; state=${solved.heightLockState} height=${solved.bodyHeightMeters} corrected=${solved.correctedHeightMeters}",
            solved.heightLockState == "locked" || solved.heightLockState == "acquiring",
        )

        solved = graph.solve(
            rawSceneMetric(
                hipDepth = Float.NaN,
                footPlane = 2.09f,
                roiDistance = 2.12f,
                height = 1.83f,
                topRayHeight = 2.04f,
                hipGeometryDistance = 2.10f,
                hipGeometryHeight = 1.84f,
                torsoHeight = 2.10f,
                pixelSpanHeight = 2.12f,
            )
        )

        assertTrue(
            "bracket recovery should leave the graph locked or acquiring a trusted retarget; state=${solved.heightLockState} height=${solved.bodyHeightMeters} corrected=${solved.correctedHeightMeters}",
            solved.heightLockState == "locked" || solved.heightLockState == "acquiring",
        )
        assertTrue("mild candidate gaps should keep exporting the acquired height", solved.correctedHeightMeters.isFinite())
        assertTrue(kotlin.math.abs(solved.correctedHeightMeters - 1.83f) < 0.06f)
        assertTrue("exported locked height should carry usable confidence", solved.heightConfidence >= 0.56f)
        assertFalse("trusted lock export should suppress stale spread diagnostics", solved.activeFactors.contains("untrusted_height_spread"))
    }

