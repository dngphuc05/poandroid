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
