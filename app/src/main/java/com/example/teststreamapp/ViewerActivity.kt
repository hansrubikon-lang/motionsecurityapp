package com.example.teststreamapp

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import livekit.org.webrtc.*
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest

class ViewerActivity : AppCompatActivity() {
    private var lastOfferId: Long = -1
    private var acceptedOfferId: Long = -1
    private var readySent = false
    private lateinit var audioSource: AudioSource
    private lateinit var audioTrack: AudioTrack
    private lateinit var btnPushToTalk: ImageButton

    private val TAG = "RTC_VIEWER"
    private val db = FirebaseFirestore.getInstance()

    private lateinit var pc: PeerConnection
    private lateinit var remoteRenderer: SurfaceViewRenderer
    private var remoteTrack: VideoTrack? = null

    private val app get() = application as App
    private val factory get() = app.factory
    private val eglBase get() = app.eglBase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1001
            )
        }


        setContentView(R.layout.activity_viewer)
        btnPushToTalk = findViewById(R.id.btnPushToTalk) // ✅ DAS FEHLTE

        resetState()

        remoteRenderer = findViewById(R.id.remoteRenderer)
        remoteRenderer.init(eglBase.eglBaseContext, null)
        remoteRenderer.setEnableHardwareScaler(true)

        createPeerConnection()




        listenForOffer()
        listenForIce()

        Log.d(TAG, "👀 VIEWER READY")
    }
    private fun initViewerAudio() {
        if (::audioTrack.isInitialized) return

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }

        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("VIEWER_AUDIO", audioSource)
        audioTrack.setEnabled(false)

        pc.addTrack(audioTrack)

        // ✅ PUSH-TO-TALK ERST JETZT
        btnPushToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    audioTrack.setEnabled(true)
                    Log.d(TAG, "🎙️ TALK")
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    audioTrack.setEnabled(false)
                    Log.d(TAG, "🔇 SILENT")
                }
            }
            true
        }

        Log.d(TAG, "🎙️ VIEWER AUDIO INITIALIZED")
    }



    private fun markViewerReady() {
        db.collection("rooms")
            .document(AppConfig.ROOM_ID)
            .set(
                mapOf(
                    "viewerReadyTs" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
    }

    private fun attachRemoteRenderer() {
        remoteRenderer.post {
            remoteTrack?.let {
                it.removeSink(remoteRenderer)
                it.addSink(remoteRenderer)
            }
        }
    }

    private fun createPeerConnection() {

        pc = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(
                listOf(
                    PeerConnection.IceServer
                        .builder("stun:stun.l.google.com:19302")
                        .createIceServer()
                )
            ),

            object : PeerConnection.Observer {

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                    val track = receiver.track() as? VideoTrack ?: return
                    remoteTrack = track
                    Log.d(TAG, "🎥 REMOTE TRACK RECEIVED")
                    attachRemoteRenderer()
                }

                override fun onIceCandidate(c: IceCandidate) {
                    db.collection("rooms")
                        .document(AppConfig.ROOM_ID)
                        .collection("candidates")
                        .add(
                            mapOf(
                                "from" to "viewer",
                                "sdp" to c.sdp,
                                "mid" to c.sdpMid,
                                "mline" to c.sdpMLineIndex
                            )

                        )
                }

                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                    Log.d(TAG, "PC = $s")
                }

                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE = $s")
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(p0: Array<IceCandidate>) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onAddStream(p0: MediaStream) {}
                override fun onRemoveStream(p0: MediaStream) {}
                override fun onDataChannel(p0: DataChannel) {}
                override fun onRenegotiationNeeded() {}
            }
        )!!


    }

    private fun resetState() {
        lastOfferId = -1
        acceptedOfferId = -1
    }

    private fun listenForOffer() {
        db.collection("rooms")
            .document(AppConfig.ROOM_ID)
            .addSnapshotListener { snap, _ ->

                val sdp = snap?.getString(AppConfig.FIELD_OFFER)
                    ?: return@addSnapshotListener

                val offerId = snap.getLong(AppConfig.FIELD_OFFER_ID)
                    ?: return@addSnapshotListener

                // 🔒 NEU – DAS IST DER FIX
                if (!::pc.isInitialized) return@addSnapshotListener

                val state = pc.signalingState()
                if (state != PeerConnection.SignalingState.STABLE) {
                    Log.d(TAG, "⚠️ IGNORE OFFER – state=$state")
                    return@addSnapshotListener
                }


                // 🔐 Alte Offers ignorieren
                if (offerId <= lastOfferId) {
                    Log.d(TAG, "⏭️ IGNORE OLD OFFER id=$offerId")
                    return@addSnapshotListener
                }

                lastOfferId = offerId
                Log.d(TAG, "📥 ACCEPT OFFER id=$offerId")

                pc.setRemoteDescription(
                    object : SdpObserver {
                        override fun onSetSuccess() {
                            initViewerAudio()   // 🔥 HIER IST DER MAGIC-PUNKT
                            createAnswer()
                        }

                        override fun onSetFailure(e: String) {
                            Log.e(TAG, "❌ OFFER SET FAIL $e")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    },
                    SessionDescription(SessionDescription.Type.OFFER, sdp)
                )
            }
    }




    private fun createAnswer() {
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(this, sdp)
                Log.d(TAG, "SDP ANSWER SENT:\n${sdp.description}")

                db.collection("rooms")
                    .document(AppConfig.ROOM_ID)
                    .set(
                        mapOf(
                            AppConfig.FIELD_ANSWER to sdp.description,
                            AppConfig.FIELD_OFFER_ID to lastOfferId
                        ),
                        SetOptions.merge()
                    )

                Log.d(TAG, "📤 ANSWER SENT")
            }

            override fun onCreateFailure(e: String) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun listenForIce() {
        db.collection("rooms")
            .document(AppConfig.ROOM_ID)
            .collection("candidates")
            .addSnapshotListener { snap, _ ->
                snap?.documents?.forEach { doc ->

                    if (doc.getString("from") == "viewer") return@forEach

                    // 🔐 SAFETY 1: PC existiert?
                    if (!::pc.isInitialized) {
                        Log.d(TAG, "❌ IGNORE ICE – pc not initialized")
                        return@forEach
                    }

                    // 🔐 SAFETY 2: PC noch offen?
                    if (pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
                        Log.d(TAG, "❌ IGNORE ICE – pc closed")
                        return@forEach
                    }

                    if (pc.iceConnectionState() == PeerConnection.IceConnectionState.CLOSED) {
                        Log.d(TAG, "❌ IGNORE ICE – ice closed")
                        return@forEach
                    }

                    val candidate = IceCandidate(
                        doc.getString("mid"),
                        doc.getLong("mline")!!.toInt(),
                        doc.getString("sdp")!!
                    )

                    pc.addIceCandidate(candidate)
                }
            }
    }


    // ----------------------------------------------------
    // LIFECYCLE (FINAL)
    // ----------------------------------------------------

    override fun onResume() {
        super.onResume()
        if (!readySent) {
            markViewerReady()
            readySent = true
        }

        remoteTrack?.let {
            remoteRenderer.post {
                it.removeSink(remoteRenderer)
                it.addSink(remoteRenderer)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        remoteTrack?.removeSink(remoteRenderer)
    }

    override fun onDestroy() {
        super.onDestroy()
        readySent = false

        acceptedOfferId = -1
        lastOfferId = -1


        if (::pc.isInitialized) {
            pc.close()
            pc.dispose()
        }
    }

}