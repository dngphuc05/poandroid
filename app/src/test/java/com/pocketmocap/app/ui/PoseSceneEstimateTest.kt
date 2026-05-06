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

    @Test
    fun physicalSceneFactorGraphRetargetsLowLockedHeightTowardCurrentSemanticWitnesses() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.08f,
            roiDistance = 2.10f,
            height = 1.78f,
            topRayHeight = 1.79f,
            hipGeometryDistance = 2.10f,
            hipGeometryHeight = 1.78f,
            torsoHeight = 1.78f,
            pixelSpanHeight = 1.80f,
        )
        repeat(14) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.08f,
                    roiDistance = 2.10f,
                    height = 1.78f,
                    topRayHeight = 1.79f,
                    hipGeometryDistance = 2.10f,
                    hipGeometryHeight = 1.78f,
                    torsoHeight = 1.78f,
                    pixelSpanHeight = 1.80f,
                )
            )
        }
        val initialLockedHeight = solved.bodyHeightMeters

        repeat(36) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.10f,
                    roiDistance = 2.12f,
                    height = solved.bodyHeightMeters,
                    topRayHeight = 1.86f,
                    hipGeometryDistance = 2.12f,
                    hipGeometryHeight = 1.82f,
                    torsoHeight = 1.88f,
                    pixelSpanHeight = 2.18f,
                )
            )
        }

        assertTrue(
            "semantic retarget final state=${solved.heightLockState} height=${solved.bodyHeightMeters} corrected=${solved.correctedHeightMeters}",
            solved.heightLockState == "locked",
        )
        assertTrue("semantic witnesses should pull a low acquisition upward", solved.bodyHeightMeters > initialLockedHeight + 0.025f)
        assertTrue(
            "retargeted height should approach the 1.83m capture truth without overriding the acquired anchor; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters in 1.805f..1.85f,
        )
        assertTrue("retargeted export should keep usable height confidence", solved.heightConfidence >= 0.56f)
    }

    @Test
    fun physicalSceneFactorGraphAcquiresAnthropometricHeightDespiteHighSilhouette() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.18f,
            roiDistance = 2.20f,
            height = 1.88f,
            topRayHeight = 1.94f,
            hipGeometryDistance = 2.18f,
            hipGeometryHeight = 1.82f,
            torsoHeight = 1.845f,
            pixelSpanHeight = 1.98f,
        )

        repeat(14) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.18f,
                    roiDistance = 2.20f,
                    height = 1.88f,
                    topRayHeight = 1.94f,
                    hipGeometryDistance = 2.18f,
                    hipGeometryHeight = 1.82f,
                    torsoHeight = 1.845f,
                    pixelSpanHeight = 1.98f,
                )
            )
        }

        assertTrue(
            "anthropometric acquisition final state=${solved.heightLockState} height=${solved.bodyHeightMeters} corrected=${solved.correctedHeightMeters}",
            solved.heightLockState == "locked",
        )
        assertTrue("top/pixel silhouette bias should not define subject height", solved.bodyHeightMeters in 1.81f..1.85f)
        assertTrue("locked anthropometric height should export", solved.correctedHeightMeters.isFinite())
    }

    @Test
    fun physicalSceneFactorGraphAcquiresMetrics61HeightFromTopTorsoWhenHipGeometryLow() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.42f,
            roiDistance = 2.36f,
            height = 1.78f,
            topRayHeight = 1.89f,
            hipGeometryDistance = 2.34f,
            hipGeometryHeight = 1.78f,
            torsoHeight = 1.84f,
            pixelSpanHeight = 1.95f,
            groundedFootDistance = 2.42f,
            footContactState = "grounded_roi_supported",
        )
        repeat(16) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.42f,
                    roiDistance = 2.36f,
                    height = 1.78f,
                    topRayHeight = 1.89f,
                    hipGeometryDistance = 2.34f,
                    hipGeometryHeight = 1.78f + (it % 3) * 0.002f,
                    torsoHeight = 1.84f + (it % 2) * 0.006f,
                    pixelSpanHeight = 1.95f,
                    groundedFootDistance = 2.42f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue("low hip geometry should not define metrics_61 subject height by itself", solved.bodyHeightMeters in 1.81f..1.86f)
        assertTrue("corrected height should export near the 1.83m capture truth", solved.correctedHeightMeters in 1.81f..1.86f)
    }

    @Test
    fun physicalSceneFactorGraphDoesNotInventMetrics63HeightFromTopRayMargin() {
        val graph = PhysicalSceneFactorGraph(
            PhysicalSceneBias(heightEndpointBiasMeters = -0.09f)
        )
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.21f,
            roiDistance = 2.24f,
            height = 1.67f,
            topRayHeight = 1.74f,
            hipGeometryDistance = 2.22f,
            hipGeometryHeight = 1.67f,
            torsoHeight = 1.03f,
            pixelSpanHeight = 1.12f,
            groundedFootDistance = 2.21f,
            footContactState = "grounded_roi_supported",
        )
        repeat(18) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.20f + (it % 3) * 0.015f,
                    roiDistance = 2.23f + (it % 2) * 0.012f,
                    height = 1.67f,
                    topRayHeight = 1.735f + (it % 4) * 0.004f,
                    hipGeometryDistance = 2.22f,
                    hipGeometryHeight = 1.665f + (it % 3) * 0.004f,
                    torsoHeight = 1.03f,
                    pixelSpanHeight = 1.12f,
                    groundedFootDistance = 2.20f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue(
            "top-ray evidence should not receive a fixed stature margin; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters in 1.66f..1.78f,
        )
        assertTrue(
            "shorter top-supported lock should still export as a usable corrected height; actual=${solved.correctedHeightMeters}",
            solved.correctedHeightMeters in 1.66f..1.78f,
        )
        assertTrue("old negative endpoint bias should be clamped instead of pulling top-ray down", graph.currentBias.heightEndpointBiasMeters >= -0.025f)
    }

    @Test
    fun physicalSceneFactorGraphDoesNotAcquireMetrics67FromTopMarginWithoutHipWitness() {
        // metrics_67 pattern: early top-ray is close to the 1.67m subject, but
        // hip-height is absent. The old low-height rescue added a fixed head
        // margin to top-ray during first acquisition and locked near 1.79m.
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.31f,
            roiDistance = Float.NaN,
            height = 1.54f,
            topRayHeight = 1.70f,
            hipGeometryDistance = 1.90f,
            hipGeometryHeight = Float.NaN,
            torsoHeight = 1.34f,
            pixelSpanHeight = 1.52f,
            footContactState = "moving_or_uncertain",
            distance = 1.95f,
        )

        repeat(36) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.31f + (index % 3) * 0.004f,
                    roiDistance = Float.NaN,
                    height = 1.54f + (index % 4) * 0.006f,
                    topRayHeight = 1.695f + (index % 4) * 0.004f,
                    hipGeometryDistance = 1.88f + (index % 4) * 0.012f,
                    hipGeometryHeight = Float.NaN,
                    torsoHeight = 1.33f + (index % 3) * 0.006f,
                    pixelSpanHeight = 1.51f + (index % 5) * 0.008f,
                    footContactState = "moving_or_uncertain",
                    distance = 1.95f,
                )
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue(
            "initial lock must not add a fixed top-ray margin without a lower hip witness; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters < 1.74f,
        )
        assertTrue(
            "top-supported shorter subject should still export a usable lock; actual=${solved.correctedHeightMeters}",
            solved.correctedHeightMeters in 1.64f..1.74f,
        )
    }

    @Test
    fun subjectHeightEstimatorLearnsTopRayOffsetFromIndependentWitnesses() {
        val estimator = SubjectHeightEstimator()
        var retarget: Float? = null

        repeat(14) {
            retarget = estimator.update(
                topRayHeight = 1.745f + (it % 2) * 0.004f,
                hipGeometryHeight = 1.830f + (it % 3) * 0.002f,
                torsoHeight = 1.825f + (it % 2) * 0.003f,
                pixelSpanHeight = Float.NaN,
                bodyScaleConfidence = 0.84f,
                endpointBias = 0f,
                distanceTrusted = true,
                sceneConfidence = 0.84f,
                currentLocked = Float.NaN,
                lowerAnchor = Float.NaN,
            )
        }

        repeat(12) {
            retarget = estimator.update(
                topRayHeight = 1.746f + (it % 3) * 0.003f,
                hipGeometryHeight = Float.NaN,
                torsoHeight = Float.NaN,
                pixelSpanHeight = Float.NaN,
                bodyScaleConfidence = 0.84f,
                endpointBias = 0f,
                distanceTrusted = true,
                sceneConfidence = 0.84f,
                currentLocked = Float.NaN,
                lowerAnchor = Float.NaN,
            )
        }

        assertTrue(
            "stable independent hip+torso witnesses should learn a session top-ray correction; actual=$retarget",
            retarget != null && retarget!! in 1.80f..1.86f,
        )
    }

    @Test
    fun physicalSceneFactorGraphUsesStableTopTorsoConsensusAboveLowHip() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.18f,
            roiDistance = 2.20f,
            height = 1.798f,
            topRayHeight = 1.799f,
            hipGeometryDistance = 2.20f,
            hipGeometryHeight = 1.796f,
            torsoHeight = 1.800f,
            pixelSpanHeight = 1.81f,
            groundedFootDistance = 2.18f,
            footContactState = "grounded_roi_supported",
        )
        repeat(18) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.18f,
                    roiDistance = 2.20f,
                    height = 1.798f,
                    topRayHeight = 1.799f + (it % 2) * 0.002f,
                    hipGeometryDistance = 2.20f,
                    hipGeometryHeight = 1.796f + (it % 2) * 0.002f,
                    torsoHeight = 1.800f + (it % 3) * 0.002f,
                    pixelSpanHeight = 1.81f,
                    groundedFootDistance = 2.18f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }
        val initialLockedHeight = solved.bodyHeightMeters

        repeat(96) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.18f + (index % 3) * 0.006f,
                    roiDistance = 2.20f + (index % 2) * 0.006f,
                    height = 1.799f,
                    topRayHeight = 1.816f + (index % 4) * 0.002f,
                    hipGeometryDistance = 2.20f,
                    hipGeometryHeight = 1.788f + (index % 3) * 0.002f,
                    torsoHeight = 1.822f + (index % 3) * 0.002f,
                    pixelSpanHeight = 1.94f,
                    groundedFootDistance = 2.18f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue(
            "stable top+torso consensus should keep retargeting instead of stopping 2cm low: initial=$initialLockedHeight final=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters > initialLockedHeight + 0.010f,
        )
        assertTrue(
            "metrics_69-like final height should land near the upper-body consensus, not the low hip witness; actual=${solved.correctedHeightMeters}",
            solved.correctedHeightMeters in 1.807f..1.825f,
        )
    }

    @Test
    fun physicalSceneFactorGraphDelaysLowStartupLockForStableTopEnvelope() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.10f,
            roiDistance = 2.12f,
            height = 1.64f,
            topRayHeight = 1.80f,
            hipGeometryDistance = 2.12f,
            hipGeometryHeight = 1.64f,
            torsoHeight = 2.28f,
            pixelSpanHeight = 2.38f,
            groundedFootDistance = 2.10f,
            footContactState = "grounded_roi_supported",
        )

        repeat(64) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.10f + (index % 3) * 0.004f,
                    roiDistance = 2.12f + (index % 2) * 0.004f,
                    height = 1.64f + (index % 2) * 0.002f,
                    topRayHeight = 1.795f + (index % 5) * 0.003f,
                    hipGeometryDistance = 2.12f,
                    hipGeometryHeight = 1.64f + (index % 3) * 0.002f,
                    torsoHeight = 2.24f + (index % 4) * 0.012f,
                    pixelSpanHeight = 2.36f + (index % 4) * 0.018f,
                    groundedFootDistance = 2.10f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }

        assertTrue(
            "stable top envelope final state=${solved.heightLockState} height=${solved.bodyHeightMeters} corrected=${solved.correctedHeightMeters}",
            solved.heightLockState == "locked",
        )
        assertTrue(
            "stable startup top envelope should lift away from the low hip startup before the low lock hardens; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters > 1.72f,
        )
        assertTrue(
            "startup top-envelope lift should export instead of staying pinned near 1.64m; actual=${solved.correctedHeightMeters}",
            solved.correctedHeightMeters > 1.72f,
        )
    }

    @Test
    fun physicalSceneFactorGraphLiftsMetrics64ImmatureLowLockTowardTopEnvelope() {
        // metrics_64 pattern: hip-geometry persistently low (~1.745 m), top-ray
        // around the true 1.83 m band, torso/pixel noisy and low. Without the
        // SubjectHeightEstimator the lock pins at ~1.75 m because hip-geometry
        // dominates the per-frame retarget. The latent estimator should accumulate
        // top-envelope evidence and lift the immature low lock toward 1.83 m.
        val graph = PhysicalSceneFactorGraph()
        // Phase 1: clean witnesses agree closely so the optimizer trusts height.
        // The startup top-envelope gate may already lift the old hip-dominated
        // ~1.745 m acquisition, but it must not overshoot the true-height band.
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.31f,
            roiDistance = 2.30f,
            height = 1.78f,
            topRayHeight = 1.83f,
            hipGeometryDistance = 2.30f,
            hipGeometryHeight = 1.745f,
            torsoHeight = 1.77f,
            pixelSpanHeight = 1.74f,
            groundedFootDistance = 2.31f,
            footContactState = "grounded_roi_supported",
        )
        repeat(20) { iteration ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.31f,
                    roiDistance = 2.30f,
                    height = 1.78f,
                    topRayHeight = 1.83f + (iteration % 3) * 0.004f,
                    hipGeometryDistance = 2.30f,
                    hipGeometryHeight = 1.745f + (iteration % 4) * 0.002f,
                    torsoHeight = 1.77f,
                    pixelSpanHeight = 1.74f,
                    groundedFootDistance = 2.31f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }
        val initialLockedHeight = solved.bodyHeightMeters
        assertEquals("locked", solved.heightLockState)
        assertTrue(
            "phase 1 should avoid both the old 1.745m pin and high overshoot; actual=$initialLockedHeight",
            initialLockedHeight in 1.74f..1.81f,
        )

        // Phase 2: top-ray sweeps the true 1.83 m band while torso/pixel turn
        // noisy and low (the steady-state metrics_64 pattern). The optimizer
        // can no longer trust height per frame, but the rolling top-envelope
        // should accumulate sustained 1.80-1.85 m evidence and lift the lock.
        repeat(80) { iteration ->
            val topRay = 1.80f + (iteration % 5) * 0.012f
            val hip = 1.742f + (iteration % 4) * 0.003f
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.31f + (iteration % 3) * 0.01f,
                    roiDistance = 2.30f + (iteration % 2) * 0.01f,
                    height = 1.78f,
                    topRayHeight = topRay,
                    hipGeometryDistance = 2.30f,
                    hipGeometryHeight = hip,
                    torsoHeight = if (iteration % 2 == 0) 1.42f else 1.55f,
                    pixelSpanHeight = if (iteration % 3 == 0) 1.50f else 1.62f,
                    groundedFootDistance = 2.31f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue(
            "rolling top-envelope should lift the immature low lock above 1.78 m; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters > 1.78f,
        )
        assertTrue(
            "raised lock should land near the metrics_64 true band, not stay pinned at 1.75 m; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters in 1.78f..1.86f,
        )
        assertTrue(
            "lifted lock should export a corrected height; actual=${solved.correctedHeightMeters}",
            solved.correctedHeightMeters in 1.78f..1.86f,
        )
    }

    @Test
    fun physicalSceneFactorGraphQuarantinesMetrics72BadStartupHighHipLock() {
        val graph = PhysicalSceneFactorGraph(
            PhysicalSceneBias(heightEndpointBiasMeters = 0.041f)
        )
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 1.27f,
            roiDistance = 1.29f,
            height = 1.94f,
            topRayHeight = 1.83f,
            hipGeometryDistance = 1.26f,
            hipGeometryHeight = 1.95f,
            torsoHeight = 1.22f,
            pixelSpanHeight = 1.27f,
            groundedFootDistance = 1.27f,
            footContactState = "grounded_roi_supported",
            distance = 1.27f,
        ).copy(confidence = 0.48f, bodyScaleConfidence = 0.87f)

        repeat(42) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 1.25f + (index % 3) * 0.012f,
                    roiDistance = 1.28f + (index % 2) * 0.010f,
                    height = 1.94f + (index % 2) * 0.004f,
                    topRayHeight = 1.827f + (index % 4) * 0.004f,
                    hipGeometryDistance = 1.26f,
                    hipGeometryHeight = 1.948f + (index % 3) * 0.004f,
                    torsoHeight = 1.20f + (index % 5) * 0.010f,
                    pixelSpanHeight = 1.25f + (index % 4) * 0.012f,
                    groundedFootDistance = 1.25f,
                    footContactState = "grounded_roi_supported",
                    distance = 1.27f,
                ).copy(confidence = 0.48f, bodyScaleConfidence = 0.87f)
            )
            assertFalse(
                "metrics_72-style high-spread startup must not export a first height lock at frame $index",
                solved.correctedHeightMeters.isFinite(),
            )
        }

        assertTrue(
            "bad startup evidence should be diagnosed as a wide height spread; spread=${solved.heightCandidateSpreadMeters}",
            solved.heightCandidateSpreadMeters > 0.30f,
        )
        assertFalse("quarantined startup must not report a locked state", solved.heightLockState == "locked")

        repeat(48) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.18f + (index % 3) * 0.006f,
                    roiDistance = 2.20f + (index % 2) * 0.006f,
                    height = 1.83f,
                    topRayHeight = 1.824f + (index % 4) * 0.003f,
                    hipGeometryDistance = 2.19f,
                    hipGeometryHeight = 1.828f + (index % 3) * 0.003f,
                    torsoHeight = 1.818f + (index % 2) * 0.004f,
                    pixelSpanHeight = 1.835f + (index % 3) * 0.003f,
                    groundedFootDistance = 2.18f,
                    footContactState = "grounded_roi_supported",
                    distance = 2.19f,
                ).copy(confidence = 0.66f, bodyScaleConfidence = 0.88f)
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue(
            "after the bad startup clears, stable evidence should lock near true height; actual=${solved.correctedHeightMeters}",
            solved.correctedHeightMeters in 1.79f..1.87f,
        )
    }

    @Test
    fun physicalSceneFactorGraphCorrectsBadStartupLockWhenTopContradictsUnsupportedHip() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.12f,
            roiDistance = 2.14f,
            height = 1.94f,
            topRayHeight = 1.94f,
            hipGeometryDistance = 2.12f,
            hipGeometryHeight = 1.94f,
            torsoHeight = 1.94f,
            pixelSpanHeight = 1.94f,
            groundedFootDistance = 2.12f,
            footContactState = "grounded_roi_supported",
            distance = 2.12f,
        )

        repeat(10) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.12f,
                    roiDistance = 2.14f,
                    height = 1.94f,
                    topRayHeight = 1.94f,
                    hipGeometryDistance = 2.12f,
                    hipGeometryHeight = 1.94f,
                    torsoHeight = 1.94f,
                    pixelSpanHeight = 1.94f,
                    groundedFootDistance = 2.12f,
                    footContactState = "grounded_roi_supported",
                    distance = 2.12f,
                ).copy(confidence = 0.72f, bodyScaleConfidence = 0.90f)
            )
        }
        val badStartupLock = solved.bodyHeightMeters
        assertTrue("test setup should export a high startup lock", solved.correctedHeightMeters.isFinite())
        assertTrue("test setup should create a high startup lock", badStartupLock > 1.91f)

        var correctionSuppressedExport = false
        repeat(12) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.12f + (index % 2) * 0.006f,
                    roiDistance = 2.14f + (index % 3) * 0.006f,
                    height = 1.94f,
                    topRayHeight = 1.828f + (index % 3) * 0.004f,
                    hipGeometryDistance = 2.12f,
                    hipGeometryHeight = 1.946f + (index % 3) * 0.004f,
                    torsoHeight = 1.22f + (index % 4) * 0.012f,
                    pixelSpanHeight = 1.26f + (index % 5) * 0.010f,
                    groundedFootDistance = 2.12f,
                    footContactState = "grounded_roi_supported",
                    distance = 2.12f,
                ).copy(confidence = 0.64f, bodyScaleConfidence = 0.88f)
            )
            correctionSuppressedExport = correctionSuppressedExport || !solved.correctedHeightMeters.isFinite()
        }

        assertTrue("startup correction should suppress export while undoing the bad lock", correctionSuppressedExport)
        assertTrue(
            "bad startup lock should be corrected quickly from unsupported high hip evidence; initial=$badStartupLock final=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters < badStartupLock - 0.055f,
        )
        assertTrue(
            "correction should move toward the top witness instead of staying around 1.91m; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters < 1.89f,
        )
    }

    @Test
    fun physicalSceneFactorGraphRecoversMetrics73LowLockFromBracketedSpanEvidence() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 1.88f,
            roiDistance = 1.88f,
            height = 1.49f,
            topRayHeight = 1.56f,
            hipGeometryDistance = 0.52f,
            hipGeometryHeight = 1.49f,
            torsoHeight = Float.NaN,
            pixelSpanHeight = Float.NaN,
            groundedFootDistance = 1.88f,
            footContactState = "grounded_roi_supported",
            distance = 1.88f,
        )

        repeat(28) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 1.88f + (index % 3) * 0.002f,
                    roiDistance = 1.88f + (index % 2) * 0.003f,
                    height = 1.49f,
                    topRayHeight = 1.56f + (index % 4) * 0.004f,
                    hipGeometryDistance = 0.52f,
                    hipGeometryHeight = 1.49f,
                    torsoHeight = Float.NaN,
                    pixelSpanHeight = Float.NaN,
                    groundedFootDistance = 1.88f,
                    footContactState = "grounded_roi_supported",
                    distance = 1.88f,
                ).copy(confidence = 0.56f, bodyScaleConfidence = 0.88f)
            )
        }
        val lowLock = solved.bodyHeightMeters
        assertTrue("test setup should reproduce the metrics_73 low lock; actual=$lowLock", lowLock in 1.45f..1.56f)

        repeat(120) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 1.88f + (index % 3) * 0.002f,
                    roiDistance = 1.88f + (index % 2) * 0.003f,
                    height = 1.49f,
                    topRayHeight = 1.59f + (index % 5) * 0.003f,
                    hipGeometryDistance = 0.52f,
                    hipGeometryHeight = 1.497f,
                    torsoHeight = 2.04f + (index % 7) * 0.010f,
                    pixelSpanHeight = 2.15f + (index % 6) * 0.010f,
                    groundedFootDistance = 1.88f,
                    footContactState = "grounded_roi_supported",
                    distance = 1.88f,
                ).copy(confidence = 0.56f, bodyScaleConfidence = 0.89f)
            )
        }

        assertTrue(
            "bracket recovery should leave the graph locked or acquiring a trusted retarget; state=${solved.heightLockState} height=${solved.bodyHeightMeters} corrected=${solved.correctedHeightMeters}",
            solved.heightLockState == "locked" || solved.heightLockState == "acquiring",
        )
        assertTrue(
            "bracketed span evidence should lift the low metrics_73 lock instead of staying pinned: initial=$lowLock final=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters > lowLock + 0.060f,
        )
        assertTrue(
            "bracket recovery should remain inside plausible subject-height bounds; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters in 1.60f..1.92f,
        )
    }

    @Test
    fun physicalSceneFactorGraphQuarantinesTallTopEnvelopeWhenTorsoPixelCollapse() {
        // metrics_65 pattern: the true/tall top-ray witness is stable, but
        // hip-height is absent and torso/pixel collapse low. The quarantine
        // must not export a first lock from top-only evidence while the
        // optimizer is reporting a huge height-candidate spread.
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.28f,
            roiDistance = Float.NaN,
            height = 1.72f,
            topRayHeight = 1.86f,
            hipGeometryDistance = 1.20f,
            hipGeometryHeight = Float.NaN,
            torsoHeight = 1.05f,
            pixelSpanHeight = 1.20f,
            footContactState = "moving_or_uncertain",
            distance = 2.82f,
        )

        repeat(72) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.28f + (index % 5) * 0.004f,
                    roiDistance = Float.NaN,
                    height = 1.70f + (index % 4) * 0.006f,
                    topRayHeight = 1.84f + (index % 6) * 0.010f,
                    hipGeometryDistance = 1.18f + (index % 4) * 0.020f,
                    hipGeometryHeight = Float.NaN,
                    torsoHeight = 1.00f + (index % 7) * 0.025f,
                    pixelSpanHeight = 1.18f + (index % 5) * 0.018f,
                    footContactState = "moving_or_uncertain",
                    distance = 2.82f,
                )
            )
        }

        assertEquals("acquiring", solved.heightLockState)
        assertTrue(
            "stable tall top-envelope may surface diagnostically while quarantined; actual=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters in 1.80f..1.90f,
        )
        assertFalse(
            "top-only startup with collapsed torso/pixel must not export a first height constraint",
            solved.correctedHeightMeters.isFinite(),
        )
    }

    @Test
    fun physicalSceneFactorGraphKeepsTrueHeightLockedThroughLimbSilhouetteNoise() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.12f,
            roiDistance = 2.14f,
            height = 1.83f,
            topRayHeight = 1.84f,
            hipGeometryDistance = 2.12f,
            hipGeometryHeight = 1.82f,
            torsoHeight = 1.83f,
            pixelSpanHeight = 1.84f,
        )
        repeat(14) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.12f,
                    roiDistance = 2.14f,
                    height = 1.83f,
                    topRayHeight = 1.84f,
                    hipGeometryDistance = 2.12f,
                    hipGeometryHeight = 1.82f,
                    torsoHeight = 1.83f,
                    pixelSpanHeight = 1.84f,
                )
            )
        }
        val lockedHeight = solved.bodyHeightMeters

        val heights = mutableListOf<Float>()
        repeat(48) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.08f + (index % 5) * 0.03f,
                    roiDistance = 2.16f + (index % 3) * 0.02f,
                    height = 1.90f,
                    topRayHeight = 1.90f + (index % 4) * 0.035f,
                    hipGeometryDistance = 2.12f,
                    hipGeometryHeight = 1.82f + (index % 3) * 0.006f,
                    torsoHeight = if (index % 2 == 0) 1.28f else 1.86f,
                    pixelSpanHeight = if (index % 2 == 0) 1.14f else 2.12f,
                )
            )
            heights += solved.bodyHeightMeters
        }

        val heightSpan = heights.maxOrNull()!! - heights.minOrNull()!!
        assertEquals("locked", solved.heightLockState)
        assertTrue("limb motion should keep exporting locked subject height", solved.correctedHeightMeters.isFinite())
        assertTrue(
            "human height should not chase hand/leg silhouette noise: locked=$lockedHeight final=${solved.bodyHeightMeters} span=$heightSpan",
            heightSpan < 0.035f,
        )
        assertTrue(
            "locked height should remain near the acquired anthropometric value: locked=$lockedHeight final=${solved.bodyHeightMeters}",
            kotlin.math.abs(solved.bodyHeightMeters - lockedHeight) < 0.035f,
        )
    }

    @Test
    fun physicalSceneFactorGraphDoesNotRaiseFreshMetrics60LockForHighHipGeometryRun() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.00f,
            roiDistance = 2.02f,
            height = 1.84f,
            topRayHeight = 1.95f,
            hipGeometryDistance = 2.02f,
            hipGeometryHeight = 1.84f,
            torsoHeight = 1.78f,
            pixelSpanHeight = 1.91f,
        )
        repeat(18) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.00f,
                    roiDistance = 2.02f,
                    height = 1.84f,
                    topRayHeight = 1.95f,
                    hipGeometryDistance = 2.02f,
                    hipGeometryHeight = 1.84f + (it % 3) * 0.004f,
                    torsoHeight = 1.78f,
                    pixelSpanHeight = 1.91f,
                )
            )
        }
        val lockedHeight = solved.bodyHeightMeters

        repeat(72) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.58f,
                    roiDistance = 2.56f,
                    height = 1.91f,
                    topRayHeight = 2.06f,
                    hipGeometryDistance = 2.56f,
                    hipGeometryHeight = 1.90f + (it % 4) * 0.004f,
                    torsoHeight = if (it < 8) 2.08f else Float.NaN,
                    pixelSpanHeight = Float.NaN,
                )
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue(
            "good metrics_60 startup lock should not ratchet upward from later high hip geometry: locked=$lockedHeight final=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters < lockedHeight + 0.032f,
        )
        assertTrue("fresh lock should keep exporting while high geometry is constrained", solved.correctedHeightMeters.isFinite())
    }

    @Test
    fun physicalSceneFactorGraphDoesNotLetMetrics71HighHipTeachEndpointBias() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.22f,
            roiDistance = 2.30f,
            height = 1.84f,
            topRayHeight = 1.82f,
            hipGeometryDistance = 2.20f,
            hipGeometryHeight = 1.84f,
            torsoHeight = 1.82f,
            pixelSpanHeight = 1.84f,
            groundedFootDistance = 2.22f,
            footContactState = "grounded_roi_supported",
        )
        repeat(24) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.22f,
                    roiDistance = 2.30f,
                    height = 1.84f,
                    topRayHeight = 1.82f + (it % 3) * 0.004f,
                    hipGeometryDistance = 2.20f,
                    hipGeometryHeight = 1.84f + (it % 2) * 0.003f,
                    torsoHeight = 1.82f,
                    pixelSpanHeight = 1.84f,
                    groundedFootDistance = 2.22f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }
        val lockedHeight = solved.bodyHeightMeters

        repeat(260) { index ->
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.24f,
                    roiDistance = 2.38f,
                    height = 1.90f,
                    topRayHeight = 1.78f + (index % 9) * 0.008f,
                    hipGeometryDistance = 2.26f,
                    hipGeometryHeight = 1.895f + (index % 4) * 0.004f,
                    torsoHeight = if (index % 5 == 0) 1.36f else Float.NaN,
                    pixelSpanHeight = if (index % 4 == 0) 1.43f else Float.NaN,
                    groundedFootDistance = 2.24f,
                    footContactState = "grounded_roi_supported",
                )
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue(
            "metrics_71 pattern should not let high hip geometry plus learned endpoint bias ratchet the lock: locked=$lockedHeight final=${solved.bodyHeightMeters}",
            solved.bodyHeightMeters < lockedHeight + 0.040f,
        )
        assertTrue(
            "unsupported positive endpoint bias should decay instead of masking the low raw top-ray; bias=${graph.currentBias.heightEndpointBiasMeters}",
            graph.currentBias.heightEndpointBiasMeters < 0.035f,
        )
        assertTrue("held height should remain exportable", solved.correctedHeightMeters.isFinite())
    }

    @Test
    fun physicalSceneFactorGraphDoesNotRaiseMatureHeightForHighHipGeometryRun() {
        val graph = PhysicalSceneFactorGraph()
        var solved = rawSceneMetric(
            hipDepth = Float.NaN,
            footPlane = 2.10f,
            roiDistance = 2.12f,
            height = 1.83f,
            topRayHeight = 1.84f,
            hipGeometryDistance = 2.10f,
            hipGeometryHeight = 1.82f,
            torsoHeight = 1.83f,
            pixelSpanHeight = 1.84f,
        )
        repeat(70) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.10f,
                    roiDistance = 2.12f,
                    height = 1.83f,
                    topRayHeight = 1.84f,
                    hipGeometryDistance = 2.10f,
                    hipGeometryHeight = 1.82f,
                    torsoHeight = 1.83f,
                    pixelSpanHeight = 1.84f,
                )
            )
        }
        val lockedHeight = solved.bodyHeightMeters

        repeat(80) {
            solved = graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 2.38f,
                    roiDistance = 2.36f,
                    height = 1.91f,
                    topRayHeight = 2.00f,
                    hipGeometryDistance = 2.28f,
                    hipGeometryHeight = 1.90f,
                    torsoHeight = 1.93f,
                    pixelSpanHeight = 2.18f,
                )
            )
        }

        assertEquals("locked", solved.heightLockState)
        assertTrue("mature subject height should not climb to a high hip/top silhouette run", solved.bodyHeightMeters < lockedHeight + 0.030f)
        assertTrue("high run should still keep exporting the held subject height", solved.correctedHeightMeters.isFinite())
    }

    @Test
    fun physicalSceneFactorGraphAllowsDistanceOnlyRelativeScaleWithoutHeightConstraint() {
        val graph = PhysicalSceneFactorGraph()
        repeat(8) {
            graph.solve(
                rawSceneMetric(
                    hipDepth = Float.NaN,
                    footPlane = 3.20f,
                    roiDistance = 3.15f,
                    height = 2.10f,
                    topRayHeight = 2.00f,
                    hipGeometryHeight = 2.20f,
                    torsoHeight = 1.80f,
                    torsoSpanNorm = 0.10f,
                    pixelSpanHeight = 2.55f,
                )
            )
        }

        val solved = graph.solve(
            rawSceneMetric(
                hipDepth = Float.NaN,
                footPlane = Float.NaN,
                roiDistance = Float.NaN,
                height = 2.10f,
                topRayHeight = 2.00f,
                hipGeometryHeight = 2.20f,
                torsoHeight = 1.80f,
                torsoSpanNorm = 0.20f,
                pixelSpanHeight = 2.55f,
            )
        )

