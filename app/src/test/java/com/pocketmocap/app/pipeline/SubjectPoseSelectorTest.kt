package com.pocketmocap.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectPoseSelectorTest {
    @Test
    fun lockedSubjectBeatsConfidentBackgroundCandidate() {
        val locked = SubjectPoseLock(centerX = 0.50f, centerY = 0.52f, width = 0.24f, height = 0.48f)
        val mainSubject = SubjectPoseCandidate(
            index = 0,
            confidence = 0.78f,
            centerX = 0.51f,
            centerY = 0.53f,
            width = 0.24f,
            height = 0.47f,
            visibleCoreCount = 4,
        )
        val backgroundSubject = SubjectPoseCandidate(
            index = 1,
            confidence = 0.96f,
            centerX = 0.78f,
            centerY = 0.70f,
            width = 0.14f,
            height = 0.26f,
            visibleCoreCount = 2,
        )

        val selection = SubjectPoseSelector.select(
            candidates = listOf(mainSubject, backgroundSubject),
            previousIndex = 0,
            lockedSubject = locked,
        )

        assertEquals(0, selection?.index)
    }

    @Test
    fun firstFramePrefersCenteredFullBodyCandidate() {
        val partialEdgePose = SubjectPoseCandidate(
            index = 0,
            confidence = 0.92f,
            centerX = 0.82f,
            centerY = 0.62f,
            width = 0.10f,
            height = 0.20f,
            visibleCoreCount = 2,
        )
        val centeredBody = SubjectPoseCandidate(
            index = 1,
            confidence = 0.82f,
            centerX = 0.50f,
            centerY = 0.56f,
            width = 0.25f,
            height = 0.50f,
            visibleCoreCount = 4,
        )

        val selection = SubjectPoseSelector.select(
            candidates = listOf(partialEdgePose, centeredBody),
            previousIndex = 0,
            lockedSubject = null,
        )

        assertEquals(1, selection?.index)
    }

    @Test
    fun selectedLockMovesGraduallyInsteadOfJumping() {
        val locked = SubjectPoseLock(centerX = 0.50f, centerY = 0.52f, width = 0.24f, height = 0.48f)
        val next = SubjectPoseCandidate(
            index = 0,
            confidence = 0.90f,
            centerX = 0.58f,
            centerY = 0.58f,
            width = 0.28f,
            height = 0.52f,
            visibleCoreCount = 4,
        )

        val selection = SubjectPoseSelector.select(
            candidates = listOf(next),
            previousIndex = 0,
            lockedSubject = locked,
        )

        assertTrue("lock should move toward the selected body", selection!!.lock.centerX > locked.centerX)
        assertTrue("lock should not snap all the way to one noisy frame", selection.lock.centerX < next.centerX)
    }
}
