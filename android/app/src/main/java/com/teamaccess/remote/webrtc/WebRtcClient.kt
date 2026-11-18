package com.teamaccess.remote.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer

class WebRtcClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: WebRtcListener
) {
    private val TAG = "WebRtcClient"

    interface WebRtcListener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onIceConnected()
        fun onIceDisconnected()
        fun onRemoteTrackAdded(videoTrack: VideoTrack)
        fun onDataChannelMessage(buffer: ByteBuffer)
    }

    private var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var isRemoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // High-profile H264 often negotiates and then sends no frames. Baseline + VP8 is reliable.
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun initPeerConnection(isInitiator: Boolean) {
        val stun = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
        ).map { PeerConnection.IceServer.builder(it).createIceServer() }

        // Public TURN so mobile networks (symmetric NAT) can still deliver the screen.
        val turnUrls = listOf(
            "turn:openrelay.metered.ca:80",
            "turn:openrelay.metered.ca:443",
            "turn:openrelay.metered.ca:443?transport=tcp"
        )
        val turn = turnUrls.map {
            PeerConnection.IceServer.builder(it)
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        }
        val iceServers = stun + turn

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalIceCandidate(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "IceConnectionState: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> listener.onIceConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> listener.onIceDisconnected()
                    else -> {}
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) listener.onRemoteTrackAdded(track)
            }

            override fun onDataChannel(dc: DataChannel) { setupDataChannel(dc) }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })

        if (isInitiator) {
            val init = DataChannel.Init().apply { ordered = true }
            dataChannel = peerConnection?.createDataChannel("inputChannel", init)
            dataChannel?.let { setupDataChannel(it) }
        }
    }

    private fun setupDataChannel(dc: DataChannel) {
        this.dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() { Log.d(TAG, "DataChannel state: ${dc.state()}") }
            override fun onMessage(buffer: DataChannel.Buffer) {
                listener.onDataChannelMessage(buffer.data)
            }
        })
    }

    fun addVideoTrack(videoTrack: VideoTrack) {
        try {
            val sender = peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))
            tuneSenderBitrate(sender, 2_500_000)
        } catch (e: Exception) {
            Log.e(TAG, "addVideoTrack error: ${e.message}")
        }
    }

    /** Prefer VP8 so the browser actually decodes the screen instead of a broken H264 profile. */
    fun preferReliableVideoCodec() {
        try {
            val caps = peerConnectionFactory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            val preferred = caps.codecs.sortedBy { codec ->
                when (codec.name.uppercase()) {
                    "VP8" -> 0
                    "VP9" -> 1
                    "H264" -> 2
                    else -> 3
                }
            }
            peerConnection?.transceivers?.forEach { transceiver ->
                if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                    transceiver.setCodecPreferences(preferred)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "preferReliableVideoCodec: ${e.message}")
        }
    }

    private fun tuneSenderBitrate(sender: RtpSender?, maxBps: Int) {
        if (sender == null) return
        try {
            val params = sender.parameters
            if (params.encodings.isNullOrEmpty()) return
            for (encoding in params.encodings) {
                encoding.maxBitrateBps = maxBps
            }
            sender.parameters = params
        } catch (e: Exception) {
            Log.w(TAG, "tuneSenderBitrate: ${e.message}")
        }
    }

    fun addAudioTrack(audioTrack: AudioTrack) {
        try {
            peerConnection?.addTrack(audioTrack, listOf("ARDAMS"))
        } catch (e: Exception) {
            Log.e(TAG, "addAudioTrack error: ${e.message}")
        }
    }

    /** Send raw touch/input bytes over DataChannel to the remote device */
    fun sendInputData(data: ByteArray) {
        try {
            val dc = dataChannel ?: return
            if (dc.state() != DataChannel.State.OPEN) return
            val buf = ByteBuffer.wrap(data)
            dc.send(DataChannel.Buffer(buf, true))
        } catch (e: Exception) {
            Log.w(TAG, "sendInputData error: ${e.message}")
        }
    }

    /** Create a WebRTC offer (used by browser-side viewer / RemoteViewActivity) */
    fun createOffer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, desc)
                callback(desc)
            }
            override fun onCreateFailure(s: String) {
                Log.e(TAG, "createOffer failed: $s")
            }
        }, constraints)
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                // Keep the SDP untouched. Bandwidth lines inserted above m=video
                // make the browser reject the answer, so the screen stays black.
                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, desc)
                callback(desc)
            }
            override fun onCreateFailure(s: String) {
                Log.e(TAG, "createAnswer failed: $s")
            }
        }, constraints)
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onDone: (() -> Unit)? = null) {
        val desc = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                Log.i(TAG, "Remote desc set. Flushing ${pendingRemoteCandidates.size} ICE candidates")
                for (cand in pendingRemoteCandidates) peerConnection?.addIceCandidate(cand)
                pendingRemoteCandidates.clear()
                onDone?.invoke()
            }
            override fun onSetFailure(s: String) {
                Log.e(TAG, "setRemoteDescription FAILED: $s")
            }
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (isRemoteDescriptionSet && peerConnection != null) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingRemoteCandidates.add(candidate)
        }
    }

    fun getFactory(): PeerConnectionFactory = peerConnectionFactory

    fun resetPeerConnection() {
        try {
            isRemoteDescriptionSet = false
            pendingRemoteCandidates.clear()
            dataChannel?.close()
            dataChannel = null
            peerConnection?.close()
            peerConnection = null
        } catch (e: Exception) {
            Log.w(TAG, "resetPeerConnection: ${e.message}")
        }
        initPeerConnection(isInitiator = false)
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        peerConnection = null
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String) { Log.e("SdpObserver", "onCreateFailure: $s") }
        override fun onSetFailure(s: String) { Log.e("SdpObserver", "onSetFailure: $s") }
    }
}
