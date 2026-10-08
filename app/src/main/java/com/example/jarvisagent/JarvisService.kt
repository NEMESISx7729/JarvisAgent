package com.example.jarvisagent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class JarvisService : AccessibilityService() {

    // This companion object allows the Setup Screen to check if the background service is active
    companion object {
        var isServiceRunning = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The vision and tapping logic will go here
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        return super.onUnbind(intent)
    }
}
