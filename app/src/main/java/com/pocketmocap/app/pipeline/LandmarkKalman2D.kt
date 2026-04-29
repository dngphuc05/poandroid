package com.pocketmocap.app.pipeline

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight 2D Kalman filter for a single MediaPipe landmark (normalized coords).
 *
 * Two independent 1D constant-velocity Kalman filters (x and y).
 * Much cheaper than a 4D joint filter while preserving the predict-only step
 * that EMA cannot do — occluded joints are extrapolated from velocity rather
 * than held frozen at the last stale position.
 *
 * State per axis: [position, velocity]
 * Transition: p' = p + v*dt,  v' = v
 * Measurement: z = p
 *
 * Tuning knobs (normalized coord space, 0–1):
 *   qScale  — process noise scale. Higher→more responsive/jittery.
 *             Default 8e-4 ≈ "allow 0.028/frame velocity change".
 *   rNoise  — measurement noise variance. Lower→trust MediaPipe more.
 *             Default 3e-6 ≈ ~0.0017 std (~0.55px at 320 resolution).
 */
class LandmarkKalman2D(fps: Float = 60f, qScale: Float = 8e-4f, rNoise: Float = 3e-6f) {

    private val kx = KalmanFilter1D(fps, qScale, rNoise)
    private val ky = KalmanFilter1D(fps, qScale, rNoise)

    /**
     * Update with a new measurement.
     *
     * @param visible        Whether MediaPipe returned a confident landmark.
     *                       false → predict-only step (extrapolate from velocity).
     * @param maxInnovation  Fast-motion bypass threshold in normalized units.
     *                       If the measurement jumps further than this, snap immediately.
     *                       0.12 ≈ 38px at 320 — handles genuine fast limb swings.
     */
    fun update(
        x: Float, y: Float,
        visible: Boolean,
        maxInnovation: Float = 0.12f,
    ): Pair<Float, Float> =
        if (visible) {
            Pair(
                kx.update(x, trust = 1f, maxInnovation = maxInnovation),
                ky.update(y, trust = 1f, maxInnovation = maxInnovation),
            )
        } else {
            Pair(kx.predictOnly(), ky.predictOnly())
        }

    fun getState(): Pair<Float, Float> = Pair(kx.position, ky.position)

    /** Snap position without introducing velocity — use after bone-constraint anchoring. */
    fun setPosition(x: Float, y: Float) { kx.setPosition(x); ky.setPosition(y) }

    fun reset() { kx.reset(); ky.reset() }
}

// ────────────────────────────────────────────────────────────────────────────
// Scalar 1D Kalman  (constant-velocity model, state = [p, v])
// ────────────────────────────────────────────────────────────────────────────
private class KalmanFilter1D(fps: Float, qScale: Float, rNoise: Float) {

    private val dt = 1f / fps

    // Precomputed Q terms (standard CV model)
    private val Q00 = 0.25f * dt * dt * dt * dt * qScale
    private val Q01 = 0.50f * dt * dt * dt        * qScale
    private val Q11 =         dt * dt              * qScale
    private val R   = rNoise

    // State
    var position = 0f; private set
    private var velocity = 0f

    // Covariance (upper-triangle of symmetric 2×2)
    private var P00 = 1f
    private var P01 = 0f
    private var P11 = 1f

    private var initialized = false

    fun update(z: Float, trust: Float = 1f, maxInnovation: Float = 0f): Float {
        if (!initialized) {
            position = z
            initialized = true
            return position
        }

        // ── Predict ──────────────────────────────────────────────────────────
        val pPred = position + velocity * dt
        val vPred = velocity
        val P00p = P00 + 2f * dt * P01 + dt * dt * P11 + Q00
        val P01p = P01 + dt * P11 + Q01
        val P11p = P11 + Q11

        // ── Fast-motion bypass ────────────────────────────────────────────────
        // Genuine fast limb swing → snap to raw measurement; reset covariance.
        if (maxInnovation > 0f && abs(z - pPred) > maxInnovation) {
            position = z
            velocity = (z - position) / dt
            P00 = R;  P01 = 0f;  P11 = Q11
            return position
        }

        // ── Update ────────────────────────────────────────────────────────────
        val Reff = R / trust.coerceAtLeast(0.01f)   // low trust → inflate R
        val S  = P00p + Reff
        val K0 = P00p / S
        val K1 = P01p / S
        val innov = z - pPred

        position  = pPred + K0 * innov
        velocity  = vPred + K1 * innov
        P00 = (1f - K0) * P00p
        P01 = (1f - K0) * P01p
        P11 = P11p - K1 * P01p

        return position
    }

    /** Predict-only step: advances state without a measurement (joint occluded).
     *  Velocity decays each frame — prevents indefinite drift when joint stays hidden. */
    fun predictOnly(): Float {
        if (!initialized) return position
        // Advance state; velocity decays toward zero (half-life ≈ 4 frames at 60fps)
        position += velocity * dt
        velocity *= 0.85f
        // Advance covariance (grows with Q — uncertainty increases each frame)
        val P00p = P00 + 2f * dt * P01 + dt * dt * P11 + Q00
        val P01p = P01 + dt * P11 + Q01
        val P11p = P11 + Q11
        P00 = P00p;  P01 = P01p;  P11 = P11p
        return position
    }

    fun reset() {
        initialized = false
        velocity = 0f
        P00 = 1f;  P01 = 0f;  P11 = 1f
    }

    /** Snap to position with zero velocity — covariance reset to measurement noise. */
    fun setPosition(z: Float) {
        position = z
        velocity = 0f
        P00 = R;  P01 = 0f;  P11 = Q11
        initialized = true
    }
}
