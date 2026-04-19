package com.pluginslab.agentbridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BridgeA11y"

        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        @Volatile
        var lastEventTime: Long = System.currentTimeMillis()
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "AccessibilityService destroyed")
    }
}
