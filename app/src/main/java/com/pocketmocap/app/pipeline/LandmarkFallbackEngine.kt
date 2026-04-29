package com.pocketmocap.app.pipeline

import kotlin.math.sqrt

/**
 * Simple neutral-template fallback for partially-occluded MediaPipe poses.
 *
 * Workflow:
 * 1. The client runs smoothing / Kalman / simple bone constraints first.
 * 2. If some joints are still effectively missing, we fill those joints from a
 *    neutral default 33-point body template.
 * 3. The completed 33-point pose is what the UI renders and what we send to the server.
 *
 * This engine intentionally stays simple:
 * - no opposite-side mirroring
 * - no learned per-user template
 * - short torso-relative holds for major joints
 * - wrist/ankle-local holds for finger/toe detail so they stay attached
 *
 * Hidden joints first try to stay near their most recent believable local pose.
 * If the torso has turned too much or the occlusion lasts too long, we fall back
 * to the neutral droop-down template aligned to the current torso.
 */
class LandmarkFallbackEngine {

    private data class BodyFrame(
        val centerX: Float,
        val centerY: Float,
        val xAxisX: Float,
        val xAxisY: Float,
        val yAxisX: Float,
        val yAxisY: Float,
        val scale: Float,
        val isValid: Boolean,
    )

    companion object {
        private const val JOINT_COUNT = 33
        private const val OBSERVED_THRESHOLD = 0.50f
        private const val HARD_FALLBACK_THRESHOLD = 0.20f
        // Synthetic 2D fallback points are useful as a weak visual/temporal hint, but
