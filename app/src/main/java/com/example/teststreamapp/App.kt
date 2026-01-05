    package com.example.teststreamapp

    import android.app.Application
    import android.app.NotificationChannel
    import android.app.NotificationManager
    import android.os.Build
    import android.util.Log
    import livekit.org.webrtc.*

    class App : Application() {

        lateinit var eglBase: EglBase
        lateinit var factory: PeerConnectionFactory

        override fun onCreate() {
            super.onCreate()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "stream_channel",
                    "Streaming",
                    NotificationManager.IMPORTANCE_LOW
                )

                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(this)
                    .createInitializationOptions()
            )

            eglBase = EglBase.create()

            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        eglBase.eglBaseContext,
                        true,
                        true
                    )
                )
                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(
                        eglBase.eglBaseContext
                    )
                )
                .createPeerConnectionFactory()

            Log.d("RTC", "✅ GLOBAL WebRTC READY")
        }
    }
