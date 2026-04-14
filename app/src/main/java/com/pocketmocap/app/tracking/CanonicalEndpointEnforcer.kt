package com.pocketmocap.app.tracking

import kotlin.math.sqrt

data class CanonicalEndpointEnforcementResult(
    val clampedCount: Int,
    val lowTrustCount: Int,
)

private data class EndpointSpec(
    val parent: Int,
    val child: Int,
    val lengthRatio: Float,
    val minLengthMeters: Float,
    val maxLengthMeters: Float,
    val clampLowRatio: Float = 0.78f,
    val clampHighRatio: Float = 1.12f,
    val trustLowRatio: Float = 0.45f,
    val trustHighRatio: Float = 1.45f,
    val lowTrustConfidence: Float = 0.46f,
)

private val CANONICAL_LIMB_SPECS = arrayOf(
    EndpointSpec(parent = 11, child = 13, lengthRatio = 0.185f, minLengthMeters = 0.20f, maxLengthMeters = 0.43f, clampLowRatio = 0.70f, clampHighRatio = 1.18f, trustLowRatio = 0.58f, trustHighRatio = 1.34f, lowTrustConfidence = 0.58f),
    EndpointSpec(parent = 13, child = 15, lengthRatio = 0.160f, minLengthMeters = 0.18f, maxLengthMeters = 0.38f, clampLowRatio = 0.70f, clampHighRatio = 1.18f, trustLowRatio = 0.58f, trustHighRatio = 1.34f, lowTrustConfidence = 0.56f),
    EndpointSpec(parent = 12, child = 14, lengthRatio = 0.185f, minLengthMeters = 0.20f, maxLengthMeters = 0.43f, clampLowRatio = 0.70f, clampHighRatio = 1.18f, trustLowRatio = 0.58f, trustHighRatio = 1.34f, lowTrustConfidence = 0.58f),
    EndpointSpec(parent = 14, child = 16, lengthRatio = 0.160f, minLengthMeters = 0.18f, maxLengthMeters = 0.38f, clampLowRatio = 0.70f, clampHighRatio = 1.18f, trustLowRatio = 0.58f, trustHighRatio = 1.34f, lowTrustConfidence = 0.56f),
    EndpointSpec(parent = 23, child = 25, lengthRatio = 0.245f, minLengthMeters = 0.30f, maxLengthMeters = 0.62f, clampLowRatio = 0.72f, clampHighRatio = 1.16f, trustLowRatio = 0.60f, trustHighRatio = 1.30f, lowTrustConfidence = 0.58f),
    EndpointSpec(parent = 25, child = 27, lengthRatio = 0.246f, minLengthMeters = 0.30f, maxLengthMeters = 0.62f, clampLowRatio = 0.72f, clampHighRatio = 1.16f, trustLowRatio = 0.60f, trustHighRatio = 1.30f, lowTrustConfidence = 0.58f),
    EndpointSpec(parent = 24, child = 26, lengthRatio = 0.245f, minLengthMeters = 0.30f, maxLengthMeters = 0.62f, clampLowRatio = 0.72f, clampHighRatio = 1.16f, trustLowRatio = 0.60f, trustHighRatio = 1.30f, lowTrustConfidence = 0.58f),
    EndpointSpec(parent = 26, child = 28, lengthRatio = 0.246f, minLengthMeters = 0.30f, maxLengthMeters = 0.62f, clampLowRatio = 0.72f, clampHighRatio = 1.16f, trustLowRatio = 0.60f, trustHighRatio = 1.30f, lowTrustConfidence = 0.58f),
    EndpointSpec(parent = 27, child = 29, lengthRatio = 0.105f, minLengthMeters = 0.10f, maxLengthMeters = 0.25f, clampLowRatio = 0.70f, clampHighRatio = 1.22f, trustLowRatio = 0.50f, trustHighRatio = 1.45f, lowTrustConfidence = 0.50f),
    EndpointSpec(parent = 27, child = 31, lengthRatio = 0.105f, minLengthMeters = 0.10f, maxLengthMeters = 0.25f, clampLowRatio = 0.70f, clampHighRatio = 1.22f, trustLowRatio = 0.50f, trustHighRatio = 1.45f, lowTrustConfidence = 0.50f),
    EndpointSpec(parent = 28, child = 30, lengthRatio = 0.105f, minLengthMeters = 0.10f, maxLengthMeters = 0.25f, clampLowRatio = 0.70f, clampHighRatio = 1.22f, trustLowRatio = 0.50f, trustHighRatio = 1.45f, lowTrustConfidence = 0.50f),
    EndpointSpec(parent = 28, child = 32, lengthRatio = 0.105f, minLengthMeters = 0.10f, maxLengthMeters = 0.25f, clampLowRatio = 0.70f, clampHighRatio = 1.22f, trustLowRatio = 0.50f, trustHighRatio = 1.45f, lowTrustConfidence = 0.50f),
    EndpointSpec(parent = 15, child = 17, lengthRatio = 0.050f, minLengthMeters = 0.060f, maxLengthMeters = 0.118f),
    EndpointSpec(parent = 15, child = 19, lengthRatio = 0.050f, minLengthMeters = 0.060f, maxLengthMeters = 0.118f),
    EndpointSpec(parent = 15, child = 21, lengthRatio = 0.050f, minLengthMeters = 0.060f, maxLengthMeters = 0.118f),
    EndpointSpec(parent = 16, child = 18, lengthRatio = 0.050f, minLengthMeters = 0.060f, maxLengthMeters = 0.118f),
    EndpointSpec(parent = 16, child = 20, lengthRatio = 0.050f, minLengthMeters = 0.060f, maxLengthMeters = 0.118f),
    EndpointSpec(parent = 16, child = 22, lengthRatio = 0.050f, minLengthMeters = 0.060f, maxLengthMeters = 0.118f),
)

private val HAND_ENDPOINT_SPECS = CANONICAL_LIMB_SPECS.filter {
    it.parent == 15 || it.parent == 16
}.toTypedArray()

/**
 * Keeps canonical display limb and endpoint bones from stretching after per-joint
 * smoothing. This does not estimate metric scale; it only enforces endpoint
 * lengths from the already-authoritative canonical body height.
 */
fun enforceCanonicalLimbEndpoints(
    x: FloatArray,
    y: FloatArray,
    z: FloatArray,
    confidence: FloatArray?,
    targetHeightMeters: Float,
): CanonicalEndpointEnforcementResult = enforceCanonicalSpecs(
    specs = CANONICAL_LIMB_SPECS,
    x = x,
    y = y,
    z = z,
    confidence = confidence,
    targetHeightMeters = targetHeightMeters,
)

fun enforceCanonicalHandEndpoints(
    x: FloatArray,
    y: FloatArray,
    z: FloatArray,
    confidence: FloatArray?,
    targetHeightMeters: Float,
): CanonicalEndpointEnforcementResult = enforceCanonicalSpecs(
    specs = HAND_ENDPOINT_SPECS,
    x = x,
    y = y,
    z = z,
    confidence = confidence,
    targetHeightMeters = targetHeightMeters,
)

