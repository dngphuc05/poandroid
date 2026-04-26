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

            val joints = org.json.JSONArray()
            for (i in 0 until jointsArr.length()) {
                val serverJoint = jointsArr.getJSONObject(i)
                joints.put(JSONObject().apply {
                    put("index", serverJoint.optInt("index", i))
                    put("name", serverJoint.optString("name", "joint_${i.toString().padStart(2, '0')}"))
                    put("position", JSONObject().apply {
                        put("x", serverJoint.optDouble("x", 0.0))
                        put("y", serverJoint.optDouble("y", 0.0))
                        put("z", serverJoint.optDouble("z", 0.0))
                    })
                    put("confidence", serverJoint.optDouble("confidence", 0.0))
                    put("numViews", serverJoint.optInt("num_views", 1))
                    put("reprojectionError", serverJoint.optDouble("reprojection_error", 0.0))
                    put("valid", serverJoint.optBoolean("valid", false))
                })
            }
            put("joints", joints)
        }

        sendToUnity("PushPoseFrame3D", dto.toString())
    }

    /**
     * Forward tracking state to Unity.
     */
    fun forwardTrackingState(state: String, setupProgress: Float) {
        val dto = JSONObject().apply {
            put("trackingState", state)
            put("setupProgress", setupProgress.toDouble())
        }
        sendToUnity("PushNativeTrackingState", dto.toString())
    }

    fun setViewMode(mode: String) = sendToUnity("SetViewMode", mode)
    fun setMirrorDistance(meters: Float) = sendToUnity("SetMirrorDistance", meters.toString())
    fun beginSetup() = sendToUnity("BeginSetup", "")

    private fun sendToUnity(method: String, message: String) {
        try {
            // Unity's UnitySendMessage via reflection (when Unity is embedded as a library)
