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

    /**
     * Build constraints from collected measurements.
     * Call once after bootstrap is complete.
     */
    fun build() {
        val n = BONE_CONNECTIONS.size
        val med = FloatArray(n)
        val mn  = FloatArray(n)
        val mx  = FloatArray(n)
        var readyCount = 0
        for (b in 0 until n) {
            val m = measurements[b]
            if (m.size < MIN_SAMPLES) continue
            val sorted = m.toFloatArray().also { it.sort() }
            val median = sorted[sorted.size / 2]
            val std = run {
                var s = 0f
                for (v in sorted) s += (v - median) * (v - median)
                sqrt(s / sorted.size)
            }
            med[b] = median
            mn[b]  = maxOf(median - 2f * std, median * 0.80f)
            mx[b]  = median + 2f * std
            readyCount++
        }
        if (readyCount >= n / 2) {
            lengths    = med
            lengthsMin = mn
            lengthsMax = mx
        }
    }

    /**
     * Build if enough samples are available. No-op if already ready.
     * Allows opportunistic build during early CAPTURING frames.
     */
    fun tryBuild() {
        if (isReady) return
        val enough = measurements.count { it.size >= MIN_SAMPLES }
        if (enough >= BONE_CONNECTIONS.size / 2) build()
    }

    /**
     * Apply bone-length constraints in-place.
     * Only adjusts a joint when its partner is confident and it is not.
     * Visible joints are never moved — this is purely a recovery step.
     *
     * @param targetThreshold  visibility below which a joint is treated as occluded/needy.
     *   Use 0.20f when applied to Kalman state (strict: only truly hidden joints).
     *   Use 0.35f for output-display copy (broader safety net).
     */
    fun apply(
        xNorm: FloatArray, yNorm: FloatArray, visibility: FloatArray,
        targetThreshold: Float = 0.35f,
    ) {
        val len = lengths    ?: return
        val lMin = lengthsMin ?: return
        val lMax = lengthsMax ?: return
        val anchorConf = 0.65f   // anchor must be clearly visible

        for (b in BONE_CONNECTIONS.indices) {
            if (len[b] == 0f) continue              // no data for this bone
            val p = BONE_CONNECTIONS[b][0]
            val c = BONE_CONNECTIONS[b][1]

            val vp = visibility[p] >= anchorConf
            val vc = visibility[c] >= anchorConf

            // Only act when exactly one endpoint is a confident anchor and the other needs help
            val (anchorIdx, targetIdx) = when {
                vp && visibility[c] < targetThreshold -> p to c
                vc && visibility[p] < targetThreshold -> c to p
                else -> continue
            }

            val dx = xNorm[targetIdx] - xNorm[anchorIdx]
            val dy = yNorm[targetIdx] - yNorm[anchorIdx]
            val current = sqrt(dx * dx + dy * dy)
            if (current < MIN_LENGTH) continue

            // Clamp to [min, max]; nothing to do if already within range
            val desired = current.coerceIn(lMin[b], lMax[b])
            if (desired == current) continue

            val scale = desired / current
            xNorm[targetIdx] = xNorm[anchorIdx] + dx * scale
            yNorm[targetIdx] = yNorm[anchorIdx] + dy * scale
        }

        recoverHinges(xNorm, yNorm, visibility, len, targetThreshold, anchorConf)
    }

    private fun recoverHinges(
        xNorm: FloatArray,
        yNorm: FloatArray,
        visibility: FloatArray,
        lengths: FloatArray,
        targetThreshold: Float,
        anchorConf: Float,
    ) {
        for (triplet in HINGE_TRIPLETS) {
            val root = triplet[0]
            val middle = triplet[1]
            val end = triplet[2]
            if (visibility[root] < anchorConf || visibility[end] < anchorConf || visibility[middle] >= targetThreshold) {
                continue
            }

            val upperLength = boneLengthFor(root, middle, lengths)
            val lowerLength = boneLengthFor(middle, end, lengths)
            if (upperLength <= MIN_LENGTH || lowerLength <= MIN_LENGTH) continue

            val ax = xNorm[root]
            val ay = yNorm[root]
            val cx = xNorm[end]
            val cy = yNorm[end]
            val dcx = cx - ax
            val dcy = cy - ay
            val distance = sqrt(dcx * dcx + dcy * dcy).coerceAtLeast(MIN_LENGTH)

            val clampedDistance = distance.coerceIn(
                kotlin.math.abs(upperLength - lowerLength) + 1e-4f,
                upperLength + lowerLength - 1e-4f,
            )
            val dirX = dcx / distance
            val dirY = dcy / distance
            val a = (upperLength * upperLength - lowerLength * lowerLength + clampedDistance * clampedDistance) / (2f * clampedDistance)
            val hSq = maxOf(upperLength * upperLength - a * a, 0f)
            val h = sqrt(hSq)
            val midBaseX = ax + dirX * a
            val midBaseY = ay + dirY * a
            val perpX = -dirY
            val perpY = dirX

            val cand1X = midBaseX + perpX * h
            val cand1Y = midBaseY + perpY * h
            val cand2X = midBaseX - perpX * h
            val cand2Y = midBaseY - perpY * h

            val priorX = xNorm[middle]
            val priorY = yNorm[middle]
            val dist1 = (cand1X - priorX) * (cand1X - priorX) + (cand1Y - priorY) * (cand1Y - priorY)
            val dist2 = (cand2X - priorX) * (cand2X - priorX) + (cand2Y - priorY) * (cand2Y - priorY)
            if (dist1 <= dist2) {
                xNorm[middle] = cand1X
                yNorm[middle] = cand1Y
            } else {
                xNorm[middle] = cand2X
                yNorm[middle] = cand2Y
            }
            visibility[middle] = maxOf(visibility[middle], 0.55f)
        }
    }

    private fun boneLengthFor(a: Int, b: Int, lengths: FloatArray): Float {
        for (i in BONE_CONNECTIONS.indices) {
            val edge = BONE_CONNECTIONS[i]
            if ((edge[0] == a && edge[1] == b) || (edge[0] == b && edge[1] == a)) {
                return lengths[i]
            }
        }
        return 0f
    }

