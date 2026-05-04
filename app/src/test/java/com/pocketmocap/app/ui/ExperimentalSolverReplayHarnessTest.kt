package com.pocketmocap.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

internal data class ReplayTruth(
    val captureId: Int,
    val expectedHeightMeters: Float?,
    val expectedDistanceRangeMeters: ClosedFloatingPointRange<Float>?,
    val stableMiddleWindow: IntRange,
    val knownGoodBaseline: Boolean = false,
    val notes: String = "",
