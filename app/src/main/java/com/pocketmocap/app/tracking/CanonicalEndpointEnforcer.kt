package com.pocketmocap.app.tracking

import kotlin.math.sqrt

data class CanonicalEndpointEnforcementResult(
    val clampedCount: Int,
    val lowTrustCount: Int,
