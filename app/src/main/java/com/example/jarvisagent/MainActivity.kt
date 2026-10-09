package com.example.jarvisagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request Microphone Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val btnSaveKey = findViewById<Button>(R.id.btnSaveKey)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        
        val sharedPrefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val savedKey = sharedPrefs.getString("GEMINI_API_KEY", "")
        if (!savedKey.isNullOrEmpty()) etApiKey.setText(savedKey)

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                sharedPrefs.edit().putString("GEMINI_API_KEY", key).apply()
                Toast.makeText(this, "Key Saved!", Toast.LENGTH_SHORT).show()
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        if (JarvisService.isServiceRunning) {
            tvStatus.text = "🟢 Voice Agent Online"
            tvStatus.setTextColor(0xFF22C55E.toInt())
            btnSettings.visibility = View.GONE
        } else {
            tvStatus.text = "🔴 Agent Offline"
            tvStatus.setTextColor(0xFFEF4444.toInt())
            btnSettings.visibility = View.VISIBLE
        }
    }
}
