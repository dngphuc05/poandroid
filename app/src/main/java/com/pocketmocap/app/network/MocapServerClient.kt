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
