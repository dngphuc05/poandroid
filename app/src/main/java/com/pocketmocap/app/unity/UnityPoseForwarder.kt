package com.pocketmocap.app.unity

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * Hosts Unity as a FrameLayout and forwards 3D poses from server via UnitySendMessage.
 */
class UnityPoseForwarder {

    companion object {
        private const val TAG = "UnityPoseForwarder"
        private const val UNITY_GAME_OBJECT = "ShellReceiver"
    }

    /**
     * Forward a 3D pose JSON from the server to Unity for retargeting + VRM rendering.
     *
     * The JSON format matches what PocketMocapShellReceiver.PushPoseFrame3D expects:
     * {
     *   "timestampUs": 123456789,
     *   "effectiveViewIds": ["server"],
     *   "joints": [
     *     { "index": 0, "name": "nose", "position": {"x":0,"y":0,"z":0},
     *       "confidence": 0.9, "numViews": 2, "reprojectionError": 0.001, "valid": true },
     *     ...
     *   ]
     * }
     */
    fun forwardPose3D(pose3dJson: JSONObject) {
        val jointsArr = pose3dJson.optJSONArray("joints") ?: return
        val timestampUs = pose3dJson.optLong("timestamp_us", 0)

        // Build the DTO format Unity expects
        val dto = JSONObject().apply {
            put("timestampUs", timestampUs)
            put("effectiveViewIds", org.json.JSONArray().put("server"))

