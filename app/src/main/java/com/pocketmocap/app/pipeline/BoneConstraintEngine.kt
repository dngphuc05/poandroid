package com.pocketmocap.app.pipeline

import kotlin.math.sqrt

/**
 * Phone-side bone constraint engine — mirrors the server's ConstraintEngine but
 * works in MediaPipe normalized coords (0–1) instead of pixels.
 *
 * Lifecycle:
 *   1. collectFrame()  — called on high-confidence frames during bootstrap
 *   2. build()         — called once after bootstrap completes
 *   3. apply()         — called every frame at runtime; no-ops until build() is done
 *
 * Effect: low-visibility joints whose connected neighbour IS visible are
 * repositioned to sit at the learned bone-length distance from that anchor.
 * Purely geometric — no extra allocations after startup.
 */
class BoneConstraintEngine(private val minConfidence: Float = 0.5f) {

    // 14 structural bones matching the server's BONE_CONNECTIONS
    companion object {
        private val BONE_CONNECTIONS = arrayOf(
            intArrayOf(11, 12), intArrayOf(11, 23), intArrayOf(12, 24), intArrayOf(23, 24),
            intArrayOf(11, 13), intArrayOf(13, 15), intArrayOf(12, 14), intArrayOf(14, 16),
            intArrayOf(23, 25), intArrayOf(25, 27), intArrayOf(24, 26), intArrayOf(26, 28),
            intArrayOf(0, 11),  intArrayOf(0, 12),
        )
        private val HINGE_TRIPLETS = arrayOf(
            intArrayOf(11, 13, 15), // left shoulder -> elbow -> wrist
            intArrayOf(12, 14, 16), // right shoulder -> elbow -> wrist
            intArrayOf(23, 25, 27), // left hip -> knee -> ankle
            intArrayOf(24, 26, 28), // right hip -> knee -> ankle
        )
        private const val MIN_SAMPLES = 3
        // Minimum normalized length to bother recording (avoids degenerate readings)
        private const val MIN_LENGTH = 0.005f
    }

    // Accumulate per-bone measurements during bootstrap
    private val measurements: Array<MutableList<Float>> =
        Array(BONE_CONNECTIONS.size) { mutableListOf() }

    // Built constraints (null until build() is called)
    private var lengths:    FloatArray? = null  // median length per bone
    private var lengthsMin: FloatArray? = null
    private var lengthsMax: FloatArray? = null

    val isReady: Boolean get() = lengths != null

    /**
     * Collect a single frame of normalized landmarks during bootstrap.
     * Only records bones where both endpoints have visibility > minConfidence.
     */
    fun collectFrame(xNorm: FloatArray, yNorm: FloatArray, visibility: FloatArray) {
        if (xNorm.size < 33) return
        for (b in BONE_CONNECTIONS.indices) {
            val p = BONE_CONNECTIONS[b][0]
            val c = BONE_CONNECTIONS[b][1]
            if (visibility[p] < minConfidence || visibility[c] < minConfidence) continue
            val dx = xNorm[p] - xNorm[c]
            val dy = yNorm[p] - yNorm[c]
            val len = sqrt(dx * dx + dy * dy)
            if (len > MIN_LENGTH) measurements[b].add(len)
        }
    }

