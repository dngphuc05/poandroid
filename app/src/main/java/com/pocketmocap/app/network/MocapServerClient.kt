package com.pocketmocap.app.network

import android.content.Context
import android.util.Log
import com.pocketmocap.app.tracking.SceneMetricSnapshot
import com.pocketmocap.app.tracking.WorldTrackingSnapshot
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Socket.IO client that communicates with pocket-mocap-server.
 *
 * Protocol:
 *   connect → calibrate → bootstrap (15x) → frame (continuous) ← pose_3d
 *
 * Once a WebRTC DataChannel is negotiated the heavy frame↔pose_3d stream
 * moves to the DataChannel (UDP-like, low latency).  Socket.IO is kept
 * alive only for session lifecycle events and as a fallback.
 */
class MocapServerClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(sessionId: String)
        fun onDisconnected()
        fun onCalibrationAck(success: Boolean, state: String)
        fun onBootstrapProgress(collected: Int, target: Int, complete: Boolean)
        fun onPose3DReceived(pose3dJson: JSONObject)
        fun onRtcChannelReady()          // WebRTC DataChannel is open — low-latency path active
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "MocapServerClient"
        private const val RTC_NO_POSE_FRAME_LIMIT = 18
        private const val RTC_MAX_FRAME_BYTES = 180_000
    }

    private var socket: Socket? = null
    private val sessionId = AtomicReference<String?>(null)
    private var serverUrl: String = ""
    @Volatile private var latestPoseFrameIndex = -1
    @Volatile private var latestPoseTimestampUs = 0L

    // WebRTC DataChannel — replaces Socket.IO for frame transport once open
    private var rtcChannel: WebRtcPoseChannel? = null
    @Volatile private var rtcDisabledForFrames = false
    @Volatile private var rtcFramesSinceLastPose = 0

    val isConnected: Boolean get() = socket?.connected() == true
    val isRtcReady: Boolean get() = !rtcDisabledForFrames && rtcChannel?.isReady == true

    fun connect(url: String) {
        serverUrl = url
        try {
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionAttempts(10)
                .setReconnectionDelay(1000)
                .setTimeout(10000)
                .build()
            socket = IO.socket(URI.create(url), opts).apply {
                on(Socket.EVENT_CONNECT) {
                    Log.i(TAG, "Connected to $url, sending register")
                    latestPoseFrameIndex = -1
                    latestPoseTimestampUs = 0L
                    rtcDisabledForFrames = false
                    rtcFramesSinceLastPose = 0
                    emit("register")
                }
                on(Socket.EVENT_DISCONNECT) {
                    Log.i(TAG, "Disconnected")
                    listener.onDisconnected()
                }
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val err = args.firstOrNull()?.toString() ?: "unknown"
                    Log.e(TAG, "Connection error: $err")
                    listener.onError("Connection failed: $err")
                }
                on("session_created") { args ->
                    val data = args.firstOrNull() as? JSONObject
                    if (data == null) {
                        Log.w(TAG, "session_created: no JSONObject in args (${args.map { it?.javaClass?.name }})")
                        return@on
                    }
                    val sid = data.optString("session_id", "")
                    Log.i(TAG, "Session created: $sid")
                    sessionId.set(sid)
                    listener.onConnected(sid)
                    // Kick off WebRTC negotiation immediately after session is confirmed
                    initiateRtc()
                }
                on("rtc_answer") { args ->
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    val sdp = data.optString("sdp")
                    val type = data.optString("type")
                    Log.i(TAG, "Received rtc_answer")
                    rtcChannel?.setRemoteAnswer(sdp, type)
                }
                on("rtc_ice") { args ->
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    val candidate = data.optString("candidate")
                    val sdpMid = data.optString("sdpMid", "0")
                    val sdpMLineIndex = data.optInt("sdpMLineIndex", 0)
                    rtcChannel?.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }
                on("calibration_ack") { args ->
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    val status = data.optString("status", "")
                    val state = data.optString("state", "")
                    listener.onCalibrationAck(status == "ok", state)
                }
                on("bootstrap_ack") { args ->
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    val progress = data.optJSONObject("bootstrap_progress")
                    val collected = progress?.optInt("collected", 0) ?: 0
                    val target = progress?.optInt("target", 15) ?: 15
                    val complete = data.optString("status") == "complete"
                    listener.onBootstrapProgress(collected, target, complete)
                }
                on("pose_3d") { args ->
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    deliverPose3DIfFresh(data)
                }
                on("error") { args ->
                    val data = args.firstOrNull() as? JSONObject
                    val msg = data?.optString("message", "Server error") ?: "Server error"
                    Log.e(TAG, "Server error: $msg")
                    listener.onError(msg)
                }
                connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            listener.onError("Failed to connect: ${e.message}")
        }
    }

    fun disconnect() {
        rtcChannel?.dispose()
        rtcChannel = null
        socket?.disconnect()
        socket?.off()
        socket = null
        sessionId.set(null)
        latestPoseFrameIndex = -1
        latestPoseTimestampUs = 0L
        rtcDisabledForFrames = false
        rtcFramesSinceLastPose = 0
    }

    /** Create the WebRTC channel and fire the offer over Socket.IO. */
    private fun initiateRtc() {
        if (rtcChannel != null) return
        rtcChannel = WebRtcPoseChannel(
            context = context,
            onOffer = { sdp, type ->
                socket?.emit("rtc_offer", JSONObject().apply {
                    put("sdp", sdp)
                    put("type", type)
                })
                Log.i(TAG, "Sent rtc_offer")
            },
            onIceCandidate = { candidate, sdpMid, sdpMLineIndex ->
                socket?.emit("rtc_ice", JSONObject().apply {
                    put("candidate", candidate)
                    put("sdpMid", sdpMid)
                    put("sdpMLineIndex", sdpMLineIndex)
                })
            },
            onPose3D = { json -> deliverPose3DIfFresh(json) },
            onChannelReady = {
                Log.i(TAG, "WebRTC DataChannel OPEN — low-latency path active")
                listener.onRtcChannelReady()
            },
            onError = { msg ->
                Log.w(TAG, "WebRTC error (will fall back to Socket.IO): $msg")
                disableRtcFrameTransport(msg)
            },
        )
        rtcChannel!!.createOffer()
    }

    private fun disableRtcFrameTransport(reason: String) {
        if (!rtcDisabledForFrames) {
            Log.w(TAG, "Disabling WebRTC frame transport; Socket.IO fallback active. reason=$reason")
        }
        rtcDisabledForFrames = true
        rtcFramesSinceLastPose = 0
        runCatching { rtcChannel?.close() }
    }

    /**
     * Send camera intrinsics for calibration.
     */
    fun sendCalibration(
        fx: Double, fy: Double, cx: Double, cy: Double,
        imageWidth: Int, imageHeight: Int,
        mirrorDistance: Float,
    ) {
        latestPoseFrameIndex = -1
        latestPoseTimestampUs = 0L
        val data = JSONObject().apply {
            put("fx", fx)
            put("fy", fy)
            put("cx", cx)
            put("cy", cy)
            put("image_width", imageWidth)
            put("image_height", imageHeight)
            put("mirror_distance", mirrorDistance.toDouble())
        }
        socket?.emit("calibrate", data)
        Log.i(TAG, "Sent calibration: ${imageWidth}x${imageHeight} fx=$fx fy=$fy")
    }

    /**
     * Send a bootstrap frame (2D landmarks for bone constraint learning).
     */
    fun sendBootstrapFrame(landmarks: List<LandmarkData>) {
        val data = JSONObject().apply {
            put("landmarks", landmarksToJson(landmarks))
        }
        socket?.emit("bootstrap", data)
    }

    /**
     * Send a capture frame (2D landmarks → server returns 3D pose).
     *
     * Routing:
     *  • WebRTC DataChannel (isRtcReady=true) → compact array JSON, UDP-like, low latency
     *  • Socket.IO fallback                   → named-object JSON over TCP
     */
    fun sendFrame(
        frameIndex: Int,
        timestampUs: Long,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int = 0,
        landmarks: List<LandmarkData>,
        worldTracking: WorldTrackingSnapshot? = null,
        sceneMetrics: SceneMetricSnapshot? = null,
        mlImageBase64: String? = null,
        mlImageWidth: Int? = null,
        mlImageHeight: Int? = null,
        mlImageSourceWidth: Int? = null,
        mlImageSourceHeight: Int? = null,
        mlImageCropLeft: Int? = null,
        mlImageCropTop: Int? = null,
        mlImageCropWidth: Int? = null,
        mlImageCropHeight: Int? = null,
        mlImageJpegQuality: Int? = null,
        mlImageCropPadRatio: Float? = null,
    ) {
        if (isRtcReady) {
            val json = buildCompactFrameJson(
                frameIndex,
                timestampUs,
                imageWidth,
                imageHeight,
                rotationDegrees,
                landmarks,
                worldTracking,
                sceneMetrics,
                mlImageBase64,
                mlImageWidth,
                mlImageHeight,
                mlImageSourceWidth,
                mlImageSourceHeight,
                mlImageCropLeft,
                mlImageCropTop,
                mlImageCropWidth,
                mlImageCropHeight,
                mlImageJpegQuality,
                mlImageCropPadRatio,
            )
            val payloadBytes = json.toByteArray(Charsets.UTF_8).size
            when {
                payloadBytes > RTC_MAX_FRAME_BYTES -> {
                    disableRtcFrameTransport("rtc_payload_too_large:${payloadBytes}B")
                }
                rtcChannel!!.sendFrame(json) -> {
                    rtcFramesSinceLastPose += 1
                    if (rtcFramesSinceLastPose < RTC_NO_POSE_FRAME_LIMIT) {
                        return
                    }
                    disableRtcFrameTransport("no_pose_response_after_${rtcFramesSinceLastPose}_rtc_frames")
                    // Fall through and send this same frame via Socket.IO.
                    // If the RTC copy eventually arrives, frame-index/timestamp
                    // freshness gates on both sides will discard the duplicate.
                }
                else -> {
                    disableRtcFrameTransport("datachannel_send_failed")
                }
            }
            // Fall through to Socket.IO if DataChannel is unhealthy or unsafe for this payload.
        }
        val data = JSONObject().apply {
            put("frame_index", frameIndex)
            put("timestamp_us", timestampUs)
            put("image_width", imageWidth)
            put("image_height", imageHeight)
            put("rotation_degrees", rotationDegrees)
            put("landmarks", landmarksToNamedJson(landmarks))
            worldTracking?.let { put("world_tracking", it.toJson()) }
            sceneMetrics?.let { put("scene_metrics", it.toJson()) }
            putMlImagePayload(
                mlImageBase64,
                mlImageWidth,
                mlImageHeight,
                mlImageSourceWidth,
                mlImageSourceHeight,
                mlImageCropLeft,
                mlImageCropTop,
                mlImageCropWidth,
                mlImageCropHeight,
                mlImageJpegQuality,
                mlImageCropPadRatio,
            )
        }
        socket?.emit("frame", data)
    }

    fun sendMirrorDistance(distance: Float) {
        socket?.emit("set_mirror_distance", JSONObject().put("distance", distance.toDouble()))
    }

    @Synchronized
    private fun deliverPose3DIfFresh(pose3dJson: JSONObject) {
        val frameIndex = pose3dJson.optInt("frame_index", -1)
        val timestampUs = pose3dJson.optLong("timestamp_us", 0L)

        val staleByFrame = frameIndex >= 0 && latestPoseFrameIndex >= 0 && frameIndex <= latestPoseFrameIndex
        val staleByTimestamp = timestampUs > 0L && latestPoseTimestampUs > 0L && timestampUs <= latestPoseTimestampUs
        if (staleByFrame || staleByTimestamp) return

        if (frameIndex >= 0) latestPoseFrameIndex = frameIndex
        if (timestampUs > 0L) latestPoseTimestampUs = timestampUs
        rtcFramesSinceLastPose = 0
        listener.onPose3DReceived(pose3dJson)
    }

    private fun landmarksToJson(landmarks: List<LandmarkData>): JSONArray = landmarksToNamedJson(landmarks)

    /** Named-object format used by Socket.IO fallback path. */
    private fun landmarksToNamedJson(landmarks: List<LandmarkData>): JSONArray {
        val arr = JSONArray()
        landmarks.forEach { lm ->
            arr.put(JSONObject().apply {
                put("x", lm.x.toDouble())
                put("y", lm.y.toDouble())
                put("z", lm.z.toDouble())
                put("x_metric", lm.xMetric.toDouble())
                put("y_metric", lm.yMetric.toDouble())
                put("z_metric", lm.zMetric.toDouble())
                put("visibility", lm.visibility.toDouble())
                put("presence", lm.presence.toDouble())
                put("confidence", lm.confidence.toDouble())
            })
        }
        return arr
    }

    /**
     * Compact WebRTC DataChannel frame format — ~60% smaller than named-object JSON.
     *   {"fi":42,"ts":1234,"iw":1080,"ih":1920,"lm":[[x,y,z,xm,ym,zm,vis,pres,conf],...]}
     */
    private fun buildCompactFrameJson(
        frameIndex: Int,
        timestampUs: Long,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        landmarks: List<LandmarkData>,
        worldTracking: WorldTrackingSnapshot?,
        sceneMetrics: SceneMetricSnapshot?,
        mlImageBase64: String?,
        mlImageWidth: Int?,
        mlImageHeight: Int?,
        mlImageSourceWidth: Int?,
        mlImageSourceHeight: Int?,
        mlImageCropLeft: Int?,
        mlImageCropTop: Int?,
        mlImageCropWidth: Int?,
        mlImageCropHeight: Int?,
        mlImageJpegQuality: Int?,
        mlImageCropPadRatio: Float?,
    ): String {
        // Use simple %.4f formatting — avoids 198 BigDecimal heap allocations per frame
        val sb = StringBuilder(landmarks.size * 45 + 60)
        sb.append("{\"fi\":").append(frameIndex)
        sb.append(",\"ts\":").append(timestampUs)
        sb.append(",\"iw\":").append(imageWidth)
        sb.append(",\"ih\":").append(imageHeight)
        sb.append(",\"rd\":").append(rotationDegrees)
        sb.append(",\"lm\":[")
        landmarks.forEachIndexed { i, lm ->
            if (i > 0) sb.append(',')
            sb.append('[')
            sb.append(String.format(Locale.US, "%.4f", lm.x)).append(',')
            sb.append(String.format(Locale.US, "%.4f", lm.y)).append(',')
            sb.append(String.format(Locale.US, "%.5f", lm.z)).append(',')
            sb.append(String.format(Locale.US, "%.5f", lm.xMetric)).append(',')
            sb.append(String.format(Locale.US, "%.5f", lm.yMetric)).append(',')
            sb.append(String.format(Locale.US, "%.5f", lm.zMetric)).append(',')
            sb.append(String.format(Locale.US, "%.4f", lm.visibility)).append(',')
            sb.append(String.format(Locale.US, "%.4f", lm.presence)).append(',')
            sb.append(String.format(Locale.US, "%.4f", lm.confidence))
            sb.append(']')
        }
        sb.append("]}")
        val base = JSONObject(sb.toString())
        worldTracking?.let { base.put("wt", it.toJson()) }
        sceneMetrics?.let { base.put("sm", it.toJson()) }
        base.putMlImagePayload(
            mlImageBase64,
            mlImageWidth,
            mlImageHeight,
            mlImageSourceWidth,
            mlImageSourceHeight,
            mlImageCropLeft,
            mlImageCropTop,
            mlImageCropWidth,
            mlImageCropHeight,
            mlImageJpegQuality,
            mlImageCropPadRatio,
            compactKeys = true,
        )
        return base.toString()
    }

    private fun JSONObject.putMlImagePayload(
        jpegBase64: String?,
        width: Int?,
        height: Int?,
        sourceWidth: Int?,
        sourceHeight: Int?,
        cropLeft: Int?,
        cropTop: Int?,
        cropWidth: Int?,
        cropHeight: Int?,
        jpegQuality: Int?,
        cropPadRatio: Float?,
        compactKeys: Boolean = false,
    ) {
        if (jpegBase64.isNullOrBlank()) return
        if (compactKeys) {
            put("mi", JSONObject().apply {
                put("fmt", "jpeg_base64")
                put("jpg", jpegBase64)
                width?.let { put("w", it) }
                height?.let { put("h", it) }
                sourceWidth?.let { put("sw", it) }
                sourceHeight?.let { put("sh", it) }
                cropLeft?.let { put("x", it) }
                cropTop?.let { put("y", it) }
                cropWidth?.let { put("cw", it) }
                cropHeight?.let { put("ch", it) }
                jpegQuality?.let { put("q", it) }
                cropPadRatio?.let { put("pad", it.toDouble()) }
            })
        } else {
            put("ml_image", JSONObject().apply {
                put("format", "jpeg_base64")
                put("jpeg_base64", jpegBase64)
                width?.let { put("width", it) }
                height?.let { put("height", it) }
                sourceWidth?.let { put("source_width", it) }
                sourceHeight?.let { put("source_height", it) }
                cropLeft?.let { put("crop_left", it) }
                cropTop?.let { put("crop_top", it) }
                cropWidth?.let { put("crop_width", it) }
                cropHeight?.let { put("crop_height", it) }
                jpegQuality?.let { put("jpeg_quality", it) }
                cropPadRatio?.let { put("crop_pad_ratio", it.toDouble()) }
            })
        }
    }
}

