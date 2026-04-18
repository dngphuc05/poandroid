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
    val hipGeometryDistanceMeters: Float = Float.NaN,
    val hipGeometryHeightMeters: Float = Float.NaN,
    val torsoHeightMeters: Float = Float.NaN,
    val groundedFootDistanceMeters: Float = Float.NaN,
    val bodyScaleConfidence: Float = Float.NaN,
    val boneLengthSpreadMeters: Float = Float.NaN,
    val relativeScaleDistanceMeters: Float = Float.NaN,
    val previousDistanceMeters: Float,
    val previousHeightMeters: Float,
    val floorHeightBiasMeters: Float,
    val depthScale: Float,
    val depthOffsetMeters: Float,
    val heightEndpointBiasMeters: Float,
)

internal data class PhysicalSceneOptimizerResult(
    val distanceMeters: Float,
    val heightMeters: Float,
    val cameraHeightMeters: Float,
    val solverConfidence: Float,
    val solverResidualMeters: Float,
    val floorHeightBiasMeters: Float,
    val depthScale: Float,
    val depthOffsetMeters: Float,
    val heightEndpointBiasMeters: Float,
    val distanceCandidateSpreadMeters: Float,
    val heightCandidateSpreadMeters: Float,
    val distanceTrusted: Boolean,
    val heightTrusted: Boolean,
