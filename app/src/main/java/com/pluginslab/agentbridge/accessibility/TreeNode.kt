package com.pluginslab.agentbridge.accessibility

import kotlinx.serialization.Serializable

@Serializable
data class TreeNode(
    val id: Int,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val bounds: Bounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val children: List<TreeNode>
) {
    @Serializable
    data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    fun toIndentedText(indent: Int = 0): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        val label = buildString {
            append("[$id] ")
            append(className?.substringAfterLast('.') ?: "?")
            text?.let { append(" text=\"$it\"") }
            contentDescription?.let { append(" desc=\"$it\"") }
            resourceId?.let { append(" id=$it") }
            if (clickable) append(" [clickable]")
            if (longClickable) append(" [long-clickable]")
            if (scrollable) append(" [scrollable]")
            if (editable) append(" [editable]")
            if (focused) append(" [focused]")
            if (checked) append(" [checked]")
            if (!enabled) append(" [disabled]")
            append(" (${bounds.x},${bounds.y} ${bounds.width}x${bounds.height})")
        }
        sb.append(prefix).append(label).append('\n')
        for (child in children) {
            sb.append(child.toIndentedText(indent + 1))
        }
        return sb.toString()
    }
}
