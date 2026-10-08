package com.example.jarvisagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class JarvisService : AccessibilityService() {

    companion object {
        var isServiceRunning = false
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupFloatingHUD()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingHUD() {
        if (overlayView != null) return

        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_jarvis_overlay, null)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }

        val tab = overlayView?.findViewById<View>(R.id.overlayTab)
        val console = overlayView?.findViewById<View>(R.id.overlayConsole)
        val etCommand = overlayView?.findViewById<EditText>(R.id.etAgentCommand)
        val btnRun = overlayView?.findViewById<Button>(R.id.btnRunAgent)
        val btnClose = overlayView?.findViewById<Button>(R.id.btnCloseOverlay)
        val tvStatus = overlayView?.findViewById<TextView>(R.id.tvAgentStatus)

        tab?.setOnClickListener {
            tab.visibility = View.GONE
            console?.visibility = View.VISIBLE
        }

        btnClose?.setOnClickListener {
            setConsoleFocusable(false)
            console?.visibility = View.GONE
            tab?.visibility = View.VISIBLE
        }

        // Tap input to trigger keyboard
        etCommand?.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                setConsoleFocusable(true)
                v.postDelayed({
                    v.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }, 100)
            }
            false
        }

        btnRun?.setOnClickListener {
            val task = etCommand?.text?.toString()?.trim().orEmpty()
            if (task.isEmpty()) return@setOnClickListener

            setConsoleFocusable(false)
            console?.visibility = View.GONE
            tab?.visibility = View.VISIBLE

            tvStatus?.text = "ANALYZING SCREEN..."
            captureScreenAndAnalyze(task)
        }

        windowManager.addView(overlayView, overlayParams)
    }

    private fun setConsoleFocusable(focusable: Boolean) {
        val params = overlayParams ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(overlayView?.windowToken, 0)
        }
        windowManager.updateViewLayout(overlayView, params)
    }

    // --- STEP 1: CAPTURE SCREEN SILENTLY ---
    private fun captureScreenAndAnalyze(task: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                applicationContext.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()

                        if (bitmap != null) {
                            sendToGemini(bitmap, task)
                        } else {
                            showToast("Screenshot buffer conversion failed")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        showToast("Screen capture failed: Error $errorCode")
                    }
                }
            )
        } else {
            showToast("Automated vision requires Android 11 or higher.")
        }
    }

    // --- STEP 2: SEND SCREENSHOT TO GEMINI VISION API ---
    private fun sendToGemini(bitmap: Bitmap, task: String) {
        val sharedPrefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
        val apiKey = sharedPrefs.getString("GEMINI_API_KEY", "")

        if (apiKey.isNullOrEmpty()) {
            showToast("Missing Gemini API Key! Save it in MainActivity.")
            return
        }

        Thread {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                val screenWidth = bitmap.width
                val screenHeight = bitmap.height

                val prompt = """
                    You are an Android UI touch agent.
                    Screen dimensions: width=$screenWidth, height=$screenHeight.
                    User wants to: "$task".
                    Look at this screenshot and determine where the user needs to tap to accomplish this task.
                    Return ONLY a raw JSON object with this exact structure:
                    {"x": 540, "y": 1200, "action": "click"}
                    If no tap is possible or needed, return:
                    {"action": "none"}
                """.trimIndent()

                val jsonPayload = """
                    {
                      "contents": [{
                        "parts": [
                          {"text": ${escapeJson(prompt)}},
                          {
                            "inline_data": {
                              "mime_type": "image/jpeg",
                              "data": "$base64Image"
                            }
                          }
                        ]
                      }]
                    }
                """.trimIndent()

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$apiKey"
                val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    parseGeminiResponseAndTap(responseBody)
                } else {
                    showToast("Gemini Error: ${response.code}")
                }
            } catch (e: Exception) {
                showToast("Request failed: ${e.localizedMessage}")
            }
        }.start()
    }

    // --- STEP 3: PARSE JSON & TAP ---
    private fun parseGeminiResponseAndTap(responseBody: String) {
        try {
            val root = JsonParser.parseString(responseBody).asJsonObject
            val candidates = root.getAsJsonArray("candidates")
            val text = candidates[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")[0].asJsonObject
                .get("text").asString

            val cleanJson = text.substringAfter("{").substringBeforeLast("}")
            val jsonObject = JsonParser.parseString("{$cleanJson}").asJsonObject

            val action = jsonObject.get("action")?.asString ?: "none"
            if (action == "click") {
                val tapX = jsonObject.get("x").asFloat
                val tapY = jsonObject.get("y").asFloat
                mainHandler.post {
                    performTap(tapX, tapY)
                }
            } else {
                showToast("Agent completed or cannot find button.")
            }
        } catch (e: Exception) {
            showToast("Failed to parse AI coordinates.")
        }
    }

    // --- STEP 4: PHYSICAL TAP DISPATCH ---
    private fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                showToast("Tapped at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                showToast("Tap cancelled")
            }
        }, null)
    }

    private fun escapeJson(str: String): String {
        return JsonParser.parseString("\"" + str.replace("\"", "\\\"").replace("\n", "\\n") + "\"").toString()
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        return super.onUnbind(intent)
    }
}
