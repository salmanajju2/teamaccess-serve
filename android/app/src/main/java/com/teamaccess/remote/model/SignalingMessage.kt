package com.teamaccess.remote.model

import com.google.gson.annotations.SerializedName

data class SignalingMessage(
    @SerializedName("type") val type: String,
    @SerializedName("peerId") val peerId: String? = null,
    @SerializedName("targetId") val targetId: String? = null,
    @SerializedName("fromId") val fromId: String? = null,
    @SerializedName("pin") val pin: String? = null,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("isHost") val isHost: Boolean? = null,
    @SerializedName("sdp") val sdp: String? = null,
    @SerializedName("candidate") val candidate: IceCandidateData? = null,
    @SerializedName("message") val message: String? = null
)

data class IceCandidateData(
    @SerializedName("sdpMid") val sdpMid: String,
    @SerializedName("sdpMLineIndex") val sdpMLineIndex: Int,
    @SerializedName("sdp") val sdp: String
)
