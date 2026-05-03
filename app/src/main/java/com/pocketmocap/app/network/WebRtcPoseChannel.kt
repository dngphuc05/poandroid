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

        pc!!.createOffer(sdpObserverFor("offer"), MediaConstraints())
    }

    /** Called when the server's answer arrives via Socket.IO `rtc_answer` event. */
    fun setRemoteAnswer(sdp: String, type: String) {
        val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
        pc?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() { Log.i(TAG, "Remote description set OK") }
            override fun onSetFailure(error: String) { onError("setRemoteDescription failed: $error") }
        }, desc)
    }

    /** Called for each trickle ICE candidate from the server (via Socket.IO `rtc_ice` event). */
    fun addRemoteIceCandidate(candidateSdp: String, sdpMid: String, sdpMLineIndex: Int) {
        pc?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidateSdp))
    }

    /**
     * Send one pose frame over the DataChannel.
     * @param json Compact frame JSON using short keys:
     *   {"fi":42,"ts":1234,"iw":1080,"ih":1920,"lm":[[x,y,xm,ym,zm,vis],...]}
     * @return true if the message was queued successfully.
     */
    fun sendFrame(json: String): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val bytes = json.toByteArray(Charsets.UTF_8)
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun close() {
        isReady = false
        dataChannel?.close()
        dataChannel = null
        pc?.close()
        pc = null
    }

    fun dispose() {
        close()
        factory.dispose()
    }

    // ── PeerConnection observer ──────────────────────────────────────────────

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            // Trickle: send each candidate as it's gathered
            onIceCandidate(candidate.sdp, candidate.sdpMid ?: "0", candidate.sdpMLineIndex)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "PeerConnection state: $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED -> {
                    isReady = false
                    onError("WebRTC connection failed - falling back to Socket.IO")
                }
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.CLOSED -> isReady = false
                else -> Unit
            }
        }

        // Unused – no audio/video tracks
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {}   // we're the offerer, we own the DC
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, streams: Array<MediaStream>) {}
    }

    // ── DataChannel observer ─────────────────────────────────────────────────

    private val dcObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}

        override fun onStateChange() {
            val state = dataChannel?.state()
            Log.i(TAG, "DataChannel state: $state")
            when (state) {
                DataChannel.State.OPEN -> {
                    isReady = true
                    onChannelReady()
                }
                DataChannel.State.CLOSED, DataChannel.State.CLOSING -> isReady = false
                else -> Unit
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            try {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                onPose3D(json)
            } catch (e: Exception) {
                Log.e(TAG, "DataChannel message parse error", e)
            }
        }
    }

