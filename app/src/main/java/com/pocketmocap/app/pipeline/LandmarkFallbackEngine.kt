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

        private val DEFAULT_TEMPLATE_V = floatArrayOf(
            1.06f,
            1.12f, 1.12f, 1.10f, 1.12f, 1.12f, 1.10f, 1.02f, 1.02f, 0.92f, 0.92f,
            0.50f, 0.50f, 0.04f, 0.04f, -0.50f, -0.50f, -0.62f, -0.62f, -0.60f, -0.60f, -0.56f, -0.56f,
            -0.50f, -0.50f, -1.50f, -1.50f, -2.45f, -2.45f, -2.58f, -2.58f, -2.52f, -2.52f,
        )

        private val ANCHOR_PARENT = IntArray(JOINT_COUNT) { -1 }.apply {
            this[17] = 15
            this[18] = 16
            this[19] = 15
            this[20] = 16
            this[21] = 15
            this[22] = 16
            this[29] = 27
            this[30] = 28
            this[31] = 27
            this[32] = 28
        }

        private fun midpoint(
            x: FloatArray,
            y: FloatArray,
            a: Int,
            b: Int,
        ): Pair<Float, Float> = Pair((x[a] + x[b]) * 0.5f, (y[a] + y[b]) * 0.5f)

        private fun buildBodyFrame(
            x: FloatArray,
            y: FloatArray,
            fallback: BodyFrame?,
        ): BodyFrame {
            if (x.size < JOINT_COUNT || y.size < JOINT_COUNT) {
                return fallback ?: DEFAULT_BODY_FRAME.copy(isValid = false)
            }

            val shoulderMid = midpoint(x, y, 11, 12)
            val hipMid = midpoint(x, y, 23, 24)
            val torsoDx = shoulderMid.first - hipMid.first
            val torsoDy = shoulderMid.second - hipMid.second
            val torsoLen = sqrt(torsoDx * torsoDx + torsoDy * torsoDy)

            if (torsoLen < MIN_TORSO_SCALE) {
                return fallback ?: DEFAULT_BODY_FRAME.copy(isValid = false)
            }

            val yAxisX = torsoDx / torsoLen
            val yAxisY = torsoDy / torsoLen

            var xAxisX = -yAxisY
            var xAxisY = yAxisX

            val leftSideX = (x[11] + x[23]) * 0.5f
            val leftSideY = (y[11] + y[23]) * 0.5f
            val rightSideX = (x[12] + x[24]) * 0.5f
            val rightSideY = (y[12] + y[24]) * 0.5f
            val sideDx = rightSideX - leftSideX
            val sideDy = rightSideY - leftSideY

            if (sideDx * xAxisX + sideDy * xAxisY < 0f) {
                xAxisX = -xAxisX
                xAxisY = -xAxisY
            }

            return BodyFrame(
                centerX = (shoulderMid.first + hipMid.first) * 0.5f,
                centerY = (shoulderMid.second + hipMid.second) * 0.5f,
                xAxisX = xAxisX,
                xAxisY = xAxisY,
                yAxisX = yAxisX,
                yAxisY = yAxisY,
                scale = torsoLen,
                isValid = true,
            )
        }

        private fun defaultJointPoint(index: Int): Pair<Float, Float> = Pair(
            DEFAULT_BODY_FRAME.centerX +
                (DEFAULT_TEMPLATE_U[index] * DEFAULT_BODY_FRAME.xAxisX + DEFAULT_TEMPLATE_V[index] * DEFAULT_BODY_FRAME.yAxisX) *
                DEFAULT_BODY_FRAME.scale,
            DEFAULT_BODY_FRAME.centerY +
                (DEFAULT_TEMPLATE_U[index] * DEFAULT_BODY_FRAME.xAxisY + DEFAULT_TEMPLATE_V[index] * DEFAULT_BODY_FRAME.yAxisY) *
                DEFAULT_BODY_FRAME.scale,
        )

        private fun anchorOffsetForFrame(
            frame: BodyFrame,
            jointIndex: Int,
            anchorIndex: Int,
        ): Pair<Float, Float> {
            val du = DEFAULT_TEMPLATE_U[jointIndex] - DEFAULT_TEMPLATE_U[anchorIndex]
            val dv = DEFAULT_TEMPLATE_V[jointIndex] - DEFAULT_TEMPLATE_V[anchorIndex]
            return Pair(
                (du * frame.xAxisX + dv * frame.yAxisX) * frame.scale,
                (du * frame.xAxisY + dv * frame.yAxisY) * frame.scale,
            )
        }

        private fun clampAnchorOffset(
            frame: BodyFrame,
            jointIndex: Int,
            anchorIndex: Int,
            dx: Float,
            dy: Float,
        ): Pair<Float, Float> {
            val defaultOffset = anchorOffsetForFrame(frame, jointIndex, anchorIndex)
            val defaultLen = sqrt(
                defaultOffset.first * defaultOffset.first + defaultOffset.second * defaultOffset.second
            )
            val maxLen = maxOf(defaultLen * 1.35f, frame.scale * 0.04f)
            val currentLen = sqrt(dx * dx + dy * dy)
            if (currentLen <= maxLen || currentLen <= 1e-4f) {
                return Pair(dx, dy)
            }
            val scale = maxLen / currentLen
            return Pair(dx * scale, dy * scale)
        }
    }

    private var lastBodyFrame: BodyFrame? = null
    private val lastReliableU = FloatArray(JOINT_COUNT)
    private val lastReliableV = FloatArray(JOINT_COUNT)
    private val hasReliablePose = BooleanArray(JOINT_COUNT)
    private val lastReliableFrame = arrayOfNulls<BodyFrame>(JOINT_COUNT)
    private val occludedFrameCount = IntArray(JOINT_COUNT)
    private val lastReliableAnchorDx = FloatArray(JOINT_COUNT)
    private val lastReliableAnchorDy = FloatArray(JOINT_COUNT)
    private val hasReliableAnchorOffset = BooleanArray(JOINT_COUNT)

    fun complete(
        sourceX: FloatArray,
        sourceY: FloatArray,
        sourceVisibility: FloatArray,
        outX: FloatArray,
        outY: FloatArray,
        outVisibility: FloatArray,
    ) {
        if (sourceX.size < JOINT_COUNT || sourceY.size < JOINT_COUNT || sourceVisibility.size < JOINT_COUNT) return
        if (outX.size < JOINT_COUNT || outY.size < JOINT_COUNT || outVisibility.size < JOINT_COUNT) return

