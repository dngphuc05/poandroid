package com.pocketmocap.app

/**
 * Public responsibility contract for the Pocap phone app.
 *
 * The phone is a capture node: it acquires camera/MediaPipe/AR evidence and
 * streams it to the server. Reconstructed 3D skeleton, avatar stage, asset
 * library, and operator controls belong to the Pocap PC app.
 */
object CaptureNodeContract {
    val primaryScreens = setOf(
        "join_session",
        "camera_permission",
        "capture_device",
        "sync_calibration_status",
        "live_capture_status",
        "error_disconnected",
    )

    val outboundPayloads = setOf(
        "timestamped_frame",
        "mediapipe_2d_landmarks",
        "camera_intrinsics",
        "world_tracking",
        "scene_metrics",
        "ml_crop",
        "device_status",
    )

    val forbiddenRuntimeResponsibilities = setOf(
        "3d_skeleton_display",
        "avatar_stage",
        "vrm_library",
        "environment_library",
        "operator_session_dashboard",
        "canonical_solver_display",
    )

    fun ownsReconstructionDisplay(): Boolean = false
}
