package com.pluginslab.agentbridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class BridgeAccessibilityService : AccessibilityService() {

    data class NotifEntry(
        val timestamp: Long,
        val packageName: String,
        val texts: List<String>
    )

    companion object {
        private const val TAG = "BridgeA11y"
        private const val MAX_NOTIFS = 50

        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        @Volatile
        var lastEventTime: Long = System.currentTimeMillis()
            private set

        private val notifBuffer = ArrayDeque<NotifEntry>()

        fun recentNotifications(sinceMs: Long? = null): List<NotifEntry> = synchronized(notifBuffer) {
            if (sinceMs == null) notifBuffer.toList()
            else notifBuffer.filter { it.timestamp >= sinceMs }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventTime = System.currentTimeMillis()
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: "unknown"
            val texts = event.text?.mapNotNull { it?.toString() } ?: emptyList()
            val entry = NotifEntry(System.currentTimeMillis(), pkg, texts)
            synchronized(notifBuffer) {
                notifBuffer.addFirst(entry)
                while (notifBuffer.size > MAX_NOTIFS) notifBuffer.removeLast()
            }
        }
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
