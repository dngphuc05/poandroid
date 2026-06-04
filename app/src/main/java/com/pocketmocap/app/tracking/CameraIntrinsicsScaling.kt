package com.pocketmocap.app.tracking

import org.json.JSONObject

private const val FALLBACK_FOCAL_SCALE = 1.2f

fun cameraIntrinsicsFromJsonScaled(
    json: JSONObject?,
    targetWidth: Int,
    targetHeight: Int,
): CameraIntrinsics {
    val width = targetWidth.coerceAtLeast(1)
    val height = targetHeight.coerceAtLeast(1)
    if (json == null) {
        return fallbackIntrinsics(width, height)
    }

    val sourceWidth = json.optDouble("width", width.toDouble())
        .takeIf { it.isFinite() && it > 1.0 }
        ?: width.toDouble()
    val sourceHeight = json.optDouble("height", height.toDouble())
        .takeIf { it.isFinite() && it > 1.0 }
        ?: height.toDouble()

    val fx = json.optDouble("fx", sourceWidth * FALLBACK_FOCAL_SCALE)
    val fy = json.optDouble("fy", sourceHeight * FALLBACK_FOCAL_SCALE)
    val cx = json.optDouble("cx", sourceWidth * 0.5)
    val cy = json.optDouble("cy", sourceHeight * 0.5)

    val scaleX = width.toDouble() / sourceWidth
    val scaleY = height.toDouble() / sourceHeight
    return CameraIntrinsics(
        fx = (fx * scaleX).toFloat(),
        fy = (fy * scaleY).toFloat(),
        cx = (cx * scaleX).toFloat(),
        cy = (cy * scaleY).toFloat(),
        imageWidth = width,
        imageHeight = height,
    )
}

private fun fallbackIntrinsics(width: Int, height: Int): CameraIntrinsics =
    CameraIntrinsics(
        fx = width * FALLBACK_FOCAL_SCALE,
        fy = height * FALLBACK_FOCAL_SCALE,
        cx = width * 0.5f,
        cy = height * 0.5f,
        imageWidth = width,
        imageHeight = height,
    )
