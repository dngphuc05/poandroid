package com.pocketmocap.app.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkFallbackEngineTest {

    @Test
    fun syntheticFallbackDoesNotAdvertiseObservedVisibility() {
        val engine = LandmarkFallbackEngine()
        val x = FloatArray(33) { 0.5f }
        val y = FloatArray(33) { 0.5f }
        val v = FloatArray(33) { 0f }
        val outX = FloatArray(33)
