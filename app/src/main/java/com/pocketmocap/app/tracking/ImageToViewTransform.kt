package com.pocketmocap.app.tracking

import kotlin.math.abs

/**
 * Affine mapping from ARCore CPU-image normalized coordinates into the Android
 * view coordinates used by the camera preview.
 *
 * ARCore already knows how it rotates/crops the camera texture for the view.
 * Keeping this as a value object prevents the overlay and server export paths
 * from inventing their own separate handedness rules.
 */
data class ImageToViewTransform(
    val originX: Float,
    val originY: Float,
    val xAxisX: Float,
    val xAxisY: Float,
    val yAxisX: Float,
    val yAxisY: Float,
) {
    fun mapImageToView(x: Float, y: Float): Pair<Float, Float> =
        Pair(
            originX + x * xAxisX + y * yAxisX,
            originY + x * xAxisY + y * yAxisY,
        )

    fun mapViewToImage(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - originX
        val dy = y - originY
        val det = xAxisX * yAxisY - yAxisX * xAxisY
        if (abs(det) < 1e-6f) return Pair(x, y)
        return Pair(
            (dx * yAxisY - yAxisX * dy) / det,
            (xAxisX * dy - dx * xAxisY) / det,
        )
    }

    fun isUsable(): Boolean =
        listOf(originX, originY, xAxisX, xAxisY, yAxisX, yAxisY).all { it.isFinite() } &&
            abs(xAxisX * yAxisY - yAxisX * xAxisY) >= 1e-6f

    companion object {
        fun identity(): ImageToViewTransform =
            ImageToViewTransform(0f, 0f, 1f, 0f, 0f, 1f)

        fun fromImageCorners(
            topLeftX: Float,
            topLeftY: Float,
            topRightX: Float,
            topRightY: Float,
            bottomLeftX: Float,
            bottomLeftY: Float,
        ): ImageToViewTransform =
            ImageToViewTransform(
                originX = topLeftX,
                originY = topLeftY,
                xAxisX = topRightX - topLeftX,
                xAxisY = topRightY - topLeftY,
                yAxisX = bottomLeftX - topLeftX,
                yAxisY = bottomLeftY - topLeftY,
            )

        fun fallbackForRotation(rotationDegrees: Int): ImageToViewTransform =
            when (((rotationDegrees % 360) + 360) % 360) {
                90 -> fromImageCorners(1f, 0f, 1f, 1f, 0f, 0f)
                180 -> fromImageCorners(1f, 1f, 0f, 1f, 1f, 0f)
                270 -> fromImageCorners(0f, 1f, 0f, 0f, 1f, 1f)
                else -> identity()
            }
    }
}
