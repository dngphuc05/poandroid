package com.pocketmocap.app.network

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer

/**
 * Manages a WebRTC PeerConnection + DataChannel for low-latency pose streaming.
 *
 * Transport properties (reliable, ordered — SCTP over DTLS/UDP):
 *   - Faster than Socket.IO/TCP on LAN: no HTTP overhead, direct P2P, UDP-based SCTP
 *   - Reliable delivery required: GRU and Kalman filter are sequential models;
 *     dropped frames corrupt their hidden state and produce bad pose output
 *   - ordered=true so the GRU sees frames in the right temporal order
 *
 * Protocol:
 *   Phone (offerer)          ←Socket.IO signaling→  Server (aiortc)
 *   createOffer
 *   → rtc_offer {sdp, type}
 *   ← rtc_answer {sdp, type}  (server ICE already embedded in SDP)
 *   → rtc_ice  {candidate, sdpMid, sdpMLineIndex}  (trickle)
 *   [DataChannel OPEN]
 *   → DataChannel.send(compact frame JSON)
 *   ← DataChannel.onMessage (pose_3d JSON)
 */
class WebRtcPoseChannel(
    context: Context,
    private val onOffer: (sdp: String, type: String) -> Unit,
    private val onIceCandidate: (candidate: String, sdpMid: String, sdpMLineIndex: Int) -> Unit,
    private val onPose3D: (JSONObject) -> Unit,
    private val onChannelReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "WebRtcPoseChannel"
        private const val DATA_CHANNEL_LABEL = "pose"

        // Single STUN server for host-candidate refinement on non-LAN networks.
        // On the same WiFi the host candidate will succeed anyway.
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )

        @Volatile
        private var factoryInitialized = false

        fun initializeFactory(context: Context) {
            if (factoryInitialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            factoryInitialized = true
        }
    }

    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    @Volatile
    var isReady: Boolean = false
        private set

    init {
        initializeFactory(context)
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    /** Call once after Socket.IO session is confirmed to start the WebRTC handshake. */
    fun createOffer() {
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        pc = factory.createPeerConnection(config, pcObserver)
            ?: run { onError("PeerConnection creation failed"); return }

        // DataChannel must be created BEFORE the offer so it's included in the SDP.
        // Reliable + ordered: SCTP over UDP still beats Socket.IO/TCP for latency on LAN,
        // but we don't drop frames — the GRU and Kalman filter need sequential input.
        val dcInit = DataChannel.Init().apply {
            ordered = true
            // maxRetransmits / maxRetransmitTimeMs left at default (-1 = unlimited retransmits)
        }
        dataChannel = pc!!.createDataChannel(DATA_CHANNEL_LABEL, dcInit)
            ?.also { it.registerObserver(dcObserver) }
            ?: run { onError("DataChannel creation failed"); return }

