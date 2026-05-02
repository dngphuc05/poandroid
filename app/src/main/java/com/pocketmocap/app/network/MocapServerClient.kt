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
