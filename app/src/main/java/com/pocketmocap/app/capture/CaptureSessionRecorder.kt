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

    val isRecording: Boolean
        get() = sessionDir != null

    val currentSessionName: String?
        get() = sessionDir?.name

    @Synchronized
    fun start(): File {
        stop()
        val root = writableCapturesRoot(context)
        root.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(root, "capture_$stamp").apply { mkdirs() }
        sessionDir = dir
        frameIndex = 0

        File(dir, "metadata.txt").writeText(
            buildString {
                appendLine("Pocket Mocap capture")
                appendLine("created_at=$stamp")
                appendLine("folder=${dir.absolutePath}")
                appendLine("contents=metrics.csv,skeleton_2d_landmarks.csv,technical_3d_landmarks.csv,visual_frames.csv")
                appendLine("technical_scene_policy=server_first_ar_constrained")
                appendLine("fallback_policy=client_33pt_only_when_server_catastrophic_or_missing")
                appendLine("visual_recording_policy=optional_external_rgb_or_video_with_frame_timestamp_mapping")
            }
        )
        File(dir, "frames").mkdirs()
        metricsWriter = FileWriter(File(dir, "metrics.csv")).apply {
            appendLine(
                "frame,timestamp_ms,pipeline,visible_joints,scene_source,scene_confidence," +
                    "distance_m,height_m,camera_height_m,floor_pitch_deg,lateral_offset_m," +
                    "tracking_source,tracking_state,tracking_confidence,has_ground," +
                    "raw_camera_height_m,tracking_floor_source,floor_lock_state," +
                    "camera_x,camera_y,camera_z,ground_x,ground_y,ground_z," +
                    "technical_pose_source,server_stable_frames,accepted_pose_source," +
                    "server_pose_status,server_correction_reason,rejected_server_reason," +
                    "server_transport,frames_sent_to_server,pose3d_received_count," +
                    "last_pose3d_age_ms,last_server_joints_count,server_missing_reason," +
                    "raw_server_height_m,raw_server_distance_m," +
                    "pre_skeleton_constrained_height_m,pre_skeleton_constrained_distance_m," +
                    "constrained_server_height_m,constrained_server_distance_m," +
                    "ar_target_height_m,ar_target_distance_m," +
                    "server_scale_applied,server_root_shift_m,constraint_confidence," +
                    "ml_visual_usable,ml_dlt_metric_bad,ml_height_target_source," +
                    "ml_distance_target_source,ml_distance_hold_active,ml_dlt_weight_scale," +
                    "height_target_weight,height_prior_weight,height_smooth_weight," +
                    "height_target_admission,height_target_admission_reason,height_geometry_suspicious," +
                    "height_witness_count,height_witness_median_m,height_witness_spread_m," +
                    "height_corrected_vs_top_delta_m,height_corrected_vs_hip_delta_m," +
                    "height_corrected_vs_pixel_delta_m,height_corrected_vs_torso_delta_m," +
                    "height_target_before_gate_m,height_target_after_gate_m," +
