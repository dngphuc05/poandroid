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
    val exportHeightConstraint: Boolean,
    val distanceConfidence: Float,
    val heightConfidence: Float,
    val floorConfidence: Float,
    val weightHip: Float,
    val weightHead: Float,
    val weightFoot: Float,
    val weightTorso: Float,
    val weightBone: Float,
    val weightDepth: Float,
    val weightRoi: Float,
    val weightTemporal: Float,
    val activeFactors: String,
)

internal object PhysicalSceneOptimizer {
    private const val MIN_BODY_HEIGHT_METERS = 1.15f
    private const val MAX_BODY_HEIGHT_METERS = 2.15f
    private const val MIN_HEIGHT_ENDPOINT_BIAS_METERS = -0.025f
    private const val MAX_HEIGHT_ENDPOINT_BIAS_METERS = 0.080f
    private const val MAX_HIP_RAW_TOP_STANDALONE_GAP_METERS = 0.100f
    private const val MAX_TOP_LOW_BIAS_MASK_GAP_METERS = 0.045f
    private const val MAX_ENDPOINT_BIAS_SUPPORT_GAP_METERS = 0.110f

    private const val FLAG_DISTANCE_TRUSTED = 1
    private const val FLAG_HEIGHT_TRUSTED = 2
    private const val FLAG_REJECTED_HIP = 4
    private const val FLAG_REJECTED_FOOT = 8
    private const val FACTOR_NATIVE_FALLBACK = 128
    private const val FACTOR_NATIVE_CERES = 256
    private const val FACTOR_TORSO = 512
    private const val FACTOR_GROUNDED_FOOT = 1024
    private const val FACTOR_BONE = 2048
    private const val FACTOR_RELATIVE_SCALE = 4096
    private const val FLAG_REJECTED_HIP_GEOMETRY = 16
    private const val PACKED_INPUT_VERSION = 3f

