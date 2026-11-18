package com.teamaccess.remote

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.teamaccess.remote.databinding.ActivityRemoteViewBinding
import com.teamaccess.remote.model.IceCandidateData
import com.teamaccess.remote.model.TouchConstants
import com.teamaccess.remote.webrtc.SignalingClient
import com.teamaccess.remote.webrtc.WebRtcClient
import org.webrtc.*
import java.nio.ByteBuffer

class RemoteViewActivity : AppCompatActivity(), WebRtcClient.WebRtcListener, SignalingClient.SignalingCallback {

    companion object {
        const val EXTRA_PARTNER_ID = "EXTRA_PARTNER_ID"
        const val EXTRA_PARTNER_PIN = "EXTRA_PARTNER_PIN"
        const val EXTRA_SERVER_URL = "EXTRA_SERVER_URL"

        init {
            System.loadLibrary("teamaccess_native")
        }
    }

    private external fun nativeSerializeTouch(
        action: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        extra: Int,
        timestampMs: Int
    ): ByteArray

    private lateinit var binding: ActivityRemoteViewBinding
    private lateinit var eglBase: EglBase
    private var webRtcClient: WebRtcClient? = null
    private var signalingClient: SignalingClient? = null

    private var partnerId: String = ""
    private var partnerPin: String = ""
    private var serverUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        partnerId = intent.getStringExtra(EXTRA_PARTNER_ID) ?: ""
        partnerPin = intent.getStringExtra(EXTRA_PARTNER_PIN) ?: ""
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "wss://teamaccess-serve.onrender.com/ws"

        initVideoRenderer()
        setupTouchOverlay()
        setupNavigationControls()
        connectToPartner()
    }

    private fun initVideoRenderer() {
        eglBase = EglBase.create()
        binding.remoteVideoView.init(eglBase.eglBaseContext, null)
        binding.remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.remoteVideoView.setEnableHardwareScaler(true)
    }

    private fun connectToPartner() {
        binding.pbLoading.visibility = View.VISIBLE

        webRtcClient = WebRtcClient(this, eglBase, this)
        webRtcClient?.initPeerConnection(isInitiator = true)

        signalingClient = SignalingClient(serverUrl, this)
        signalingClient?.connect()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchOverlay() {
        binding.touchOverlayView.setOnTouchListener { view, event ->
            val width = view.width.toFloat()
            val height = view.height.toFloat()

            if (width > 0 && height > 0) {
                val normX = (event.x / width).coerceIn(0f, 1f)
                val normY = (event.y / height).coerceIn(0f, 1f)
                val actionCode = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> TouchConstants.ACTION_DOWN
                    MotionEvent.ACTION_MOVE -> TouchConstants.ACTION_MOVE
                    MotionEvent.ACTION_UP -> TouchConstants.ACTION_UP
                    else -> TouchConstants.ACTION_CANCEL
                }

                val packet = nativeSerializeTouch(
                    actionCode,
                    event.getPointerId(0),
                    normX,
                    normY,
                    0,
                    (System.currentTimeMillis() % 65535).toInt()
                )

                webRtcClient?.sendInputData(packet)
            }
            true
        }
    }

    private fun setupNavigationControls() {
        binding.btnRemoteBack.setOnClickListener {
            sendVirtualKey(TouchConstants.KEY_BACK)
        }

        binding.btnRemoteHome.setOnClickListener {
            sendVirtualKey(TouchConstants.KEY_HOME)
        }

        binding.btnDisconnect.setOnClickListener {
            finish()
        }
    }

    private fun sendVirtualKey(keyCode: Int) {
        val packet = nativeSerializeTouch(
            TouchConstants.KEY_EVENT,
            0,
            0f,
            0f,
            keyCode,
            0
        )
        webRtcClient?.sendInputData(packet)
    }

    // Signaling Callbacks
    override fun onConnected() {
        signalingClient?.requestConnect(partnerId, partnerPin)
    }

    override fun onRegistered(peerId: String) {}

    override fun onIncomingConnection(fromId: String) {}

    override fun onConnectAccepted(targetId: String) {
        // Create WebRTC Offer
        webRtcClient?.createOffer { offerDesc ->
            signalingClient?.sendOffer(offerDesc.description)
        }
    }

    override fun onConnectError(message: String) {
        runOnUiThread {
            binding.pbLoading.visibility = View.GONE
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onOfferReceived(sdp: String) {}

    override fun onAnswerReceived(sdp: String) {
        webRtcClient?.setRemoteDescription(SessionDescription.Type.ANSWER, sdp)
    }

    override fun onIceCandidateReceived(candidate: IceCandidateData) {
        val iceCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        webRtcClient?.addIceCandidate(iceCandidate)
    }

    override fun onSessionEnded(reason: String) {
        runOnUiThread {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onError(err: String) {
        runOnUiThread {
            Toast.makeText(this, "Network Error: $err", Toast.LENGTH_SHORT).show()
        }
    }

    // WebRTC Callbacks
    override fun onLocalIceCandidate(candidate: IceCandidate) {
        val data = IceCandidateData(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        signalingClient?.sendIceCandidate(data)
    }

    override fun onIceConnected() {
        runOnUiThread {
            binding.pbLoading.visibility = View.GONE
        }
    }

    override fun onIceDisconnected() {
        runOnUiThread {
            Toast.makeText(this, "Partner disconnected", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onRemoteTrackAdded(videoTrack: VideoTrack) {
        runOnUiThread {
            videoTrack.addSink(binding.remoteVideoView)
        }
    }

    override fun onDataChannelMessage(buffer: ByteBuffer) {}

    override fun onDestroy() {
        super.onDestroy()
        signalingClient?.endSession()
        signalingClient?.destroy()
        webRtcClient?.close()
        binding.remoteVideoView.release()
        eglBase.release()
    }
}
