package com.pocketmocap.app.calibration

import org.json.JSONArray
import org.json.JSONObject

data class CloudAnchorResult(
    val state: String,
    val sharedAnchorId: String = "",
    val position: FloatArray = floatArrayOf(),
    val rotation: FloatArray = floatArrayOf(),
    val quality: Float = Float.NaN,
    val errorMessage: String = "",
) {
    val isSuccess: Boolean
        get() = state == "hosted" || state == "resolved"

    fun poseJson(): JSONObject = JSONObject().apply {
        if (position.size >= 3) {
            put("position", JSONArray().apply {
                put(position[0].toDouble())
                put(position[1].toDouble())
                put(position[2].toDouble())
            })
        }
        if (rotation.size >= 4) {
            put("rotation", JSONArray().apply {
                put(rotation[0].toDouble())
                put(rotation[1].toDouble())
                put(rotation[2].toDouble())
                put(rotation[3].toDouble())
            })
        }
        put("state", state)
        if (sharedAnchorId.isNotBlank()) put("shared_anchor_id", sharedAnchorId)
        if (quality.isFinite()) put("quality", quality.toDouble())
    }
}

interface CloudAnchorEngine {
    fun hostSharedAnchor(onResult: (CloudAnchorResult) -> Unit)
    fun resolveSharedAnchor(sharedAnchorId: String, onResult: (CloudAnchorResult) -> Unit)
}
