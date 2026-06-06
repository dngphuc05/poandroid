package com.pocketmocap.app.camera

internal class DepthFrameSampler(
    private val sampleIntervalNs: Long,
    private val reuseWindowNs: Long,
) {
    private var lastSampleRequestNs: Long = 0L
    private var lastSuccessfulSampleNs: Long = 0L

    fun shouldRequestDepth(timestampNs: Long): Boolean {
        if (timestampNs <= 0L) return false
        if (lastSampleRequestNs == 0L || timestampNs - lastSampleRequestNs >= sampleIntervalNs) {
            lastSampleRequestNs = timestampNs
            return true
        }
        return false
    }

    fun markDepthSampleSucceeded(timestampNs: Long) {
        if (timestampNs > 0L) {
            lastSuccessfulSampleNs = timestampNs
        }
    }

    fun canReuseDepth(timestampNs: Long): Boolean =
        lastSuccessfulSampleNs > 0L && timestampNs - lastSuccessfulSampleNs <= reuseWindowNs
}
