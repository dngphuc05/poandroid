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
