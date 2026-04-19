package com.pluginslab.agentbridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

object TreeSnapshotter {

    fun snapshot(
        root: AccessibilityNodeInfo,
        maxDepth: Int = 20,
        includeInvisible: Boolean = false
    ): TreeNode {
        NodeRegistry.clear()
        return buildNode(root, maxDepth, includeInvisible, 0)
    }

    fun snapshotAllWindows(
        service: AccessibilityService,
        maxDepth: Int = 20,
        includeInvisible: Boolean = false
    ): TreeNode {
        NodeRegistry.clear()

        val windows: List<AccessibilityWindowInfo> = try {
            service.windows ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val windowNodes = mutableListOf<TreeNode>()

        if (windows.isEmpty()) {
            service.rootInActiveWindow?.let { root ->
                windowNodes.add(buildNode(root, maxDepth, includeInvisible, 0))
            }
        } else {
            for (window in windows) {
                val root = window.root ?: continue
                val rect = Rect()
                window.getBoundsInScreen(rect)
                val label = windowTypeLabel(window.type)
                val realTree = buildNode(root, maxDepth, includeInvisible, 0)
                windowNodes.add(
                    TreeNode(
                        id = 0,
                        className = "Window[$label]",
                        text = window.title?.toString(),
                        contentDescription = "windowId=${window.id} active=${window.isActive} focused=${window.isFocused}",
                        resourceId = null,
                        bounds = TreeNode.Bounds(rect.left, rect.top, rect.width(), rect.height()),
                        clickable = false,
                        longClickable = false,
                        scrollable = false,
                        editable = false,
                        focused = window.isFocused,
                        checked = false,
                        enabled = true,
                        children = listOf(realTree)
                    )
                )
            }
        }

        return TreeNode(
            id = 0,
            className = "AllWindows",
            text = null,
            contentDescription = "${windowNodes.size} window(s)",
            resourceId = null,
            bounds = TreeNode.Bounds(0, 0, 0, 0),
            clickable = false,
            longClickable = false,
            scrollable = false,
            editable = false,
            focused = false,
            checked = false,
            enabled = true,
            children = windowNodes
        )
    }

    private fun windowTypeLabel(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
        else -> "type_$type"
    }

    private fun buildNode(
        node: AccessibilityNodeInfo,
        maxDepth: Int,
        includeInvisible: Boolean,
        currentDepth: Int
    ): TreeNode {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val id = NodeRegistry.put(node)

        val children = mutableListOf<TreeNode>()
        if (currentDepth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (!includeInvisible && !child.isVisibleToUser) {
                    continue
                }
                children.add(buildNode(child, maxDepth, includeInvisible, currentDepth + 1))
            }
        }

        return TreeNode(
            id = id,
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            bounds = TreeNode.Bounds(rect.left, rect.top, rect.width(), rect.height()),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            focused = node.isFocused,
            checked = node.isChecked,
            enabled = node.isEnabled,
            children = children
        )
    }
}
