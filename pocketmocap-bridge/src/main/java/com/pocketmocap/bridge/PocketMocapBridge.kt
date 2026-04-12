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

    fun pickVrm() {
        vrmPickerLauncher?.launch(arrayOf("*/*"))
    }

    fun getLastPickedVrmUri(): String? = lastPickedVrmUri?.toString()

    fun copyLastPickedVrmToCache(suggestedFileName: String): String {
        val activity = activityRef?.get() ?: return ""
        val uri = lastPickedVrmUri ?: return ""
        val importDir = File(activity.filesDir, "imports").apply { mkdirs() }
        val baseName = when {
            suggestedFileName.isNotBlank() -> suggestedFileName
            else -> uri.lastPathSegment ?: "picked-avatar"
        }.replace("[^A-Za-z0-9._-]".toRegex(), "_")
        val normalized = if (baseName.endsWith(".vrm", ignoreCase = true)) baseName else "$baseName.vrm"
        val output = uniqueFile(importDir, normalized)

        return try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            } ?: return ""
            output.absolutePath
        } catch (_: IOException) {
            ""
        }
    }

    fun updateRuntimeShellState(
        trackingState: String,
        setupProgress: Float,
        setupLabel: String,
        avatarState: String,
        avatarLabel: String,
        sourceLabel: String,
        viewMode: String,
    ) {
        runtimeShellState = RuntimeShellState(
            trackingState = trackingState,
            setupProgress = setupProgress,
            setupLabel = setupLabel,
            avatarState = avatarState,
            avatarLabel = avatarLabel,
            sourceLabel = sourceLabel,
            viewMode = viewMode,
        )

        val wasReady = unityRuntimeReady
        unityRuntimeReady = true
        if (!wasReady) {
            dispatchReadyToAll(true)
        }
        dispatchStateToAll()
    }

