package com.pluginslab.agentbridge.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object NodeRegistry {
    private val nodes = ConcurrentHashMap<Int, AccessibilityNodeInfo>()
    private val counter = AtomicInteger(0)

    fun clear() {
        nodes.clear()
        counter.set(0)
    }

    fun put(node: AccessibilityNodeInfo): Int {
        val id = counter.incrementAndGet()
        nodes[id] = node
        return id
    }

    fun get(id: Int): AccessibilityNodeInfo? = nodes[id]

    val size: Int get() = nodes.size
}
