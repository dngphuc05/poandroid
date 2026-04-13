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
