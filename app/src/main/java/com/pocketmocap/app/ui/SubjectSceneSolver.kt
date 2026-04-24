package com.pocketmocap.app.ui

import com.pocketmocap.app.tracking.SceneMetricSnapshot
import kotlin.math.abs

internal enum class SceneMeasurementKind {
    Distance,
    Height,
    CameraHeight,
}

internal data class SceneMeasurement(
    val kind: SceneMeasurementKind,
    val valueMeters: Float,
    val sigmaMeters: Float,
    val confidence: Float,
    val source: String,
    val valid: Boolean,
    val timestampUs: Long = 0L,
    val debugReason: String = "",
)

internal object SceneMeasurementExtractor {
    fun extract(
        raw: SceneMetricSnapshot,
        relativeScaleDistance: Float,
        timestampUs: Long = 0L,
    ): List<SceneMeasurement> {
        val clipRisk = raw.bodyClipRisk.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
