package com.pocketmocap.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureNodeContractTest {
    @Test
    fun phoneAppDoesNotOwnReconstructionDisplay() {
        assertFalse(CaptureNodeContract.ownsReconstructionDisplay())
        assertTrue(CaptureNodeContract.forbiddenRuntimeResponsibilities.contains("3d_skeleton_display"))
        assertTrue(CaptureNodeContract.forbiddenRuntimeResponsibilities.contains("avatar_stage"))
        assertTrue(CaptureNodeContract.forbiddenRuntimeResponsibilities.contains("vrm_library"))
        assertTrue(CaptureNodeContract.forbiddenRuntimeResponsibilities.contains("environment_library"))
    }

    @Test
    fun phoneAppStreamsCaptureEvidenceToServer() {
        assertTrue(CaptureNodeContract.outboundPayloads.contains("timestamped_frame"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("frame_index"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("mediapipe_2d_landmarks"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("mediapipe_metric_xyz"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("landmark_visibility_presence_confidence"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("camera_intrinsics"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("image_size"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("rotation_degrees"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("world_tracking"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("scene_metrics"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("ml_crop"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("webrtc_compact_frame"))
        assertTrue(CaptureNodeContract.outboundPayloads.contains("socketio_named_frame"))
    }

    @Test
    fun phoneScreensStayCaptureNodeFocused() {
        assertTrue(CaptureNodeContract.primaryScreens.contains("join_session"))
        assertTrue(CaptureNodeContract.primaryScreens.contains("capture_device"))
        assertTrue(CaptureNodeContract.primaryScreens.contains("live_capture_status"))
        assertFalse(CaptureNodeContract.primaryScreens.contains("asset_library"))
        assertFalse(CaptureNodeContract.primaryScreens.contains("avatar_stage"))
    }
}
