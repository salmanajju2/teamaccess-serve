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

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true, // enableIntelVp8Encoder
            true  // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun initPeerConnection(isInitiator: Boolean) {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalIceCandidate(candidate)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "IceConnectionState: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> listener.onIceConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED -> listener.onIceDisconnected()
                    else -> {}
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    listener.onRemoteTrackAdded(track)
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                setupDataChannel(dc)
            }

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
            val init = DataChannel.Init().apply {
                ordered = true
                maxRetransmits = 0 // Lowest latency for real-time input
            }
            dataChannel = peerConnection?.createDataChannel("inputChannel", init)
            dataChannel?.let { setupDataChannel(it) }
        }
    }

    private fun setupDataChannel(dc: DataChannel) {
        this.dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Log.d(TAG, "DataChannel state: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                listener.onDataChannelMessage(buffer.data)
            }
        })
    }

    fun sendInputData(bytes: ByteArray) {
        dataChannel?.let {
            if (it.state() == DataChannel.State.OPEN) {
                val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), true)
                it.send(buffer)
            }
        }
    }

    fun addVideoTrack(videoTrack: VideoTrack) {
        peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))
    }

    fun createOffer(callback: (SessionDescription) -> Unit) {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, desc)
                callback(desc)
            }
        }, sdpConstraints)
    }

    private var isRemoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private fun boostSdpBitrate(sdp: String): String {
        var modified = sdp
        if (modified.contains("m=video")) {
            modified = modified.replace("m=video", "b=AS:5000\r\nb=TIAS:5000000\r\nm=video")
        }
        if (modified.contains("a=fmtp:")) {
            modified = modified.replace(
                Regex("(a=fmtp:\\d+ [^\r\n]+)"),
                "$1;x-google-min-bitrate=2500;x-google-start-bitrate=4000;x-google-max-bitrate=7000"
            )
        }
        return modified
    }

    fun createAnswer(callback: (SessionDescription) -> Unit) {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                val boostedSdp = boostSdpBitrate(desc.description)
                val boostedDesc = SessionDescription(desc.type, boostedSdp)
                peerConnection?.setLocalDescription(object : SdpObserverAdapter() {}, boostedDesc)
                callback(boostedDesc)
            }
        }, sdpConstraints)
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String, onDone: (() -> Unit)? = null) {
        val desc = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                Log.i(TAG, "Remote description set. Flushing ${pendingRemoteCandidates.size} queued ICE candidates.")
                for (cand in pendingRemoteCandidates) {
                    peerConnection?.addIceCandidate(cand)
                }
                pendingRemoteCandidates.clear()
                onDone?.invoke()
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
            Log.w(TAG, "Note closing previous peer connection: ${e.message}")
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
        override fun onCreateFailure(s: String) { Log.e("SdpObserverAdapter", "onCreateFailure: $s") }
        override fun onSetFailure(s: String) { Log.e("SdpObserverAdapter", "onSetFailure: $s") }
    }
}
