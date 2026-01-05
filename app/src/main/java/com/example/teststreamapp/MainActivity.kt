package com.example.teststreamapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 🔐 Firebase anonymous login
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                Log.d("AUTH", "✅ Firebase anonymous login ok")
            }
            .addOnFailureListener {
                Log.e("AUTH", "❌ Firebase auth failed", it)
            }

        // ▶️ START SENDER
        findViewById<Button>(R.id.btnSender).setOnClickListener {
            val intent = Intent(this, StreamingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)

            // 🔥 WICHTIG: MainActivity beenden, sonst onPause-Hölle
            finish()
        }

        // ▶️ START VIEWER
        findViewById<Button>(R.id.btnViewer).setOnClickListener {
            val intent = Intent(this, ViewerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)

            // 🔥 Ebenfalls beenden
            finish()
        }
    }
}
