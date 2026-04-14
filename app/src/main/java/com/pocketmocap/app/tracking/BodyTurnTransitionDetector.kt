package com.pocketmocap.app.tracking

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class BodyTurnTransitionState(
    val fastUpdateActive: Boolean,
    val reason: String,
    val framesRemaining: Int,
)

/**
 * Detects short body yaw/turn transitions from the 2D skeleton. During a turn,
 * shoulder/hip axes can collapse, flip sign, or disagree while MediaPipe swaps
 * visible-side evidence. Display smoothing needs to temporarily follow server
 * canonical joints faster, otherwise the skeleton appears to lag behind turns.
 */
class BodyTurnTransitionDetector(
    private val fastFrames: Int = 8,
    private val axisFlipMinNorm: Float = 0.035f,
    private val shoulderCollapseDxNorm: Float = 0.075f,
    private val shoulderCollapseSpanNorm: Float = 0.17f,
    private val hipCollapseDxNorm: Float = 0.055f,
    private val hipCollapseSpanNorm: Float = 0.13f,
    private val minShoulderVisibility: Float = 0.35f,
    private val minHipVisibility: Float = 0.30f,
) {
    private var previousShoulderDx = Float.NaN
    private var previousHipDx = Float.NaN
    private var fastFramesRemaining = 0

    fun reset() {
        previousShoulderDx = Float.NaN
        previousHipDx = Float.NaN
        fastFramesRemaining = 0
    }

    fun update(
        xNorm: FloatArray?,
        yNorm: FloatArray?,
        visibility: FloatArray?,
    ): BodyTurnTransitionState {
        if (xNorm == null || yNorm == null || visibility == null ||
            xNorm.size < 33 || yNorm.size < 33 || visibility.size < 33
        ) {
            return decay("insufficient_landmarks")
        }

        val shoulder = axis(xNorm, yNorm, visibility, left = 11, right = 12, minVisibility = minShoulderVisibility)
        val hip = axis(xNorm, yNorm, visibility, left = 23, right = 24, minVisibility = minHipVisibility)

        val shoulderCollapsed = shoulder.valid &&
            abs(shoulder.dx) < shoulderCollapseDxNorm &&
            shoulder.span in 0.030f..shoulderCollapseSpanNorm
        val hipCollapsed = hip.valid &&
            abs(hip.dx) < hipCollapseDxNorm &&
            hip.span in 0.025f..hipCollapseSpanNorm
        val shoulderFlipped = shoulder.valid &&
            previousShoulderDx.isFinite() &&
            abs(previousShoulderDx) >= axisFlipMinNorm &&
            abs(shoulder.dx) >= axisFlipMinNorm &&
            previousShoulderDx * shoulder.dx < 0f
        val hipFlipped = hip.valid &&
            previousHipDx.isFinite() &&
            abs(previousHipDx) >= axisFlipMinNorm &&
            abs(hip.dx) >= axisFlipMinNorm &&
            previousHipDx * hip.dx < 0f
        val shoulderHipDisagree = shoulder.valid &&
            hip.valid &&
            abs(shoulder.dx) >= axisFlipMinNorm &&
            abs(hip.dx) >= axisFlipMinNorm &&
            shoulder.dx * hip.dx < 0f

