package com.pocketmocap.app.tracking

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalEndpointEnforcerTest {
    @Test
    fun clampsElongatedHandEndpointsFromCanonicalHeight() {
        val x = FloatArray(33)
        val y = FloatArray(33)
        val z = FloatArray(33)
        val confidence = FloatArray(33) { 0.92f }
