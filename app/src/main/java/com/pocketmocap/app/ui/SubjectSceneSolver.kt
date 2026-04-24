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
