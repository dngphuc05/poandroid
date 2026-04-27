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
                    "height_memory_update,height_memory_reason,height_memory_age_frames,height_memory_target_m," +
                    "wls_height_m,post_stable_skeleton_height_m,final_smoothed_height_m,height_loss_stage," +
                    "distance_target_weight,distance_prior_weight,distance_smooth_weight," +
                    "distance_local_authority," +
                    "distance_target_admission,distance_target_admission_reason,distance_geometry_suspicious," +
                    "distance_witness_count,distance_witness_median_m,distance_witness_spread_m," +
                    "distance_vs_previous_target_delta_m,distance_foot_roi_disagreement_m," +
                    "distance_foot_relative_disagreement_m,distance_corrected_vs_foot_delta_m," +
                    "distance_target_before_gate_m,distance_target_after_gate_m," +
                    "distance_memory_update,distance_memory_reason,distance_memory_age_frames,distance_memory_target_m," +
                    "factor_graph_active,factor_graph_status,factor_graph_cost_before,factor_graph_cost_after," +
                    "factor_graph_scale_delta,factor_graph_yaw_deg,factor_graph_root_dx_m,factor_graph_root_dz_m," +
                    "factor_graph_left_foot_dz_m,factor_graph_right_foot_dz_m," +
                    "factor_graph_target_distance_m,factor_graph_target_height_m,factor_graph_factor_summary," +
                    "metric_pose_status,metric_pose_reject_reason,metric_pose_frame," +
                    "metric_root_x_m,metric_root_y_m,metric_root_z_m,metric_root_distance_m," +
                    "metric_foot_midpoint_x_m,metric_foot_midpoint_z_m,metric_body_height_m," +
                    "metric_body_scale_locked,metric_bone_scale_source," +
                    "metric_pose_jitter_scale_m,metric_pose_jitter_root_m," +
                    "pose_joints_frame,pose_joints_normalized,pose_joints_normalization_scale," +
                    "pose_lifter_status,pose_lifter_model_path,pose_lifter_hidden_joint_count," +
                    "pose_lifter_mean_confidence,pose_lifter_applied_joint_count,pose_lifter_reject_reason," +
                    "client_motion_overlay_active," +
                    "stable_skeleton_learning_enabled,stable_skeleton_reset_reason," +
                    "server_scene_metrics_received,server_scene_metrics_accepted," +
                    "server_scene_metrics_source,server_scene_metrics_floor_source," +
                    "server_scene_metrics_filter_reason," +
                    "server_scene_metrics_confidence," +
                    "ml_evidence_status,ml_evidence_height_sigma_m,ml_evidence_distance_sigma_m," +
                    "ml_evidence_mask_endpoint_confidence,ml_evidence_visible_body_fraction," +
                    "ml_evidence_foot_contact_probability,ml_evidence_image_status,ml_evidence_debug," +
                    metricEvidenceV2CsvColumns.joinToString(",") + "," +
                    "corrected_distance_m,corrected_height_m,corrected_camera_height_m," +
                    "local_height_candidate_m,local_height_candidate_confidence,local_height_candidate_source," +
                    "profile_subject_height_m,profile_subject_height_confidence,profile_subject_height_source," +
                    "floor_source,solver_confidence,solver_residual_m," +
                    "floor_height_bias_m,depth_scale,depth_offset_m,height_endpoint_bias_m," +
                    "height_lock_state,distance_candidate_spread_m,height_candidate_spread_m," +
                    "raw_hip_depth_distance_m,foot_plane_distance_m,roi_distance_m," +
                    "left_foot_ray_floor_distance_m,right_foot_ray_floor_distance_m," +
                    "feet_midpoint_floor_distance_m,nearest_foot_floor_distance_m,foot_ray_floor_spread_m," +
                    "top_ray_height_m,top_ray_floor_height_m,visual_top_scan_y_norm," +
                    "visual_top_scan_confidence,visual_top_lift_norm,pixel_span_height_m," +
                    "root_hip_ray_floor_distance_m,distance_height_geometry_residual_m,active_factors," +
                    "hip_geometry_distance_m,hip_geometry_height_m,torso_height_m,torso_residual_m," +
                    "grounded_foot_distance_m,foot_contact_state,bone_length_spread_m,body_scale_confidence," +
                    "distance_confidence,height_confidence,floor_confidence,distance_state," +
                    "w_hip,w_head,w_foot,w_torso,w_bone,w_depth,w_roi,w_temporal," +
                    "hip_center_x_norm,hip_center_y_norm,shoulder_hip_span_norm,torso_span_norm," +
                    "body_roi_height_norm,relative_scale_distance_m,rejected_hip_reason,height_seed_trusted," +
                    "body_clip_risk,top_endpoint_confidence,foot_endpoint_confidence,mask_endpoint_confidence," +
                    "experimental_height_m,experimental_height_sigma_m,experimental_height_confidence," +
                    "experimental_height_state,experimental_distance_m,experimental_distance_sigma_m," +
                    "experimental_distance_confidence,experimental_solver_cost,experimental_solver_status," +
                    "experimental_factor_summary,baseline_experimental_height_delta_m," +
                    "baseline_experimental_distance_delta_m,promoted_solver_source," +
                    RAW_KEYPOINT_GEOMETRY_CSV_COLUMNS.joinToString(",")
            )
        }
        skeletonWriter = FileWriter(File(dir, "skeleton_2d_landmarks.csv")).apply {
            appendLine("frame,timestamp_ms,joint,x_norm,y_norm,z,visibility")
        }
        technicalWriter = FileWriter(File(dir, "technical_3d_landmarks.csv")).apply {
            appendLine("frame,timestamp_ms,source,joint,x_m,y_m,z_m,confidence")
        }
        visualFrameWriter = FileWriter(File(dir, "visual_frames.csv")).apply {
            appendLine("frame,timestamp_ms,media_path,media_type,width,height")
        }
        return dir
    }

    @Synchronized
    fun stop(): File? {
        val dir = sessionDir
        metricsWriter?.flushAndClose()
        skeletonWriter?.flushAndClose()
        technicalWriter?.flushAndClose()
        visualFrameWriter?.flushAndClose()
        metricsWriter = null
        skeletonWriter = null
        technicalWriter = null
        visualFrameWriter = null
        sessionDir = null
        return dir
    }

    @Synchronized
    fun recordVisualFrame(
        frame: Int,
        timestampMs: Long,
        mediaPath: String,
        mediaType: String = "rgb",
        width: Int? = null,
        height: Int? = null,
    ) {
        if (!isRecording || mediaPath.isBlank()) return
        visualFrameWriter?.appendLine(
            listOf(
                frame,
                timestampMs,
                mediaPath,
                mediaType,
                width,
                height,
            ).joinCsv()
        )
    }

    @Synchronized
    fun recordFrame(
        uiState: PocketMocapViewModel.UiState,
        visibleLandmarkCount: Int,
        sceneMetrics: SceneMetricSnapshot?,
        worldTracking: WorldTrackingSnapshot?,
        skeletonX: FloatArray?,
        skeletonY: FloatArray?,
        skeletonZ: FloatArray?,
        skeletonVisibility: FloatArray?,
        technicalX: FloatArray?,
        technicalY: FloatArray?,
        technicalZ: FloatArray?,
        technicalConfidence: FloatArray?,
        technicalSource: String,
        serverStableFrames: Int,
        acceptedPoseSource: String,
        serverPoseStatus: String,
        serverCorrectionReason: String?,
        rejectedServerReason: String,
        serverTransport: String,
        framesSentToServer: Int,
        pose3DReceivedCount: Int,
        lastPose3DAgeMs: Long?,
        lastServerJointsCount: Int,
        serverMissingReason: String,
        rawServerHeightMeters: Float?,
        rawServerDistanceMeters: Float?,
        preSkeletonConstrainedHeightMeters: Float?,
        preSkeletonConstrainedDistanceMeters: Float?,
        constrainedServerHeightMeters: Float?,
        constrainedServerDistanceMeters: Float?,
        arTargetHeightMeters: Float?,
        arTargetDistanceMeters: Float?,
        serverScaleApplied: Float?,
        serverRootShiftMeters: Float?,
        constraintConfidence: Float?,
        mlVisualUsable: Boolean?,
        mlDltMetricBad: Boolean?,
        mlHeightTargetSource: String?,
        mlDistanceTargetSource: String?,
        mlDistanceHoldActive: Boolean?,
        mlDltWeightScale: Float?,
        heightTargetWeight: Float?,
        heightPriorWeight: Float?,
        heightSmoothWeight: Float?,
        heightTargetAdmission: String?,
        heightTargetAdmissionReason: String?,
        heightGeometrySuspicious: Boolean?,
        heightWitnessCount: Float?,
        heightWitnessMedianMeters: Float?,
        heightWitnessSpreadMeters: Float?,
        heightCorrectedVsTopDeltaMeters: Float?,
        heightCorrectedVsHipDeltaMeters: Float?,
        heightCorrectedVsPixelDeltaMeters: Float?,
        heightCorrectedVsTorsoDeltaMeters: Float?,
        heightTargetBeforeGateMeters: Float?,
        heightTargetAfterGateMeters: Float?,
        heightMemoryUpdate: String?,
        heightMemoryReason: String?,
        heightMemoryAgeFrames: Float?,
        heightMemoryTargetMeters: Float?,
        wlsHeightMeters: Float?,
        postStableSkeletonHeightMeters: Float?,
        finalSmoothedHeightMeters: Float?,
        heightLossStage: String?,
        distanceTargetWeight: Float?,
        distancePriorWeight: Float?,
        distanceSmoothWeight: Float?,
        distanceLocalAuthority: Float?,
        distanceTargetAdmission: String?,
        distanceTargetAdmissionReason: String?,
        distanceGeometrySuspicious: Boolean?,
        distanceWitnessCount: Float?,
        distanceWitnessMedianMeters: Float?,
        distanceWitnessSpreadMeters: Float?,
        distanceVsPreviousTargetDeltaMeters: Float?,
        distanceFootRoiDisagreementMeters: Float?,
        distanceFootRelativeDisagreementMeters: Float?,
        distanceCorrectedVsFootDeltaMeters: Float?,
        distanceTargetBeforeGateMeters: Float?,
        distanceTargetAfterGateMeters: Float?,
        distanceMemoryUpdate: String?,
        distanceMemoryReason: String?,
        distanceMemoryAgeFrames: Float?,
        distanceMemoryTargetMeters: Float?,
        factorGraphActive: Float?,
        factorGraphStatus: String?,
        factorGraphCostBefore: Float?,
        factorGraphCostAfter: Float?,
        factorGraphScaleDelta: Float?,
        factorGraphYawDegrees: Float?,
        factorGraphRootDxMeters: Float?,
        factorGraphRootDzMeters: Float?,
        factorGraphLeftFootDzMeters: Float?,
        factorGraphRightFootDzMeters: Float?,
        factorGraphTargetDistanceMeters: Float?,
        factorGraphTargetHeightMeters: Float?,
        factorGraphFactorSummary: String?,
        metricPoseStatus: String?,
        metricPoseRejectReason: String?,
        metricPoseFrame: String?,
        metricRootXMeters: Float?,
        metricRootYMeters: Float?,
        metricRootZMeters: Float?,
        metricRootDistanceMeters: Float?,
        metricFootMidpointXMeters: Float?,
        metricFootMidpointZMeters: Float?,
        metricBodyHeightMeters: Float?,
        metricBodyScaleLocked: Boolean?,
        metricBoneScaleSource: String?,
        metricPoseJitterScaleMeters: Float?,
        metricPoseJitterRootMeters: Float?,
        poseJointsFrame: String?,
        poseJointsNormalized: Float?,
        poseJointsNormalizationScale: Float?,
        poseLifterStatus: String?,
        poseLifterModelPath: String?,
        poseLifterHiddenJointCount: Float?,
        poseLifterMeanConfidence: Float?,
        poseLifterAppliedJointCount: Float?,
        poseLifterRejectReason: String?,
        clientMotionOverlayActive: Boolean?,
        stableSkeletonLearningEnabled: Boolean?,
        stableSkeletonResetReason: String?,
        mlEvidenceStatus: String?,
        mlEvidenceHeightSigmaMeters: Float?,
        mlEvidenceDistanceSigmaMeters: Float?,
        mlEvidenceMaskEndpointConfidence: Float?,
        mlEvidenceVisibleBodyFraction: Float?,
        mlEvidenceFootContactProbability: Float?,
        mlEvidenceImageStatus: String?,
        mlEvidenceDebug: String?,
        mlImageWidthPx: Int?,
        mlImageHeightPx: Int?,
        mlImageSourceWidthPx: Int?,
        mlImageSourceHeightPx: Int?,
        mlImageCropLeftPx: Int?,
        mlImageCropTopPx: Int?,
        mlImageCropWidthPx: Int?,
        mlImageCropHeightPx: Int?,
        mlImageJpegQuality: Int?,
        mlImageCropPadRatio: Float?,
        mlEvidenceSchema: String?,
        mlEvidenceOutputs: Map<String, Float>?,
        serverSceneMetricsReceived: Boolean?,
        serverSceneMetricsAccepted: Boolean?,
        serverSceneMetricsSource: String?,
        serverSceneMetricsFloorSource: String?,
        serverSceneMetricsFilterReason: String?,
        serverSceneMetricsConfidence: Float?,
    ) {
        if (!isRecording) return
        val frame = frameIndex++
        val timestampMs = System.currentTimeMillis()
        val hasTechnicalPose =
            technicalX != null &&
                technicalY != null &&
                technicalZ != null &&
                technicalX.size >= 33 &&
                technicalY.size >= 33 &&
                technicalZ.size >= 33
        val effectiveTechnicalSource = if (hasTechnicalPose) technicalSource else "none"
        recordMetrics(
            frame,
            timestampMs,
            uiState,
            visibleLandmarkCount,
            sceneMetrics,
            worldTracking,
            effectiveTechnicalSource,
            serverStableFrames,
            if (hasTechnicalPose) acceptedPoseSource else "none",
            serverPoseStatus,
            serverCorrectionReason,
            rejectedServerReason,
            serverTransport,
            framesSentToServer,
            pose3DReceivedCount,
            lastPose3DAgeMs,
            lastServerJointsCount,
            serverMissingReason,
            rawServerHeightMeters,
            rawServerDistanceMeters,
            preSkeletonConstrainedHeightMeters,
            preSkeletonConstrainedDistanceMeters,
            constrainedServerHeightMeters,
            constrainedServerDistanceMeters,
            arTargetHeightMeters,
            arTargetDistanceMeters,
            serverScaleApplied,
            serverRootShiftMeters,
            constraintConfidence,
            mlVisualUsable,
            mlDltMetricBad,
            mlHeightTargetSource,
            mlDistanceTargetSource,
            mlDistanceHoldActive,
            mlDltWeightScale,
            heightTargetWeight,
            heightPriorWeight,
            heightSmoothWeight,
            heightTargetAdmission,
            heightTargetAdmissionReason,
            heightGeometrySuspicious,
            heightWitnessCount,
            heightWitnessMedianMeters,
            heightWitnessSpreadMeters,
            heightCorrectedVsTopDeltaMeters,
            heightCorrectedVsHipDeltaMeters,
            heightCorrectedVsPixelDeltaMeters,
            heightCorrectedVsTorsoDeltaMeters,
            heightTargetBeforeGateMeters,
            heightTargetAfterGateMeters,
            heightMemoryUpdate,
            heightMemoryReason,
            heightMemoryAgeFrames,
            heightMemoryTargetMeters,
            wlsHeightMeters,
            postStableSkeletonHeightMeters,
            finalSmoothedHeightMeters,
            heightLossStage,
            distanceTargetWeight,
            distancePriorWeight,
            distanceSmoothWeight,
            distanceLocalAuthority,
            distanceTargetAdmission,
            distanceTargetAdmissionReason,
            distanceGeometrySuspicious,
            distanceWitnessCount,
            distanceWitnessMedianMeters,
            distanceWitnessSpreadMeters,
            distanceVsPreviousTargetDeltaMeters,
            distanceFootRoiDisagreementMeters,
            distanceFootRelativeDisagreementMeters,
            distanceCorrectedVsFootDeltaMeters,
            distanceTargetBeforeGateMeters,
            distanceTargetAfterGateMeters,
            distanceMemoryUpdate,
            distanceMemoryReason,
            distanceMemoryAgeFrames,
            distanceMemoryTargetMeters,
            factorGraphActive,
            factorGraphStatus,
            factorGraphCostBefore,
            factorGraphCostAfter,
            factorGraphScaleDelta,
            factorGraphYawDegrees,
            factorGraphRootDxMeters,
            factorGraphRootDzMeters,
            factorGraphLeftFootDzMeters,
            factorGraphRightFootDzMeters,
            factorGraphTargetDistanceMeters,
            factorGraphTargetHeightMeters,
            factorGraphFactorSummary,
            metricPoseStatus,
            metricPoseRejectReason,
            metricPoseFrame,
            metricRootXMeters,
            metricRootYMeters,
            metricRootZMeters,
            metricRootDistanceMeters,
            metricFootMidpointXMeters,
            metricFootMidpointZMeters,
            metricBodyHeightMeters,
            metricBodyScaleLocked,
            metricBoneScaleSource,
            metricPoseJitterScaleMeters,
            metricPoseJitterRootMeters,
            poseJointsFrame,
            poseJointsNormalized,
            poseJointsNormalizationScale,
            poseLifterStatus,
            poseLifterModelPath,
            poseLifterHiddenJointCount,
            poseLifterMeanConfidence,
            poseLifterAppliedJointCount,
            poseLifterRejectReason,
            clientMotionOverlayActive,
            stableSkeletonLearningEnabled,
            stableSkeletonResetReason,
            mlEvidenceStatus,
            mlEvidenceHeightSigmaMeters,
            mlEvidenceDistanceSigmaMeters,
            mlEvidenceMaskEndpointConfidence,
            mlEvidenceVisibleBodyFraction,
            mlEvidenceFootContactProbability,
            mlEvidenceImageStatus,
            mlEvidenceDebug,
            mlImageWidthPx,
            mlImageHeightPx,
            mlImageSourceWidthPx,
            mlImageSourceHeightPx,
            mlImageCropLeftPx,
            mlImageCropTopPx,
            mlImageCropWidthPx,
            mlImageCropHeightPx,
            mlImageJpegQuality,
            mlImageCropPadRatio,
            mlEvidenceSchema,
            mlEvidenceOutputs,
            serverSceneMetricsReceived,
            serverSceneMetricsAccepted,
            serverSceneMetricsSource,
            serverSceneMetricsFloorSource,
            serverSceneMetricsFilterReason,
            serverSceneMetricsConfidence,
        )
        recordSkeleton(frame, timestampMs, skeletonX, skeletonY, skeletonZ, skeletonVisibility)
        recordTechnical(frame, timestampMs, effectiveTechnicalSource, technicalX, technicalY, technicalZ, technicalConfidence)
    }

    private fun recordMetrics(
        frame: Int,
        timestampMs: Long,
        uiState: PocketMocapViewModel.UiState,
        sceneVisibleCount: Int,
        scene: SceneMetricSnapshot?,
        tracking: WorldTrackingSnapshot?,
        technicalSource: String,
        serverStableFrames: Int,
        acceptedPoseSource: String,
        serverPoseStatus: String,
        serverCorrectionReason: String?,
        rejectedServerReason: String,
        serverTransport: String,
        framesSentToServer: Int,
        pose3DReceivedCount: Int,
        lastPose3DAgeMs: Long?,
        lastServerJointsCount: Int,
        serverMissingReason: String,
        rawServerHeightMeters: Float?,
        rawServerDistanceMeters: Float?,
        preSkeletonConstrainedHeightMeters: Float?,
        preSkeletonConstrainedDistanceMeters: Float?,
        constrainedServerHeightMeters: Float?,
        constrainedServerDistanceMeters: Float?,
        arTargetHeightMeters: Float?,
        arTargetDistanceMeters: Float?,
        serverScaleApplied: Float?,
        serverRootShiftMeters: Float?,
        constraintConfidence: Float?,
        mlVisualUsable: Boolean?,
        mlDltMetricBad: Boolean?,
        mlHeightTargetSource: String?,
        mlDistanceTargetSource: String?,
        mlDistanceHoldActive: Boolean?,
        mlDltWeightScale: Float?,
        heightTargetWeight: Float?,
        heightPriorWeight: Float?,
        heightSmoothWeight: Float?,
        heightTargetAdmission: String?,
        heightTargetAdmissionReason: String?,
        heightGeometrySuspicious: Boolean?,
        heightWitnessCount: Float?,
        heightWitnessMedianMeters: Float?,
        heightWitnessSpreadMeters: Float?,
        heightCorrectedVsTopDeltaMeters: Float?,
        heightCorrectedVsHipDeltaMeters: Float?,
        heightCorrectedVsPixelDeltaMeters: Float?,
        heightCorrectedVsTorsoDeltaMeters: Float?,
        heightTargetBeforeGateMeters: Float?,
        heightTargetAfterGateMeters: Float?,
        heightMemoryUpdate: String?,
        heightMemoryReason: String?,
        heightMemoryAgeFrames: Float?,
        heightMemoryTargetMeters: Float?,
        wlsHeightMeters: Float?,
        postStableSkeletonHeightMeters: Float?,
        finalSmoothedHeightMeters: Float?,
        heightLossStage: String?,
        distanceTargetWeight: Float?,
        distancePriorWeight: Float?,
        distanceSmoothWeight: Float?,
        distanceLocalAuthority: Float?,
        distanceTargetAdmission: String?,
        distanceTargetAdmissionReason: String?,
        distanceGeometrySuspicious: Boolean?,
        distanceWitnessCount: Float?,
