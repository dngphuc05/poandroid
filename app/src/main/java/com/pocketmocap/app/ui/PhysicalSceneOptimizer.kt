package com.pocketmocap.app.ui

import kotlin.math.abs

internal data class PhysicalSceneOptimizerInput(
    val confidence: Float,
    val rawDistanceMeters: Float,
    val rawHeightMeters: Float,
    val rawCameraHeightMeters: Float,
    val rawHipDepthDistanceMeters: Float,
    val footPlaneDistanceMeters: Float,
    val roiDistanceMeters: Float,
    val topRayHeightMeters: Float,
    val pixelSpanHeightMeters: Float,
