package com.pluginslab.agentbridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object GestureDispatcher {

    suspend fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchGesture(service, stroke)
    }

    suspend fun longPress(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 600): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(service, stroke)
    }

    suspend fun swipe(
        service: AccessibilityService,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatchGesture(service, stroke)
    }

    suspend fun tapNode(service: AccessibilityService, nodeId: Int): Pair<Boolean, String> {
        val node = NodeRegistry.get(nodeId)
            ?: return Pair(false, "node_not_found")

        // Try ACTION_CLICK first if the node is clickable
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) return Pair(true, "action_click")
        }

        // Fall back to gesture tap on center of bounds
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val result = tap(service, cx, cy)
        return Pair(result, "gesture_tap")
    }

    suspend fun longPressNode(service: AccessibilityService, nodeId: Int, durationMs: Long = 600): Pair<Boolean, String> {
        val node = NodeRegistry.get(nodeId)
            ?: return Pair(false, "node_not_found")

        if (node.isLongClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            if (result) return Pair(true, "action_long_click")
        }

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val result = longPress(service, cx, cy, durationMs)
        return Pair(result, "gesture_long_press")
    }

    private suspend fun dispatchGesture(
        service: AccessibilityService,
        stroke: GestureDescription.StrokeDescription
    ): Boolean = suspendCancellableCoroutine { cont ->
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }

        service.dispatchGesture(gesture, callback, null)
    }
}
