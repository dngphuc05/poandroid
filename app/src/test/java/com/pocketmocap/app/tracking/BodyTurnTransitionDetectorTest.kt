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

