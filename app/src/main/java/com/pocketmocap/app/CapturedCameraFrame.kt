package com.pocketmocap.app

import android.graphics.Bitmap
import com.pocketmocap.app.tracking.WorldTrackingSnapshot

/**
 * A single camera frame captured from the active camera source.
 *
 * [bitmap] is the raw upright Bitmap passed directly to MediaPipe — no JPEG decode needed.
 * [jpegBytes] is produced lazily only when something actually needs it (e.g. recording).
 */
data class CapturedCameraFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val timestampUs: Long,
    val rotationDegrees: Int = 0,
    val worldTracking: WorldTrackingSnapshot? = null,
    val jpegBytes: ByteArray = ByteArray(0),  // unused in normal capture flow
