package com.pocketmocap.app.capture

import android.content.Context
import android.os.Environment
import com.pocketmocap.app.PocketMocapViewModel
import com.pocketmocap.app.tracking.METRIC_EVIDENCE_V2_OUTPUT_NAMES
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RAW_KEYPOINT_GEOMETRY_INDICES = intArrayOf(0, 7, 8, 11, 12, 23, 24, 27, 28, 29, 30, 31, 32)
private val RAW_KEYPOINT_GEOMETRY_CSV_COLUMNS = RAW_KEYPOINT_GEOMETRY_INDICES.flatMap { index ->
    listOf("kp_${index}_x_norm", "kp_${index}_y_norm", "kp_${index}_vis")
}

class CaptureSessionRecorder(private val context: Context) {
    private val metricEvidenceV2CsvColumns =
        listOf(
            "ml_image_width_px",
            "ml_image_height_px",
            "ml_image_source_width_px",
            "ml_image_source_height_px",
            "ml_image_crop_left_px",
            "ml_image_crop_top_px",
            "ml_image_crop_width_px",
            "ml_image_crop_height_px",
            "ml_image_jpeg_quality",
            "ml_image_crop_pad_ratio",
            "ml_evidence_schema",
        ) + METRIC_EVIDENCE_V2_OUTPUT_NAMES.map { "ml_$it" }

    private var sessionDir: File? = null
    private var metricsWriter: FileWriter? = null
    private var skeletonWriter: FileWriter? = null
    private var technicalWriter: FileWriter? = null
    private var visualFrameWriter: FileWriter? = null
    private var frameIndex = 0

