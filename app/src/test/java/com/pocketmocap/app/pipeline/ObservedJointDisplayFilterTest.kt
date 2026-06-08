package com.pocketmocap.app.pipeline

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedJointDisplayFilterTest {

    @Test
    fun visibleFastMovementKeepsUpWithinOneFrame() {
        val filter = ObservedJointDisplayFilter()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[15] = 0.20f
        filter.update(x, y, v)

        x[15] = 0.70f
        val out = filter.update(x, y, v)

        assertTrue("visible wrist should follow a fast real swing", abs(out.x[15] - 0.70f) < 0.08f)
    }

    @Test
    fun stillJointStaysStableUnderSmallNoise() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        var minRaw = Float.POSITIVE_INFINITY
        var maxRaw = Float.NEGATIVE_INFINITY
        var minOut = Float.POSITIVE_INFINITY
        var maxOut = Float.NEGATIVE_INFINITY

        repeat(30) { frame ->
            val jitter = if (frame % 2 == 0) -0.010f else 0.010f
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            x[13] = 0.50f + jitter
            minRaw = minOf(minRaw, x[13])
            maxRaw = maxOf(maxRaw, x[13])

            val out = filter.update(x, y, v)
            if (frame > 3) {
                minOut = minOf(minOut, out.x[13])
                maxOut = maxOf(maxOut, out.x[13])
            }
        }

        assertTrue("display filter should damp tiny stationary jitter", (maxOut - minOut) < (maxRaw - minRaw))
    }

    @Test
    fun isolatedShoulderJumpDuringStillTorsoIsDamped() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[11] = 0.42f
        y[11] = 0.35f
        x[12] = 0.58f
        y[12] = 0.35f
        x[23] = 0.45f
        y[23] = 0.55f
        x[24] = 0.55f
        y[24] = 0.55f
        filter.update(x, y, v)

        x[11] = 0.24f
        val out = filter.update(x, y, v)

        assertTrue("single-frame shoulder detector jump should be damped", out.x[11] > 0.34f)
        assertTrue("other shoulder should remain stable", abs(out.x[12] - 0.58f) < 0.02f)
    }

    @Test
    fun coherentTorsoMovementStillFollowsBody() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[11] = 0.42f
        x[12] = 0.58f
        x[23] = 0.45f
        x[24] = 0.55f
        filter.update(x, y, v)

        x[11] = 0.34f
        x[12] = 0.50f
        x[23] = 0.37f
        x[24] = 0.47f
        val out = filter.update(x, y, v)

        assertTrue("coherent torso shift should not be frozen", out.x[11] < 0.39f)
        assertTrue("coherent hip shift should not be frozen", out.x[23] < 0.42f)
    }

    @Test
    fun transientShoulderPairFlipIsFrozenWithoutSwappingSemantics() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[11] = 0.42f
        y[11] = 0.34f
        x[12] = 0.58f
        y[12] = 0.34f
        x[13] = 0.36f
        x[14] = 0.64f
        filter.update(x, y, v)

        x[11] = 0.58f
        x[12] = 0.42f
        x[13] = 0.60f
        x[14] = 0.40f
        val out = filter.update(x, y, v)

        assertTrue("left shoulder should keep the previous semantic side", out.x[11] < out.x[12])
        assertTrue("left shoulder should not be swapped to the right side", out.x[11] < 0.49f)
        assertTrue("arm descendants should not follow a suspicious pair flip across the torso", out.x[13] < 0.50f)
    }

    @Test
    fun realTorsoTurnWithMovingCenterIsNotFrozenAsPairFlip() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[11] = 0.42f
        y[11] = 0.34f
        x[12] = 0.58f
        y[12] = 0.34f
        filter.update(x, y, v)

        x[11] = 0.68f
        x[12] = 0.52f
        y[11] = 0.40f
        y[12] = 0.40f
        val out = filter.update(x, y, v)

        assertTrue("large coherent motion should not be treated as a one-frame detector flip", out.x[11] > 0.47f)
    }

    @Test
    fun isolatedHandEndpointJumpDuringStillArmChainIsDamped() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[11] = 0.40f
        y[11] = 0.35f
        x[13] = 0.35f
        y[13] = 0.48f
        x[15] = 0.32f
        y[15] = 0.62f
        x[19] = 0.28f
        y[19] = 0.62f
        filter.update(x, y, v)

        x[19] = 0.62f
        val out = filter.update(x, y, v)

        assertTrue("isolated hand endpoint detector jump should be damped", out.x[19] < 0.44f)
        assertTrue("elbow should remain near the stable arm chain", abs(out.x[13] - 0.35f) < 0.03f)
    }

    @Test
    fun lowerBodyNoiseIsNotPredictedPastMeasuredLegRange() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        var minOut = Float.POSITIVE_INFINITY
        var maxOut = Float.NEGATIVE_INFINITY

        repeat(36) { frame ->
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            x[27] = if (frame % 2 == 0) 0.46f else 0.54f

            val out = filter.update(x, y, v)
            if (frame > 5) {
                minOut = minOf(minOut, out.x[27])
                maxOut = maxOf(maxOut, out.x[27])
            }
        }

        assertTrue("ankle preview should not overshoot noisy measured leg range", minOut >= 0.46f)
        assertTrue("ankle preview should not overshoot noisy measured leg range", maxOut <= 0.54f)
        assertTrue("ankle preview should damp stationary leg wobble", (maxOut - minOut) < 0.06f)
    }

    @Test
    fun isolatedElbowJumpDuringStableArmChainIsDamped() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[11] = 0.42f
        y[11] = 0.34f
        x[13] = 0.34f
        y[13] = 0.50f
        x[15] = 0.30f
        y[15] = 0.64f
        filter.update(x, y, v)

        x[13] = 0.52f
        val out = filter.update(x, y, v)

        assertTrue("isolated elbow jump should not invent a new arm angle", out.x[13] < 0.42f)
        assertTrue("stable wrist should stay attached to measured evidence", abs(out.x[15] - 0.30f) < 0.03f)
    }

    @Test
    fun coherentElbowMovementStillFollowsArm() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[11] = 0.42f
        y[11] = 0.34f
        x[13] = 0.34f
        y[13] = 0.50f
        x[15] = 0.30f
        y[15] = 0.64f
        filter.update(x, y, v)

        x[11] = 0.38f
        x[13] = 0.26f
        x[15] = 0.18f
        val out = filter.update(x, y, v)

        assertTrue("coherent arm swing should not be frozen by hinge damping", out.x[13] < 0.30f)
        assertTrue("coherent wrist swing should still follow", out.x[15] < 0.23f)
    }

    @Test
    fun isolatedKneeJumpDuringStableLegChainIsDamped() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[23] = 0.44f
        y[23] = 0.52f
        x[25] = 0.42f
        y[25] = 0.66f
        x[27] = 0.40f
        y[27] = 0.80f
        filter.update(x, y, v)

        x[25] = 0.58f
        val out = filter.update(x, y, v)

        assertTrue("isolated knee jump should not invent a new leg angle", out.x[25] < 0.50f)
        assertTrue("stable ankle should stay attached to measured evidence", abs(out.x[27] - 0.40f) < 0.03f)
    }

    @Test
    fun footEndpointsDoNotStretchAwayFromAnkle() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[25] = 0.42f
        y[25] = 0.64f
        x[27] = 0.42f
        y[27] = 0.78f
        x[31] = 0.45f
        y[31] = 0.82f
        filter.update(x, y, v)

        x[31] = 0.78f
        y[31] = 0.88f
        val out = filter.update(x, y, v)

        assertTrue("toe endpoint should be capped near the ankle/shin scale", out.x[31] < 0.58f)
        assertTrue("ankle should not be pulled by bad toe evidence", abs(out.x[27] - 0.42f) < 0.03f)
    }

    @Test
    fun weakFootEndpointFlipIsPulledBackTowardLegChain() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[25] = 0.42f
        y[25] = 0.64f
        x[27] = 0.42f
        y[27] = 0.78f
        x[31] = 0.43f
        y[31] = 0.84f
        filter.update(x, y, v)

        x[31] = 0.42f
        y[31] = 0.61f
        v[31] = 0.36f
        val out = filter.update(x, y, v)

        assertTrue("weak toe endpoint should not visibly flip above the ankle", out.y[31] > 0.70f)
    }

    @Test
    fun hiddenJointIsNotInventedOrHeldVisible() {
        val filter = ObservedJointDisplayFilter()
        val visible = FloatArray(33) { 0.95f }
        val hidden = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[16] = 0.44f
        y[16] = 0.40f
        filter.update(x, y, visible)

        hidden[16] = 0.05f
        x[16] = 0.95f
        y[16] = 0.95f
        val out = filter.update(x, y, hidden)

        assertTrue("hidden elbow must not be drawn as a confident point", out.visibility[16] < 0.10f)
        assertTrue("hidden elbow must not jump to a bad hidden measurement", abs(out.x[16] - 0.95f) > 0.25f)
    }

    @Test
    fun faceMotionCanUseBoundedPredictionToReducePreviewOverlayLag() {
        val filter = ObservedJointDisplayFilter(
            stillAlpha = 1.0f,
            fastAlpha = 1.0f,
            latencyCompensationFrames = 1.0f,
            maxPredictionStep = 0.10f,
        )
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[0] = 0.20f
        filter.update(x, y, v)

        x[0] = 0.30f
        val out = filter.update(x, y, v)

        assertTrue(
            "non-limb overlay can lead the stale analyzed frame by roughly one frame",
            out.x[0] > 0.35f,
        )
        assertTrue("prediction must stay bounded", out.x[0] <= 0.40f)
    }

    @Test
    fun armAndHandEndpointDoNotPredictPastMeasuredSwing() {
        val filter = ObservedJointDisplayFilter(
            stillAlpha = 1.0f,
            fastAlpha = 1.0f,
            latencyCompensationFrames = 1.0f,
            maxPredictionStep = 0.10f,
        )
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[13] = 0.50f
        x[15] = 0.45f
        x[19] = 0.40f
        filter.update(x, y, v)

        x[13] = 0.42f
        x[15] = 0.35f
        x[19] = 0.30f
        val out = filter.update(x, y, v)

        assertTrue("elbow must not be predicted past measured motion", out.x[13] >= 0.415f)
        assertTrue("wrist must not be predicted past measured motion", out.x[15] >= 0.345f)
        assertTrue("hand endpoint must not be predicted far beyond measured motion", out.x[19] >= 0.29f)
        assertTrue("hand endpoint must stay close to measured hand evidence", abs(out.x[19] - 0.30f) < 0.03f)
    }

    @Test
    fun armDirectionReversalDoesNotOvershootOppositeSide() {
        val filter = ObservedJointDisplayFilter(
            stillAlpha = 1.0f,
            fastAlpha = 1.0f,
            latencyCompensationFrames = 1.0f,
            maxPredictionStep = 0.10f,
        )
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[13] = 0.55f
        x[15] = 0.62f
        x[19] = 0.68f
        filter.update(x, y, v)

        x[13] = 0.48f
        x[15] = 0.42f
        x[19] = 0.36f
        val out = filter.update(x, y, v)

        assertTrue("elbow should not overshoot beyond the measured reversal", out.x[13] >= 0.475f)
        assertTrue("wrist should not overshoot beyond the measured reversal", out.x[15] >= 0.415f)
        assertTrue("hand endpoint should not swing farther than the measured hand", out.x[19] >= 0.35f)
    }

    @Test
    fun weakHandEndpointIsPulledBackTowardForearmDirection() {
        val filter = ObservedJointDisplayFilter()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[13] = 0.62f
        y[13] = 0.50f
        x[15] = 0.48f
        y[15] = 0.50f
        x[19] = 0.43f
        y[19] = 0.50f
        filter.update(x, y, v)

        x[13] = 0.62f
        y[13] = 0.50f
        x[15] = 0.48f
        y[15] = 0.50f
        x[19] = 0.62f
        y[19] = 0.50f
        v[19] = 0.36f
        val out = filter.update(x, y, v)

        assertTrue("weak hand endpoint should not flip to the wrong side of the wrist", out.x[19] < 0.52f)
    }

    @Test
    fun highConfidenceHandEndpointFlipIsPulledBackForDisplay() {
        val filter = ObservedJointDisplayFilter()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[13] = 0.62f
        y[13] = 0.50f
        x[15] = 0.48f
        y[15] = 0.50f
        x[19] = 0.43f
        y[19] = 0.50f
        filter.update(x, y, v)

        x[19] = 0.55f
        val out = filter.update(x, y, v)

        assertTrue("confident hand endpoint should not visibly flip ahead of the wrist", out.x[19] < 0.52f)
    }

    @Test
    fun handEndpointWobbleIsDampedWithoutFreezingWrist() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        var minRawEndpoint = Float.POSITIVE_INFINITY
        var maxRawEndpoint = Float.NEGATIVE_INFINITY
        var minOutEndpoint = Float.POSITIVE_INFINITY
        var maxOutEndpoint = Float.NEGATIVE_INFINITY
        var finalWrist = 0f

        repeat(34) { frame ->
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            x[13] = 0.62f
            y[13] = 0.50f
            x[15] = 0.48f - frame * 0.002f
            y[15] = 0.50f
            x[19] = x[15] - if (frame % 2 == 0) 0.040f else 0.085f
            y[19] = 0.50f

            minRawEndpoint = minOf(minRawEndpoint, x[19])
            maxRawEndpoint = maxOf(maxRawEndpoint, x[19])
            val out = filter.update(x, y, v)
            if (frame > 8) {
                minOutEndpoint = minOf(minOutEndpoint, out.x[19])
                maxOutEndpoint = maxOf(maxOutEndpoint, out.x[19])
            }
            finalWrist = out.x[15]
        }

        assertTrue("hand endpoint wobble should be damped", (maxOutEndpoint - minOutEndpoint) < (maxRawEndpoint - minRawEndpoint) * 0.72f)
        assertTrue("wrist must still follow the real swing", finalWrist < 0.43f)
    }

    @Test
    fun aggressiveHandEndpointFlutterIsSuppressedWhenArmChainIsStable() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        var minRawEndpoint = Float.POSITIVE_INFINITY
        var maxRawEndpoint = Float.NEGATIVE_INFINITY
        var minOutEndpoint = Float.POSITIVE_INFINITY
        var maxOutEndpoint = Float.NEGATIVE_INFINITY

        repeat(44) { frame ->
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            x[13] = 0.62f
            y[13] = 0.50f
            x[15] = 0.48f
            y[15] = 0.50f
            x[19] = if (frame % 2 == 0) 0.39f else 0.50f
            y[19] = 0.50f

            minRawEndpoint = minOf(minRawEndpoint, x[19])
            maxRawEndpoint = maxOf(maxRawEndpoint, x[19])
            val out = filter.update(x, y, v)
            if (frame > 10) {
                minOutEndpoint = minOf(minOutEndpoint, out.x[19])
                maxOutEndpoint = maxOf(maxOutEndpoint, out.x[19])
            }
        }

        assertTrue(
            "finger endpoint flutter should be strongly reduced when elbow/wrist are stable",
            (maxOutEndpoint - minOutEndpoint) < (maxRawEndpoint - minRawEndpoint) * 0.40f,
        )
    }

    @Test
    fun realWristDrivenHandSwingStillFollowsMeasuredMotion() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        var finalEndpoint = 0f

        repeat(18) { frame ->
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            val t = frame / 17f
            x[13] = 0.60f - 0.12f * t
            y[13] = 0.50f
            x[15] = 0.48f - 0.20f * t
            y[15] = 0.50f
            x[19] = 0.42f - 0.22f * t
            y[19] = 0.50f

            finalEndpoint = filter.update(x, y, v).x[19]
        }

        assertTrue("endpoint should still follow a coherent wrist-driven swing", finalEndpoint < 0.25f)
    }

    @Test
    fun smallPersistentHandEndpointMotionStillMatchesMeasuredPose() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        var finalEndpoint = 0f

        repeat(12) { frame ->
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            val t = frame / 11f
            x[13] = 0.62f
            y[13] = 0.50f
            x[15] = 0.48f
            y[15] = 0.50f
            x[19] = 0.44f - 0.055f * t
            y[19] = 0.50f

            finalEndpoint = filter.update(x, y, v).x[19]
        }

        assertTrue("small real endpoint motion should not be over-damped", finalEndpoint < 0.405f)
    }

    @Test
    fun singleSmallHandEndpointCorrectionStillFollowsCurrentFrame() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[13] = 0.62f
        y[13] = 0.50f
        x[15] = 0.48f
        y[15] = 0.50f
        x[19] = 0.44f
        y[19] = 0.50f
        filter.update(x, y, v)

        x[19] = 0.40f
        val out = filter.update(x, y, v)

        assertTrue("small hand correction should stay close to the current frame", out.x[19] < 0.415f)
    }

    @Test
    fun fastArmSwingKeepsEndpointCloseToMeasuredEvidence() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[13] = 0.62f
        x[15] = 0.50f
        x[19] = 0.43f
        filter.update(x, y, v)

        x[13] = 0.42f
        x[15] = 0.28f
        x[19] = 0.18f
        val out = filter.update(x, y, v)

        assertTrue("fast arm swing should keep wrist close to the current frame", abs(out.x[15] - 0.28f) < 0.045f)
        assertTrue("fast arm swing should keep hand endpoint close to the current frame", abs(out.x[19] - 0.18f) < 0.055f)
    }

    @Test
    fun raisedVisibleArmStaysAttachedToMeasuredWristAndFingers() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        x[11] = 0.40f
        y[11] = 0.36f
        x[13] = 0.34f
        y[13] = 0.50f
        x[15] = 0.31f
        y[15] = 0.63f
        x[19] = 0.28f
        y[19] = 0.64f
        filter.update(x, y, v)

        x[13] = 0.30f
        y[13] = 0.24f
        x[15] = 0.24f
        y[15] = 0.14f
        x[19] = 0.21f
        y[19] = 0.11f
        val out = filter.update(x, y, v)

        assertTrue("raised elbow should not be held near the old lowered arm", abs(out.y[13] - 0.24f) < 0.06f)
        assertTrue("raised wrist should stay attached to measured hand evidence", abs(out.x[15] - 0.24f) < 0.05f)
        assertTrue("raised wrist should stay attached vertically", abs(out.y[15] - 0.14f) < 0.05f)
        assertTrue("raised finger endpoint should not be pulled toward the head/torso", abs(out.x[19] - 0.21f) < 0.06f)
        assertTrue("raised finger endpoint should remain above the wrist", out.y[19] <= out.y[15] + 0.04f)
    }

    @Test
    fun naturalElbowBendIsPreserved() {
        val filter = ObservedJointDisplayFilter()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0.95f }

        x[11] = 0.40f
        y[11] = 0.32f
        x[13] = 0.29f
        y[13] = 0.47f
        x[15] = 0.45f
        y[15] = 0.61f

        val out = filter.update(x, y, v)

        assertTrue("observed elbow bend should stay near the real MediaPipe elbow", abs(out.x[13] - 0.29f) < 0.02f)
        assertTrue("observed elbow bend should not be forced onto the shoulder-wrist line", out.x[13] < 0.34f)
    }

    @Test
    fun multipleFramesRemainFiniteAndInBounds() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }

        repeat(120) { frame ->
            val x = FloatArray(33) { 0.5f }
            val y = FloatArray(33) { 0.5f }
            x[11] = 0.40f
            y[11] = 0.34f
            x[13] = 0.35f + 0.20f * ((frame % 20) / 19f)
            y[13] = 0.45f
            x[15] = 0.46f + 0.16f * ((frame % 30) / 29f)
            y[15] = 0.62f

            val out = filter.update(x, y, v)

            listOf(11, 13, 15).forEach { index ->
                assertTrue("joint $index x must stay finite", out.x[index].isFinite())
                assertTrue("joint $index y must stay finite", out.y[index].isFinite())
                assertTrue("joint $index x must stay normalized", out.x[index] in 0f..1f)
                assertTrue("joint $index y must stay normalized", out.y[index] in 0f..1f)
            }
        }
    }

    @Test
    fun displayFilterStaysInsideRealtimeBudget() {
        val filter = ObservedJointDisplayFilter()
        val v = FloatArray(33) { 0.95f }
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }

        val started = System.nanoTime()
        repeat(1_800) { frame ->
            for (index in 0 until 33) {
                val phase = ((frame + index) % 60) / 60f
                x[index] = (0.08f + phase * 0.84f).coerceIn(0f, 1f)
                y[index] = (0.12f + ((frame * 2 + index) % 60) / 60f * 0.76f).coerceIn(0f, 1f)
            }
            filter.update(x, y, v)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val averageFrameMs = elapsedMs / 1_800.0

        assertTrue("display filtering should stay far below the 16.7ms/frame camera budget", averageFrameMs < 0.50)
    }
}
