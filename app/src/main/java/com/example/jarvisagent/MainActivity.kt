package com.example.jarvisagent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val btnSaveKey = findViewById<Button>(R.id.btnSaveKey)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        
        // 1. Load the key if you already saved it before
        val sharedPrefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val savedKey = sharedPrefs.getString("GEMINI_API_KEY", "")
        if (!savedKey.isNullOrEmpty()) {
            etApiKey.setText(savedKey)
        }

        // 2. Save the key when the blue button is tapped
        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                sharedPrefs.edit().putString("GEMINI_API_KEY", key).apply()
                Toast.makeText(this, "API Key Saved Successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please paste your key first", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Open Android Accessibility Settings when the red button is tapped
        btnSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        // 4. Change the screen to Green if Android gave us the power
        if (JarvisService.isServiceRunning) {
            tvStatus.text = "🟢 System Online & Eyes Active"
            tvStatus.setTextColor(0xFF22C55E.toInt())
            btnSettings.visibility = View.GONE
        } else {
            tvStatus.text = "🔴 System Offline"
            tvStatus.setTextColor(0xFFEF4444.toInt())
            btnSettings.visibility = View.VISIBLE
        }
    }
}
