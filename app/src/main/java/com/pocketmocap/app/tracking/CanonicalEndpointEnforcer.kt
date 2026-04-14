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

