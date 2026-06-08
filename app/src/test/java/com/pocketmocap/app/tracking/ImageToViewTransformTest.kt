package com.pocketmocap.app.tracking

import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageToViewTransformTest {
    @Test
    fun fallbackRotation90MapsLandscapeImageIntoPortraitViewBasis() {
        val transform = ImageToViewTransform.fallbackForRotation(90)

        val topLeft = transform.mapImageToView(0f, 0f)
        val topRight = transform.mapImageToView(1f, 0f)
        val bottomLeft = transform.mapImageToView(0f, 1f)

        assertNear(1f, topLeft.first)
        assertNear(0f, topLeft.second)
        assertNear(1f, topRight.first)
        assertNear(1f, topRight.second)
        assertNear(0f, bottomLeft.first)
        assertNear(0f, bottomLeft.second)
    }

    @Test
    fun inverseRoundTripsViewAndImageCoordinates() {
        val transform = ImageToViewTransform.fromImageCorners(
            topLeftX = 0.92f,
            topLeftY = -0.08f,
            topRightX = 0.94f,
            topRightY = 1.08f,
            bottomLeftX = 0.06f,
            bottomLeftY = -0.06f,
        )

        val view = transform.mapImageToView(0.37f, 0.61f)
        val image = transform.mapViewToImage(view.first, view.second)

        assertNear(0.37f, image.first)
        assertNear(0.61f, image.second)
    }

    @Test
    fun degenerateTransformFallsBackToInputOnInverse() {
        val transform = ImageToViewTransform(
            originX = 0f,
            originY = 0f,
            xAxisX = 1f,
            xAxisY = 1f,
            yAxisX = 1f,
            yAxisY = 1f,
        )

        val image = transform.mapViewToImage(0.25f, 0.75f)

        assertNear(0.25f, image.first)
        assertNear(0.75f, image.second)
        assertTrue(!transform.isUsable())
    }

    private fun assertNear(expected: Float, actual: Float) {
        assertTrue("expected $expected but got $actual", abs(expected - actual) < 1e-5f)
    }
}
