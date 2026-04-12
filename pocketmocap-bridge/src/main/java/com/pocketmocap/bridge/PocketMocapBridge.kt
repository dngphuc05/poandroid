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
        fun onUnityRuntimeReadyChanged(isReady: Boolean) {}
    }

    private var activityRef: WeakReference<ComponentActivity>? = null
    private var vrmPickerLauncher: ActivityResultLauncher<Array<String>>? = null
    private var lastPickedVrmUri: Uri? = null
    private var runtimeShellState = RuntimeShellState()
    private val listeners = CopyOnWriteArraySet<RuntimeStateListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var unityRuntimeReady = false

    fun attachActivity(activity: ComponentActivity) {
        activityRef = WeakReference(activity)
        resetRuntimeShellState()
        vrmPickerLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                lastPickedVrmUri = uri
            }
        }
    }

    fun addRuntimeStateListener(listener: RuntimeStateListener) {
        listeners += listener
        dispatchState(listener, runtimeShellState)
        dispatchReady(listener, unityRuntimeReady)
    }

    fun removeRuntimeStateListener(listener: RuntimeStateListener) {
        listeners -= listener
    }

    fun getRuntimeShellStateSnapshot(): RuntimeShellState = runtimeShellState

    /**
     * Get camera intrinsics using attached activity context.
     */
    fun getCameraIntrinsicsJson(): String {
        val ctx = activityRef?.get() ?: return "{}"
        return getCameraIntrinsicsJson(ctx)
    }

    /**
     * Get camera intrinsics using any context (e.g. Application context from ViewModel).
     */
    fun getCameraIntrinsicsJson(context: Context): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findPreferredCameraId(cameraManager) ?: return "{}"
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val streamConfig = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSize = streamConfig?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.firstOrNull()

        val width = outputSize?.width ?: 1920
        val height = outputSize?.height ?: 1080
        val focalMm = focalLengths?.firstOrNull() ?: 4.25f
        val sensorWidthMm = sensorSize?.width ?: 5.76f
        val sensorHeightMm = sensorSize?.height ?: 4.29f
        val fx = width.toDouble() * focalMm / sensorWidthMm
        val fy = height.toDouble() * focalMm / sensorHeightMm
        val cx = width / 2.0
        val cy = height / 2.0

        return JSONObject()
            .put("cameraId", cameraId)
            .put("width", width)
            .put("height", height)
            .put("fx", fx)
            .put("fy", fy)
            .put("cx", cx)
            .put("cy", cy)
            .toString()
    }

