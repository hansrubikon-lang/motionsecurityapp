package com.example.teststreamapp

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class StreamService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()

        val notification = NotificationCompat.Builder(this, "stream_channel")
            .setContentTitle("Streaming aktiv")
            .setContentText("Kamera wird verwendet")
            .setSmallIcon(R.drawable.ic_camera)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TestStreamApp::StreamWakeLock"
        )
        wakeLock.acquire()
    }

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}


