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
