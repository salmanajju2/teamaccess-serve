package com.teamaccess.remote.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.teamaccess.remote.MainActivity
import com.teamaccess.remote.model.IceCandidateData
import com.teamaccess.remote.service.ScreenCaptureService
import com.teamaccess.remote.utils.IdGenerator
import com.teamaccess.remote.webrtc.SignalingClient

class ConnectionManager private constructor(private val appContext: Context) : SignalingClient.SignalingCallback {

    companion object {
        private const val TAG = "ConnectionManager"
        const val SERVER_URL = "wss://teamaccess-serve.onrender.com/ws"

        @Volatile
        private var INSTANCE: ConnectionManager? = null

        fun getInstance(context: Context): ConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val deviceId: String = IdGenerator.getOrCreateDeviceId(appContext)
    val deviceName: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    val signalingClient: SignalingClient = SignalingClient(SERVER_URL)

    private var wakeLock: PowerManager.WakeLock? = null

    init {
        acquireWakeLock()
        signalingClient.addListener(this)
        signalingClient.connect()
        Log.i(TAG, "ConnectionManager initialized — deviceId=$deviceId deviceName=$deviceName")
    }

    private fun acquireWakeLock() {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TeamAccess::ConnectionWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24-hour WakeLock
            }
            Log.d(TAG, "WakeLock acquired for 24/7 background operation.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    override fun onConnected() {
        Log.i(TAG, "Signaling connected. Registering device: $deviceId ($deviceName)")
        signalingClient.register(
            peerId = deviceId,
            pin = "",
            deviceName = deviceName,
            isHost = true
        )
    }

    override fun onRegistered(peerId: String) {
        Log.i(TAG, "Registered on server as host — peerId=$peerId")
    }

    /**
     * Incoming connection from web dashboard.
     * If screen is already being shared → WebRTC negotiation will proceed via offer/answer.
     * If not sharing yet → show notification so user can tap to allow screen capture.
     */
    override fun onIncomingConnection(fromId: String) {
        Log.i(TAG, "Incoming connection from $fromId")
        if (ScreenCaptureService.isRunning) {
            // Screen is already captured — connection will proceed automatically
            Log.i(TAG, "Screen capture already active — WebRTC will proceed.")
        } else {
            // Show a heads-up notification asking user to open app and start sharing
            showIncomingConnectionNotification()
        }
    }

    private fun showIncomingConnectionNotification() {
        try {
            val channelId = "teamaccess_incoming"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Incoming Remote Connections",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Alert when a remote viewer connects" }
                val nm = appContext.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
            val intent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                appContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = android.app.Notification.Builder(appContext, channelId)
                .setContentTitle("TeamAccess — Incoming Remote Session")
                .setContentText("Tap to open app and allow screen sharing")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm.notify(9001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show incoming connection notification: ${e.message}")
        }
    }

    override fun onOfferReceived(sdp: String) {
        Log.i(TAG, "WebRTC offer received — forwarding to ScreenCaptureService")
        ScreenCaptureService.handleOffer(sdp) { answerSdp ->
            signalingClient.sendAnswer(answerSdp)
        }
    }

    override fun onIceCandidateReceived(candidate: IceCandidateData) {
        ScreenCaptureService.handleRemoteIceCandidate(candidate)
    }

    override fun onSessionEnded(reason: String) {
        Log.i(TAG, "Session ended by remote peer: $reason")
    }

    override fun onError(err: String) {
        Log.w(TAG, "Signaling error: $err")
    }

    fun sendAnswer(sdp: String) {
        signalingClient.sendAnswer(sdp)
    }

    fun sendIceCandidate(candidate: IceCandidateData) {
        signalingClient.sendIceCandidate(candidate)
    }
}
