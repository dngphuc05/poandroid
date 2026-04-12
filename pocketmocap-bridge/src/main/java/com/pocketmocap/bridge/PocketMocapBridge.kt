package com.pocketmocap.bridge

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

class PocketMocapBridge private constructor() {
    data class RuntimeShellState(
        val trackingState: String = "inactive",
        val setupProgress: Float = 0.0f,
        val setupLabel: String = "",
        val avatarState: String = "bundled",
        val avatarLabel: String = "Default",
        val sourceLabel: String = "",
        val viewMode: String = "ar",
    )

    interface RuntimeStateListener {
        fun onRuntimeShellStateChanged(state: RuntimeShellState) {}
