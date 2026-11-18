package com.teamaccess.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.teamaccess.remote.MainActivity
import com.teamaccess.remote.webrtc.WebRtcClient
import org.webrtc.*
import java.nio.ByteBuffer

class ScreenCaptureService : Service(), WebRtcClient.WebRtcListener {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP  = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

        @Volatile var isRunning = false
            private set

        @Volatile var instance: ScreenCaptureService? = null
            private set

        // Pending offer/ICE buffered before service starts
        @Volatile var pendingOffer: String? = null
        @Volatile var pendingAnswerCallback: ((String) -> Unit)? = null
        val pendingIceCandidates = mutableListOf<com.teamaccess.remote.model.IceCandidateData>()

        /**
         * handleOffer — called when browser sends WebRTC offer
         * If service running: process immediately
         * If not running: buffer the offer
         */
        fun handleOffer(context: Context, sdp: String, onAnswer: (String) -> Unit) {
            val svc = instance
            if (svc != null && svc.webRtcClient != null && isRunning) {
                Log.d(TAG, "handleOffer: service running — processing immediately")
                Handler(Looper.getMainLooper()).post {
                    svc.processOffer(sdp, onAnswer)
                }
            } else {
                Log.d(TAG, "handleOffer: buffering offer — service not ready")
                pendingOffer = sdp
                pendingAnswerCallback = onAnswer
                // Show notification to tell user to open app
                showNeedScreenPermissionNotification(context)
            }
        }

        fun handleRemoteIceCandidate(candidate: com.teamaccess.remote.model.IceCandidateData) {
            val svc = instance
            if (svc?.webRtcClient != null) {
                val ic = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                svc.webRtcClient?.addIceCandidate(ic)
            } else {
                synchronized(pendingIceCandidates) {
                    pendingIceCandidates.add(candidate)
                }
            }
        }

