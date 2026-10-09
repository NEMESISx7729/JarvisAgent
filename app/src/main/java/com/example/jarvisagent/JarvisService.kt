package com.example.jarvisagent

import android.content.Context
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etCode = findViewById<EditText>(R.id.etCode)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnRun = findViewById<Button>(R.id.btnRun)
        val webView = findViewById<WebView>(R.id.webView)

        // Configure the Sandbox to allow JavaScript execution
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient() 
        webView.setBackgroundColor(0x000000) 

        // Load your saved code from the phone's memory
        val sharedPrefs = getSharedPreferences("CodeSandboxPrefs", Context.MODE_PRIVATE)
        val defaultCode = "<html>\n<body style=\"color:white; font-family:sans-serif; text-align:center; margin-top:50px;\">\n  <h1>System Online</h1>\n  <button onclick=\"alert('Sandbox is working!')\">Test JavaScript</button>\n</body>\n</html>"
        val savedCode = sharedPrefs.getString("SAVED_CODE", defaultCode)
        etCode.setText(savedCode)

        // Run the code on startup
        webView.loadDataWithBaseURL(null, savedCode!!, "text/html", "utf-8", null)

        btnSave.setOnClickListener {
            val currentCode = etCode.text.toString()
            sharedPrefs.edit().putString("SAVED_CODE", currentCode).apply()
            Toast.makeText(this, "Code Saved", Toast.LENGTH_SHORT).show()
        }

        btnRun.setOnClickListener {
            val codeToRun = etCode.text.toString()
            webView.loadDataWithBaseURL(null, codeToRun, "text/html", "utf-8", null)
        }
    }
}
