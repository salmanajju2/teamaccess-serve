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

        // ── Pending offer/ICE buffered before service starts
        @Volatile var pendingOffer: String? = null
        @Volatile var pendingAnswerCallback: ((String) -> Unit)? = null
        val pendingIceCandidates = mutableListOf<com.teamaccess.remote.model.IceCandidateData>()

        /**
         * Called by ConnectionManager when a WebRTC offer arrives.
         * If service is running → handle immediately on main thread.
         * If not → buffer it until screen capture starts.
         */
        fun handleOffer(sdp: String, onAnswer: (String) -> Unit) {
            val svc = instance
            if (svc != null && svc.webRtcClient != null) {
                Log.d(TAG, "handleOffer: service running — processing immediately")
                Handler(Looper.getMainLooper()).post {
                    svc.processOffer(sdp, onAnswer)
                }
            } else {
                Log.d(TAG, "handleOffer: service not ready — buffering offer")
                pendingOffer = sdp
                pendingAnswerCallback = onAnswer
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

        init {
            try { System.loadLibrary("teamaccess_native") } catch (e: Throwable) {
                Log.w(TAG, "Native lib load note: ${e.message}")
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
            stopScreenSharing()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

            if (resultData != null && resultCode != 0) {
                Log.i(TAG, "ACTION_START — starting screen capture (resultCode=$resultCode)")
                startForegroundNotification()
                initWebRtcAndCapture(resultCode, resultData)
                isRunning = true
            } else {
                Log.e(TAG, "ACTION_START received but resultData is null or resultCode=0")
            }
        }

        return START_STICKY
    }

    // ══════════════════════════════════════
    //  FOREGROUND NOTIFICATION
    // ══════════════════════════════════════
    private fun startForegroundNotification() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TeamAccess Remote — Screen Sharing Active")
            .setContentText("Your screen is being securely streamed")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ══════════════════════════════════════
    //  WEBRTC + SCREEN CAPTURE INIT
    // ══════════════════════════════════════
    private fun initWebRtcAndCapture(resultCode: Int, resultData: Intent) {
        try {
            eglBase = EglBase.create()
            webRtcClient = WebRtcClient(applicationContext, eglBase!!, this)
            webRtcClient!!.initPeerConnection(isInitiator = false)

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            // 720p 30fps — good quality, low latency
            val captureWidth = 720
            val captureHeight = (720 * metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()).toInt()
            val fps = 30

            videoCapturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped externally")
                    mainHandler.post { stopScreenSharing(); stopSelf() }
                }
            })

            val factory = webRtcClient!!.getFactory()
            val surfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase!!.eglBaseContext)
            videoSource = factory.createVideoSource(videoCapturer!!.isScreencast)

            videoCapturer!!.initialize(surfaceHelper, applicationContext, videoSource!!.capturerObserver)
            videoCapturer!!.startCapture(captureWidth, captureHeight, fps)

            localVideoTrack = factory.createVideoTrack("ARDAMS_video0", videoSource)
            webRtcClient!!.addVideoTrack(localVideoTrack!!)

            Log.i(TAG, "Screen capture started: ${captureWidth}x${captureHeight}@${fps}fps")

            // Now flush any buffered offer/ICE
            flushPendingOffer()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to init WebRTC/screen capture: ${e.message}", e)
        }
    }

    private fun flushPendingOffer() {
        val offer = pendingOffer
        val cb = pendingAnswerCallback
        if (offer != null && cb != null) {
            Log.i(TAG, "Flushing buffered offer after screen capture started")
            processOffer(offer, cb)
            pendingOffer = null
            pendingAnswerCallback = null
        }
        synchronized(pendingIceCandidates) {
            for (cand in pendingIceCandidates) {
                val ic = IceCandidate(cand.sdpMid, cand.sdpMLineIndex, cand.sdp)
                webRtcClient?.addIceCandidate(ic)
            }
            pendingIceCandidates.clear()
        }
    }

    // ══════════════════════════════════════
    //  PROCESS INCOMING WEBRTC OFFER
    // ══════════════════════════════════════
    fun processOffer(sdp: String, onAnswer: (String) -> Unit) {
        val client = webRtcClient
        if (client == null) {
            Log.e(TAG, "processOffer: webRtcClient is null — buffering")
            pendingOffer = sdp
            pendingAnswerCallback = onAnswer
            return
        }

        // Reset peer connection for a fresh session
        try {
            client.resetPeerConnection()
            localVideoTrack?.let { client.addVideoTrack(it) }
        } catch (e: Exception) {
            Log.e(TAG, "resetPeerConnection error: ${e.message}")
        }

        // Set remote description → then create answer
        client.setRemoteDescription(SessionDescription.Type.OFFER, sdp) {
            client.createAnswer { answerDesc ->
                Log.i(TAG, "Answer SDP created — sending back")
                onAnswer(answerDesc.description)
            }
        }
    }

    // ══════════════════════════════════════
    //  STOP / CLEANUP
    // ══════════════════════════════════════
    private fun stopScreenSharing() {
        isRunning = false
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
        } catch (e: Exception) { Log.w(TAG, "videoCapturer stop: ${e.message}") }
        try {
            videoSource?.dispose()
            videoSource = null
        } catch (e: Exception) { Log.w(TAG, "videoSource dispose: ${e.message}") }
        try {
            webRtcClient?.close()
            webRtcClient = null
        } catch (e: Exception) { Log.w(TAG, "webRtcClient close: ${e.message}") }
        try {
            eglBase?.release()
            eglBase = null
        } catch (e: Exception) { Log.w(TAG, "eglBase release: ${e.message}") }
        Log.i(TAG, "Screen sharing stopped cleanly")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopScreenSharing()
        try { nativeRelease() } catch (e: Throwable) {}
    }

    // ══════════════════════════════════════
    //  NOTIFICATION CHANNEL
    // ══════════════════════════════════════
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
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
            candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp
        )
        com.teamaccess.remote.network.ConnectionManager.getInstance(applicationContext)
            .sendIceCandidate(data)
    }

    override fun onIceConnected() {
        Log.i(TAG, "✅ WebRTC ICE CONNECTED — stream should be live!")
    }

    override fun onIceDisconnected() {
        Log.w(TAG, "⚠️ WebRTC ICE disconnected")
    }

    override fun onRemoteTrackAdded(videoTrack: VideoTrack) {
        Log.d(TAG, "Remote track added (unused on host side)")
    }

    override fun onDataChannelMessage(buffer: ByteBuffer) {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        RemoteInputAccessibilityService.instance?.injectRemoteTouch(bytes)
    }
}
