package com.example.teststreamapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import livekit.org.webrtc.*

class StreamingActivity : AppCompatActivity() {

    private val TAG = "RTC_SENDER"
    private val db = FirebaseFirestore.getInstance()
    private var offerId = 0L
    private var listenersRegistered = false

    private var lastViewerReadyTs = 0L

    private lateinit var capturer: VideoCapturer
    private lateinit var videoSource: VideoSource
    private lateinit var videoTrack: VideoTrack
    private lateinit var textureHelper: SurfaceTextureHelper
    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var pc: PeerConnection

    private var webRtcStarted = false
    private var cameraStarted = false

    private val app get() = application as App
    private val factory get() = app.factory
    private val eglBase get() = app.eglBase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        audioManager.isMicrophoneMute = false

        setContentView(R.layout.activity_streaming)

        localRenderer = findViewById(R.id.localRenderer)
        localRenderer.init(eglBase.eglBaseContext, null)
        localRenderer.setMirror(true)
        localRenderer.setEnableHardwareScaler(true)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                1
            )
        }
    }

    // ----------------------------------------------------
    // CAMERA (SAFE)
    // ----------------------------------------------------
    private fun startCameraPreview() {
        if (cameraStarted) return
        cameraStarted = true

        val enumerator = Camera2Enumerator(this)
        val cam = enumerator.deviceNames.first { enumerator.isFrontFacing(it) }

        capturer = enumerator.createCapturer(cam, null)
        videoSource = factory.createVideoSource(true)

        textureHelper =
            SurfaceTextureHelper.create("CameraThread", eglBase.eglBaseContext)

        capturer.initialize(textureHelper, this, videoSource.capturerObserver)
        capturer.startCapture(640, 480, 20)

        videoTrack = factory.createVideoTrack("VIDEO", videoSource)
        attachLocalRenderer()

        Log.d(TAG, "📸 CAMERA PREVIEW STARTED")
    }

    private fun attachLocalRenderer() {
        localRenderer.post {
            videoTrack.removeSink(localRenderer)
            videoTrack.addSink(localRenderer)
        }
    }

    // ----------------------------------------------------
    // VIEWER HANDSHAKE
    // ----------------------------------------------------
    private fun listenForViewerReady() {
        db.collection("rooms")
            .document(AppConfig.ROOM_ID)
            .addSnapshotListener { snap, _ ->

                val ts = snap?.getLong("viewerReadyTs") ?: return@addSnapshotListener

                if (ts == lastViewerReadyTs) return@addSnapshotListener
                lastViewerReadyTs = ts

                Log.d(TAG, "👀 VIEWER (RE)JOIN → NEW OFFER")
                ensureStreamServiceRunning()
                startWebRtc()
            }
    }


    // ----------------------------------------------------
    // WEBRTC
    // ----------------------------------------------------
    private fun startWebRtc() {
        Log.d(TAG, "♻️ START WEBRTC")

        if (::pc.isInitialized) {
            pc.close()
            pc.dispose()
        }

        clearRoom()
        createPeerConnection()

// 🔒 ALLES JETZT KONFIGURIEREN
        // 🎧 AUDIO EMPFANGEN (SEHR WICHTIG)
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            )
        )

