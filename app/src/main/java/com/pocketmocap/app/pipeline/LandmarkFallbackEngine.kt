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
        // they must not be counted as real observed body bounds or trusted DLT input.
        private const val FALLBACK_VISIBILITY = 0.18f
        private const val MIN_TORSO_SCALE = 0.08f
        private const val MAX_HELD_OCCLUSION_FRAMES = 18
        private const val MIN_BODY_FRAME_SIMILARITY = 0.74f
        private const val MIN_SCALE_RATIO = 0.72f
        private const val MAX_SCALE_RATIO = 1.38f
        private val DEFAULT_BODY_FRAME = BodyFrame(
            centerX = 0.5f,
            centerY = 0.43f,
            xAxisX = 1f,
            xAxisY = 0f,
            yAxisX = 0f,
            yAxisY = -1f,
            scale = 0.18f,
            isValid = true,
        )

        // Neutral standing pose in torso-local coordinates:
        // shoulders and hips define the body frame, arms hang down, and legs drop straight below hips.
        private val DEFAULT_TEMPLATE_U = floatArrayOf(
            0.00f,
            -0.05f, -0.08f, -0.11f, 0.05f, 0.08f, 0.11f, -0.17f, 0.17f, -0.04f, 0.04f,
            -0.34f, 0.34f, -0.38f, 0.38f, -0.40f, 0.40f, -0.42f, 0.42f, -0.39f, 0.39f, -0.36f, 0.36f,
            -0.18f, 0.18f, -0.18f, 0.18f, -0.18f, 0.18f, -0.18f, 0.18f, -0.10f, 0.10f,
        )

