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