// 🎥 VIDEO SENDEN
        pc.addTransceiver(
            videoTrack,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            )
        )


        createOffer()



        if (!listenersRegistered) {
            listenForAnswer()
            listenForIce()
            listenersRegistered = true
        }
    }



    private fun clearRoom() {
        val room = db.collection("rooms").document(AppConfig.ROOM_ID)

        room.set(
            mapOf(
                "offer" to FieldValue.delete(),
                "answer" to FieldValue.delete(),
                "viewerReady" to false,

                ),
            SetOptions.merge()
        )

        room.collection("candidates").get()
            .addOnSuccessListener {
                it.documents.forEach { d -> d.reference.delete() }
            }

        Log.d(TAG, "🧹 ROOM CLEARED")
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

                override fun onIceCandidate(c: IceCandidate) {
                    db.collection("rooms")
                        .document(AppConfig.ROOM_ID)
                        .collection("candidates")
                        .add(
                            mapOf(
                                "from" to "sender",
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
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                    val track = receiver.track()
                    if (track is AudioTrack) {
                        track.setEnabled(true)
                        track.setVolume(1.0)

                        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        audioManager.isSpeakerphoneOn = true

                        Log.d(TAG, "🎧 VIEWER AUDIO RECEIVED + PLAYOUT ENABLED")
                    }
                }


            }
        )!!


    }

    private fun createOffer() {
        offerId++

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(this, sdp)

                db.collection("rooms")
                    .document(AppConfig.ROOM_ID)
                    .set(
                        mapOf(
                            AppConfig.FIELD_OFFER to sdp.description,
                            AppConfig.FIELD_OFFER_ID to offerId
                        ),
                        SetOptions.merge()
                    )

                Log.d(TAG, "📤 OFFER SENT id=$offerId")
            }

            override fun onCreateFailure(e: String) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun listenForAnswer() {
        db.collection("rooms")
            .document(AppConfig.ROOM_ID)
            .addSnapshotListener { snap, _ ->
                val sdp = snap?.getString(AppConfig.FIELD_ANSWER)
                    ?: return@addSnapshotListener

                val answerId = snap.getLong(AppConfig.FIELD_OFFER_ID)
                    ?: return@addSnapshotListener

                if (answerId != offerId) {
                    Log.d(TAG, "⏭️ IGNORE ANSWER id=$answerId")
                    return@addSnapshotListener
                }

                if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return@addSnapshotListener

                pc.setRemoteDescription(
                    object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "📥 ANSWER SET")
                        }

                        override fun onSetFailure(e: String) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    },
                    SessionDescription(SessionDescription.Type.ANSWER, sdp)
                )
                Log.d(TAG, "SDP ANSWER:\n${pc.remoteDescription?.description}")

            }
    }

    private fun listenForIce() {
        db.collection("rooms")
            .document(AppConfig.ROOM_ID)
            .collection("candidates")
            .addSnapshotListener { snap, _ ->
                snap?.documents?.forEach {
                    if (it.getString("from") == "sender") return@forEach
                    pc.addIceCandidate(
                        IceCandidate(
                            it.getString("mid"),
                            it.getLong("mline")!!.toInt(),
                            it.getString("sdp")!!
                        )
                    )
                }
            }
    }

    private fun ensureStreamServiceRunning() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, StreamService::class.java)
        )
    }


    // ----------------------------------------------------
    // LIFECYCLE (FINAL)
    // ----------------------------------------------------
    override fun onResume() {
        super.onResume()
        ensureStreamServiceRunning()

        localRenderer.setMirror(true)
        localRenderer.setEnableHardwareScaler(true)

        // 🔥 STARTET BEIM ERSTEN MAL + NACH BACKGROUND
        if (!cameraStarted &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
            listenForViewerReady()
        }

        if (::videoTrack.isInitialized) {
            attachLocalRenderer()
        }
    }


    override fun onPause() {
        super.onPause()
        if (::videoTrack.isInitialized) {
            videoTrack.removeSink(localRenderer)
        }
    }




    override fun onDestroy() {
        super.onDestroy()
        listenersRegistered = false   // ✅ WICHTIG

        if (::pc.isInitialized) {
            pc.close()
            pc.dispose()
        }
        if (::textureHelper.isInitialized) {
            textureHelper.dispose()
        }

        if (::videoTrack.isInitialized) {
            videoTrack.removeSink(localRenderer)
        }

        if (::capturer.isInitialized) {
            try {
                try {
                    capturer.stopCapture()
                } catch (_: Exception) {}

                capturer.dispose()

            } catch (_: Exception) {}
            capturer.dispose()
        }

        if (::videoSource.isInitialized) {
            videoSource.dispose()
        }

        localRenderer.release()
    }

}
