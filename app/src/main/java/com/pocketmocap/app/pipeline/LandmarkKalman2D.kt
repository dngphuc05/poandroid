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
