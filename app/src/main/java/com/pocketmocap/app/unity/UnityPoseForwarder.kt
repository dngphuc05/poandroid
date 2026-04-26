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

