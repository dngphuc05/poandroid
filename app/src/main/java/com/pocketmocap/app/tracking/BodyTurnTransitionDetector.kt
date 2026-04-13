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

