package com.teamaccess.remote.webrtc

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.teamaccess.remote.model.IceCandidateData
import com.teamaccess.remote.model.SignalingMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String
) {
    private val TAG = "SignalingClient"
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isDestroyed = false

    private var lastRegisteredId: String? = null
    private var lastRegisteredDeviceName: String? = null
    private var lastRegisteredIsHost: Boolean = true

    private val listeners = CopyOnWriteArraySet<SignalingCallback>()

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    interface SignalingCallback {
        fun onConnected() {}
        fun onRegistered(peerId: String) {}
        fun onIncomingConnection(fromId: String) {}
        fun onConnectAccepted(targetId: String) {}
        fun onConnectError(message: String) {}
        fun onOfferReceived(sdp: String) {}
        fun onAnswerReceived(sdp: String) {}
        fun onIceCandidateReceived(candidate: IceCandidateData) {}
        fun onSessionEnded(reason: String) {}
        fun onError(err: String) {}
    }

    constructor(serverUrl: String, callback: SignalingCallback) : this(serverUrl) {
        addListener(callback)
    }

    fun addListener(callback: SignalingCallback) {
        listeners.add(callback)
    }

    fun removeListener(callback: SignalingCallback) {
        listeners.remove(callback)
    }

    fun isConnected(): Boolean = isConnected

    private val reconnectRunnable = Runnable {
        if (!isDestroyed && !isConnected) {
            Log.i(TAG, "Attempting auto-reconnect to signaling server...")
            connect()
        }
    }

    private fun scheduleReconnect() {
        if (isDestroyed) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, 3000)
    }

    fun connect() {
        if (isDestroyed) return
        try {
            val request = Request.Builder().url(serverUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "Signaling WebSocket connected to: $serverUrl")
                    isConnected = true

                    // Auto re-register if previously registered
                    lastRegisteredId?.let { id ->
                        register(id, "", lastRegisteredDeviceName ?: "", lastRegisteredIsHost)
                    }

                    mainHandler.post {
                        for (cb in listeners) cb.onConnected()
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val msg = gson.fromJson(text, SignalingMessage::class.java)
                        mainHandler.post {
                            for (cb in listeners) {
                                when (msg.type) {
                                    "registered" -> cb.onRegistered(msg.peerId ?: "")
                                    "incoming_connection" -> cb.onIncomingConnection(msg.fromId ?: "")
                                    "connect_accepted" -> cb.onConnectAccepted(msg.targetId ?: "")
                                    "connect_error" -> cb.onConnectError(msg.message ?: "Connection failed")
                                    "offer" -> msg.sdp?.let { cb.onOfferReceived(it) }
                                    "answer" -> msg.sdp?.let { cb.onAnswerReceived(it) }
                                    "ice_candidate" -> msg.candidate?.let { cb.onIceCandidateReceived(it) }
                                    "session_ended" -> cb.onSessionEnded(msg.message ?: "Session ended")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing signaling message: ${e.message}")
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Signaling connection failure: ${t.message}. Reconnecting in 3s...")
                    isConnected = false
                    mainHandler.post {
                        for (cb in listeners) cb.onError(t.message ?: "Network error")
                    }
                    scheduleReconnect()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Signaling WebSocket closed ($code: $reason). Reconnecting in 3s...")
                    isConnected = false
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket: ${e.message}")
            scheduleReconnect()
        }
    }

    fun register(peerId: String, pin: String = "", deviceName: String = "", isHost: Boolean = true) {
        lastRegisteredId = peerId
        lastRegisteredDeviceName = deviceName
        lastRegisteredIsHost = isHost

        val payload = SignalingMessage(
            type = "register",
            peerId = peerId,
            pin = pin,
            deviceName = deviceName,
            isHost = isHost
        )
        send(payload)
    }

    fun requestConnect(targetId: String, pin: String = "") {
        val payload = SignalingMessage(type = "connect_request", targetId = targetId, pin = pin)
        send(payload)
    }

    fun sendOffer(sdp: String) {
        val payload = SignalingMessage(type = "offer", sdp = sdp)
        send(payload)
    }

    fun sendAnswer(sdp: String) {
        val payload = SignalingMessage(type = "answer", sdp = sdp)
        send(payload)
    }

    fun sendIceCandidate(candidate: IceCandidateData) {
        val payload = SignalingMessage(type = "ice_candidate", candidate = candidate)
        send(payload)
    }

    fun endSession() {
        val payload = SignalingMessage(type = "disconnect_session")
        send(payload)
    }

    private fun send(message: SignalingMessage) {
        val json = gson.toJson(message)
        webSocket?.send(json)
    }

    fun destroy() {
        isDestroyed = true
        isConnected = false
        mainHandler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "App closed")
        client.dispatcher.executorService.shutdown()
    }
}
