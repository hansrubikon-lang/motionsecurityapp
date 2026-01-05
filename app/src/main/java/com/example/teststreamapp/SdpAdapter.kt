package com.example.teststreamapp

import android.util.Log
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription

open class SdpAdapter(
    private val tag: String = "SDP"
) : SdpObserver {

    override fun onCreateSuccess(sdp: SessionDescription) {
        Log.d(tag, "SDP create success")
    }

    override fun onSetSuccess() {
        Log.d(tag, "SDP set success")
    }

    override fun onCreateFailure(error: String) {
        Log.e(tag, "SDP create failure: $error")
    }

    override fun onSetFailure(error: String) {
        Log.e(tag, "SDP set failure: $error")
    }
}