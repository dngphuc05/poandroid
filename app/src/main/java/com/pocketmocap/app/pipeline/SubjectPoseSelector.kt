package com.pocketmocap.app.pipeline

import kotlin.math.abs
import kotlin.math.sqrt

data class SubjectPoseCandidate(
    val index: Int,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val visibleCoreCount: Int,
)

data class SubjectPoseLock(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

data class SubjectPoseSelection(
    val index: Int,
    val lock: SubjectPoseLock,
)

object SubjectPoseSelector {
    fun select(
        candidates: List<SubjectPoseCandidate>,
        previousIndex: Int,
        lockedSubject: SubjectPoseLock?,
    ): SubjectPoseSelection? {
        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { candidate ->
            score(candidate, previousIndex, lockedSubject)
        } ?: return null

        return SubjectPoseSelection(
            index = best.index,
            lock = updateLock(best, lockedSubject),
        )
    }

    private fun score(
        candidate: SubjectPoseCandidate,
        previousIndex: Int,
        lockedSubject: SubjectPoseLock?,
    ): Float {
        val screenCenterDistance = distance(candidate.centerX, candidate.centerY, 0.5f, 0.58f)
        val visibleCoreBonus = (candidate.visibleCoreCount.coerceIn(0, 4) / 4f) * 0.35f
        var score = candidate.confidence * 1.20f + visibleCoreBonus - screenCenterDistance * 0.75f

        if (candidate.index == previousIndex) {
            score += 0.12f
        }

        if (lockedSubject != null) {
            val lockDistance = distance(candidate.centerX, candidate.centerY, lockedSubject.centerX, lockedSubject.centerY)
            val widthDelta = normalizedSizeDelta(candidate.width, lockedSubject.width)
            val heightDelta = normalizedSizeDelta(candidate.height, lockedSubject.height)
            score -= lockDistance * 2.80f
            score -= (widthDelta + heightDelta) * 0.65f
        }

        return score
    }

    private fun updateLock(candidate: SubjectPoseCandidate, previous: SubjectPoseLock?): SubjectPoseLock {
        if (previous == null) {
            return SubjectPoseLock(candidate.centerX, candidate.centerY, candidate.width, candidate.height)
        }
        val alpha = 0.22f
        return SubjectPoseLock(
            centerX = lerp(previous.centerX, candidate.centerX, alpha),
            centerY = lerp(previous.centerY, candidate.centerY, alpha),
            width = lerp(previous.width, candidate.width, alpha),
            height = lerp(previous.height, candidate.height, alpha),
        )
    }

    private fun normalizedSizeDelta(next: Float, previous: Float): Float {
        val denom = previous.coerceAtLeast(1e-4f)
        return abs(next - previous) / denom
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    private fun lerp(from: Float, to: Float, alpha: Float): Float =
        from + (to - from) * alpha.coerceIn(0f, 1f)
}
