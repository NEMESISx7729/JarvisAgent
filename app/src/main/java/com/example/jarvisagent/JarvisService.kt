package com.example.jarvisagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

class JarvisService : AccessibilityService() {

    companion object { var isServiceRunning = false }
    
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
        setupSpeech()
    }

    private fun setupOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_voice_jarvis, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }

        val btnMic = overlayView?.findViewById<Button>(R.id.btnFloatingMic)
        val tvStatus = overlayView?.findViewById<TextView>(R.id.tvMicStatus)

        btnMic?.setOnClickListener {
            tvStatus?.text = "Listening..."
            tvStatus?.visibility = View.VISIBLE
            
            mainHandler.post {
                try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName) // The Mic Fix
                    }
                    speechRecognizer?.startListening(intent)
                } catch (e: Exception) {
                    tvStatus?.visibility = View.GONE
                    showToast("Mic blocked! Open the Jarvis app to grant permission.")
                }
            }
        }
        windowManager.addView(overlayView, params)
    }

    private fun setupSpeech() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                overlayView?.findViewById<TextView>(R.id.tvMicStatus)?.visibility = View.GONE
                showToast("Mic Error: $error")
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val command = matches[0]
                    overlayView?.findViewById<TextView>(R.id.tvMicStatus)?.text = "Thinking..."
                    processVoiceCommand(command)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun processVoiceCommand(command: String) {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            showToast("Cannot read screen.")
            return
        }

        val clickableNodes = mutableListOf<String>()
        extractClickableNodes(rootNode, clickableNodes)
        
        if (clickableNodes.isEmpty()) {
            showToast("No buttons found.")
            return
        }

        askGeminiWhereToTap(command, clickableNodes.joinToString("\n"))
    }

    private fun extractClickableNodes(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        
        if (node.isClickable && !text.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            list.add("'$text' at X:${rect.centerX()}, Y:${rect.centerY()}")
        }
        for (i in 0 until node.childCount) {
            extractClickableNodes(node.getChild(i), list)
        }
    }

    private fun askGeminiWhereToTap(command: String, elements: String) {
        val sharedPrefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val apiKey = sharedPrefs.getString("GEMINI_API_KEY", "") ?: return

        Thread {
            try {
                val prompt = """
                    You are a phone automation agent. User said: "$command".
                    Here are the buttons on the screen:
                    $elements
                    Return ONLY a JSON object with the coordinates of the button to click: {"x": 500, "y": 800}. 
                    If nothing matches, return {"x": 0, "y": 0}.
                """.trimIndent()

                val jsonPayload = """{"contents": [{"parts": [{"text": "${escapeJson(prompt)}"}]}]}"""
                // The Lite server fix
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$apiKey"
                val body = jsonPayload.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()

                val response = httpClient.newCall(request).execute()
                val responseData = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val cleanJson = responseData.substringAfter("{").substringBeforeLast("}")
                    val json = JsonParser.parseString("{$cleanJson}").asJsonObject
                    val x = json.get("x").asFloat
                    val y = json.get("y").asFloat
                    
                    mainHandler.post {
                        overlayView?.findViewById<TextView>(R.id.tvMicStatus)?.visibility = View.GONE
                        if (x > 0 && y > 0) performTap(x, y) else showToast("Button not found")
                    }
                } else {
                    showToast("API Error ${response.code}")
                }
            } catch (e: Exception) {
                showToast("Error parsing API")
            }
        }.start()
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
        dispatchGesture(gesture, null, null)
    }

    private fun escapeJson(str: String) = str.replace("\"", "\\\"").replace("\n", "\\n")
    private fun showToast(msg: String) = mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    
    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        return super.onUnbind(intent)
    }
}