        private fun showNeedScreenPermissionNotification(context: Context) {
            try {
                val channelId = "teamaccess_restart"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val ch = NotificationChannel(channelId, "Screen Share Alert", NotificationManager.IMPORTANCE_HIGH)
                    context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
                }
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("AUTO_START_SHARE", true)
                }
                val pi = PendingIntent.getActivity(context, 99, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = android.app.Notification.Builder(context, channelId)
                    .setContentTitle("📱 Remote Session Requested!")
                    .setContentText("Tap to allow screen sharing — someone is trying to connect")
                    .setSmallIcon(android.R.drawable.ic_menu_share)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(9002, n)
            } catch (e: Exception) {
                Log.e(TAG, "notification error: ${e.message}")
            }
        }

        init {
            try { System.loadLibrary("teamaccess_native") } catch (e: Throwable) {
                Log.w(TAG, "Native lib: ${e.message}")
            }
        }
    }

    private external fun nativeInit()
    private external fun nativeRelease()

    private var eglBase: EglBase? = null
    var webRtcClient: WebRtcClient? = null
        private set
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try { nativeInit() } catch (e: Throwable) { Log.w(TAG, "nativeInit: ${e.message}") }
        createNotificationChannel()
        Log.i(TAG, "ScreenCaptureService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY

        if (action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP received")
            isRunning = false
            stopScreenSharing()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

            if (resultData != null && resultCode != 0) {
                Log.i(TAG, "ACTION_START — starting screen capture")
                startForegroundNotification()
                initWebRtcAndCapture(resultCode, resultData)
                isRunning = true
            } else {
                Log.e(TAG, "ACTION_START: missing resultData or resultCode")
            }
        }

        return START_STICKY
    }

    // ══════════════════════════════════════
    //  FOREGROUND NOTIFICATION
    // ══════════════════════════════════════
    private fun startForegroundNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 TeamAccess — Live Screen Sharing")
            .setContentText("Your screen is being remotely accessed")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ══════════════════════════════════════
    //  WEBRTC + SCREEN + AUDIO INIT
    // ══════════════════════════════════════
    private fun initWebRtcAndCapture(resultCode: Int, resultData: Intent) {
        try {
            eglBase = EglBase.create()
            webRtcClient = WebRtcClient(applicationContext, eglBase!!, this)
            webRtcClient!!.initPeerConnection(isInitiator = false)

            // ── Screen capture setup ──
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val screenW = metrics.widthPixels.coerceAtLeast(1)
            val screenH = metrics.heightPixels.coerceAtLeast(1)
            var captureWidth = 720
            var captureHeight = (720f * screenH / screenW).toInt().coerceIn(2, 1920)
            if (captureWidth % 2 != 0) captureWidth += 1
            if (captureHeight % 2 != 0) captureHeight += 1
            val fps = 30

            videoCapturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "⚠️ MediaProjection stopped by system")
                    // Don't stop the whole service — just mark capture as stopped
                    // and show notification. Keep WebSocket alive.
                    isRunning = false
                    mainHandler.post {
                        try {
                            videoCapturer?.stopCapture()
                        } catch (e: Exception) { }
                        showNeedScreenPermissionNotification(applicationContext)
                        updateNotificationStopped()
                    }
                }
            })

            val factory = webRtcClient!!.getFactory()
            val surfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase!!.eglBaseContext)
            videoSource = factory.createVideoSource(videoCapturer!!.isScreencast)

            videoCapturer!!.initialize(surfaceHelper, applicationContext, videoSource!!.capturerObserver)
            videoCapturer!!.startCapture(captureWidth, captureHeight, fps)

            localVideoTrack = factory.createVideoTrack("ARDAMS_video0", videoSource)
            localVideoTrack!!.setEnabled(true)
            // Track is attached when the offer arrives, after setRemoteDescription.

            // Mic failure must not abort the screen. Video still has to come up.
            val micGranted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                try {
                    val audioConstraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    }
                    audioSource = factory.createAudioSource(audioConstraints)
                    localAudioTrack = factory.createAudioTrack("ARDAMS_audio0", audioSource)
                    localAudioTrack!!.setEnabled(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Mic capture failed, continuing with video only: ${e.message}")
                    localAudioTrack = null
                    audioSource = null
                }
            } else {
                Log.w(TAG, "RECORD_AUDIO not granted — screen will stream without mic")
            }

            Log.i(TAG, "✅ Screen capture started: ${captureWidth}x${captureHeight}@${fps}fps")

            // Flush any buffered offer
            flushPendingOffer()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to init WebRTC/screen capture: ${e.message}", e)
        }
    }

    private fun updateNotificationStopped() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("AUTO_START_SHARE", true)
            }
            val pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⚠️ TeamAccess — Screen Share Stopped")
                .setContentText("Tap to restart screen sharing")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: Exception) { }
    }

    private fun flushPendingOffer() {
        val offer = pendingOffer
        val cb = pendingAnswerCallback
        val iceCopy = synchronized(pendingIceCandidates) {
            val copy = pendingIceCandidates.toList()
            pendingIceCandidates.clear()
            copy
        }
        if (offer != null && cb != null) {
            Log.i(TAG, "Flushing buffered offer")
            pendingOffer = null
            pendingAnswerCallback = null
            // ICE must be applied AFTER resetPeerConnection, otherwise reset drops it.
            mainHandler.post { processOffer(offer, cb, iceCopy) }
        } else if (iceCopy.isNotEmpty()) {
            mainHandler.post {
                for (cand in iceCopy) {
                    webRtcClient?.addIceCandidate(IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp))
                }
            }
        }
    }

    // ══════════════════════════════════════
    //  PROCESS INCOMING WEBRTC OFFER
    // ══════════════════════════════════════
    fun processOffer(
        sdp: String,
        onAnswer: (String) -> Unit,
        bufferedIce: List<com.teamaccess.remote.model.IceCandidateData> = emptyList()
    ) {
        val client = webRtcClient
        if (client == null || localVideoTrack == null) {
            Log.e(TAG, "processOffer: capture not ready — buffering")
            pendingOffer = sdp
            pendingAnswerCallback = onAnswer
            if (bufferedIce.isNotEmpty()) {
                synchronized(pendingIceCandidates) { pendingIceCandidates.addAll(bufferedIce) }
            }
            return
        }

        try {
            client.resetPeerConnection()
        } catch (e: Exception) {
            Log.e(TAG, "resetPeerConnection error: ${e.message}")
        }

        // Set the browser offer first so video/audio tracks bind to those m-lines.
        // Adding tracks before setRemoteDescription creates extra m-lines and the
        // browser rejects the answer — screen stays on "Negotiating".
        client.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
            localVideoTrack?.let { client.addVideoTrack(it) }
            localAudioTrack?.let { client.addAudioTrack(it) }
            client.preferReliableVideoCodec()
            for (cand in bufferedIce) {
                client.addIceCandidate(IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp))
            }
            client.createAnswer { answerDesc ->
                Log.i(TAG, "✅ Answer SDP created")
                onAnswer(answerDesc.description)
            }
        }
    }

    // ══════════════════════════════════════
    //  STOP / CLEANUP
    // ══════════════════════════════════════
    private fun stopScreenSharing() {
        try { videoCapturer?.stopCapture(); videoCapturer?.dispose(); videoCapturer = null } catch (e: Exception) {}
        try { videoSource?.dispose(); videoSource = null } catch (e: Exception) {}
        try { audioSource?.dispose(); audioSource = null } catch (e: Exception) {}
        try { localVideoTrack?.dispose(); localVideoTrack = null } catch (e: Exception) {}
        try { localAudioTrack?.dispose(); localAudioTrack = null } catch (e: Exception) {}
        try { webRtcClient?.close(); webRtcClient = null } catch (e: Exception) {}
        try { eglBase?.release(); eglBase = null } catch (e: Exception) {}
        Log.i(TAG, "Screen sharing stopped cleanly")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        stopScreenSharing()
        try { nativeRelease() } catch (e: Throwable) {}
    }

    // ══════════════════════════════════════
    //  NOTIFICATION CHANNEL
    // ══════════════════════════════════════
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen Sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Active screen sharing session"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ══════════════════════════════════════
    //  WebRtcListener CALLBACKS
    // ══════════════════════════════════════
    override fun onLocalIceCandidate(candidate: IceCandidate) {
        val data = com.teamaccess.remote.model.IceCandidateData(
            candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        com.teamaccess.remote.network.ConnectionManager.getInstance(applicationContext)
            .sendIceCandidate(data)
    }

    override fun onIceConnected() { Log.i(TAG, "✅ ICE CONNECTED — stream live!") }
    override fun onIceDisconnected() { Log.w(TAG, "⚠️ ICE disconnected") }
    override fun onRemoteTrackAdded(videoTrack: VideoTrack) {}

    override fun onDataChannelMessage(buffer: ByteBuffer) {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        RemoteInputAccessibilityService.instance?.injectRemoteTouch(bytes)
    }
}
