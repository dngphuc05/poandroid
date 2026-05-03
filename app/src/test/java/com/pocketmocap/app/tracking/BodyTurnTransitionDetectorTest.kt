package com.pocketmocap.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyTurnTransitionDetectorTest {
    @Test
    fun stableFrontFacingBodyDoesNotTriggerFastUpdate() {
        val detector = BodyTurnTransitionDetector()
        val pose = Pose2D()
        pose.setShoulders(leftX = 0.38f, rightX = 0.62f)
        pose.setHips(leftX = 0.42f, rightX = 0.58f)

        val state = detector.update(pose.x, pose.y, pose.visibility)

        assertFalse(state.fastUpdateActive)
        assertEquals("stable", state.reason)
    }

    @Test
    fun shoulderAxisFlipTriggersAndHoldsFastUpdate() {
        val detector = BodyTurnTransitionDetector(fastFrames = 4)
        val pose = Pose2D()
        pose.setShoulders(leftX = 0.38f, rightX = 0.62f)
        pose.setHips(leftX = 0.42f, rightX = 0.58f)
        detector.update(pose.x, pose.y, pose.visibility)

        pose.setShoulders(leftX = 0.63f, rightX = 0.37f)
        pose.setHips(leftX = 0.57f, rightX = 0.43f)
        val flip = detector.update(pose.x, pose.y, pose.visibility)

        assertTrue(flip.fastUpdateActive)
        assertEquals("axis_flip", flip.reason)

        pose.setShoulders(leftX = 0.64f, rightX = 0.36f)
        pose.setHips(leftX = 0.58f, rightX = 0.42f)
        val held = detector.update(pose.x, pose.y, pose.visibility)

        assertTrue(held.fastUpdateActive)
        assertTrue(held.reason.startsWith("held_"))
    }

    @Test
    fun collapsedShoulderAxisTriggersTurnFastUpdate() {
        val detector = BodyTurnTransitionDetector()
        val pose = Pose2D()
        pose.setShoulders(leftX = 0.48f, rightX = 0.52f)
        pose.setHips(leftX = 0.42f, rightX = 0.58f)

        val state = detector.update(pose.x, pose.y, pose.visibility)

        assertTrue(state.fastUpdateActive)
        assertEquals("axis_collapse", state.reason)
    }

    @Test
