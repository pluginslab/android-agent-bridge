package com.pluginslab.agentbridge.server.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.pluginslab.agentbridge.accessibility.BridgeAccessibilityService
import com.pluginslab.agentbridge.accessibility.GestureDispatcher
import com.pluginslab.agentbridge.accessibility.NodeRegistry
import com.pluginslab.agentbridge.accessibility.TreeNode
import com.pluginslab.agentbridge.accessibility.TreeSnapshotter
import com.pluginslab.agentbridge.browser.BrowserManager
import com.pluginslab.agentbridge.capture.ScreenCaptureService
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.ImageContent
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object ToolRegistry {

    private val json = Json { prettyPrint = true }

    fun registerAll(server: Server) {
        registerGetActiveWindowInfo(server)
        registerGlobalAction(server)
        registerGetUiTree(server)
        registerTapNode(server)
        registerTapCoords(server)
        registerLongPressNode(server)
        registerSwipe(server)
        registerTypeText(server)
        registerSendKeyEvents(server)
        registerWaitForIdle(server)
        registerGetScreenshot(server)
        registerListApps(server)
        registerLaunchApp(server)
        registerOpenUrl(server)
        registerFindNodes(server)
        registerWaitForNode(server)
        registerWaitForWindow(server)
        registerSetClipboard(server)
        registerGetClipboard(server)
        registerPaste(server)
        registerSendIntent(server)
        registerClearText(server)
        registerGetNotifications(server)
        registerScrollToText(server)
        registerBrowserNavigate(server)
        registerBrowserEval(server)
        registerBrowserConsole(server)
        registerBrowserInfo(server)
        registerBrowserHtml(server)
        registerBrowserScreenshot(server)
    }

    private fun getService(): BridgeAccessibilityService? = BridgeAccessibilityService.instance

    private fun serviceError(): CallToolResult = CallToolResult(
        content = listOf(TextContent("Accessibility service not connected. Enable it in Settings → Accessibility.")),
        isError = true
    )

    private fun errorResult(msg: String): CallToolResult = CallToolResult(
        content = listOf(TextContent(msg)),
        isError = true
    )

    private fun textResult(text: String): CallToolResult = CallToolResult(
        content = listOf(TextContent(text))
    )

    private fun jsonResult(obj: JsonElement): CallToolResult = CallToolResult(
        content = listOf(TextContent(json.encodeToString(obj)))
    )

    // --- get_active_window_info ---

    private fun registerGetActiveWindowInfo(server: Server) {
        server.addTool(
            name = "get_active_window_info",
            description = "Get metadata about the active window without a full tree snapshot. Returns package name, activity class, and window title.",
            inputSchema = Tool.Input(properties = buildJsonObject {}, required = emptyList())
        ) { _: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val root = svc.rootInActiveWindow
            if (root == null) return@addTool errorResult("No active window")

            val result = buildJsonObject {
                put("package_name", root.packageName?.toString() ?: "unknown")
                put("window_title", root.window?.title?.toString())
            }
            root.recycle()
            jsonResult(result)
        }
    }

    // --- global_action ---

    private fun registerGlobalAction(server: Server) {
        val actionMap = mapOf(
            "back" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            "power_dialog" to android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        )

        server.addTool(
            name = "global_action",
            description = "Perform a global action: back, home, recents, notifications, quick_settings, power_dialog.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "One of: back, home, recents, notifications, quick_settings, power_dialog")
                        put("enum", JsonArray(actionMap.keys.map { JsonPrimitive(it) }))
                    })
                },
                required = listOf("action")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val actionName = request.arguments["action"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'action' argument")
            val actionId = actionMap[actionName]
                ?: return@addTool errorResult("Unknown action: $actionName. Valid: ${actionMap.keys}")
            val success = svc.performGlobalAction(actionId)
            jsonResult(buildJsonObject { put("success", success) })
        }
    }

    // --- get_ui_tree ---

    private fun registerGetUiTree(server: Server) {
        server.addTool(
            name = "get_ui_tree",
            description = "Snapshot the on-screen UI as a tree of nodes. By default walks ALL interactive windows (app, status bar, IME, dock, system overlays) under a synthetic 'AllWindows' root. Must be called before tap_node to populate the node registry. IDs are valid until the next get_ui_tree call.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("max_depth", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum tree depth (default 20)")
                    })
                    put("include_invisible", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Include nodes not visible to user (default false)")
                    })
                    put("format", buildJsonObject {
                        put("type", "string")
                        put("description", "Output format: 'indented_text' (default, token-efficient) or 'json'")
                        put("enum", JsonArray(listOf(JsonPrimitive("indented_text"), JsonPrimitive("json"))))
                    })
                    put("single_window", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, snapshot only rootInActiveWindow (legacy v1 behaviour). Default false.")
                    })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()

            val maxDepth = request.arguments["max_depth"]?.jsonPrimitive?.intOrNull ?: 20
            val includeInvisible = request.arguments["include_invisible"]?.jsonPrimitive?.booleanOrNull ?: false
            val format = request.arguments["format"]?.jsonPrimitive?.content ?: "indented_text"
            val singleWindow = request.arguments["single_window"]?.jsonPrimitive?.booleanOrNull ?: false

            val tree = if (singleWindow) {
                val root = svc.rootInActiveWindow
                    ?: return@addTool errorResult("No active window")
                TreeSnapshotter.snapshot(root, maxDepth, includeInvisible)
            } else {
                TreeSnapshotter.snapshotAllWindows(svc, maxDepth, includeInvisible)
            }

            when (format) {
                "json" -> textResult(json.encodeToString(tree))
                else -> textResult(tree.toIndentedText())
            }
        }
    }

    // --- tap_node ---

    private fun registerTapNode(server: Server) {
        server.addTool(
            name = "tap_node",
            description = "Tap a node by its registry ID from the last get_ui_tree call. Prefers ACTION_CLICK when node is clickable, falls back to gesture tap on node center.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("node_id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Node ID from get_ui_tree")
                    })
                },
                required = listOf("node_id")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val nodeId = request.arguments["node_id"]?.jsonPrimitive?.intOrNull
                ?: return@addTool errorResult("Missing 'node_id'")
            val (success, method) = GestureDispatcher.tapNode(svc, nodeId)
            jsonResult(buildJsonObject {
                put("success", success)
                put("method", method)
            })
        }
    }

    // --- tap_coords ---

    private fun registerTapCoords(server: Server) {
        server.addTool(
            name = "tap_coords",
            description = "Tap absolute screen coordinates. Use when no node matches or for canvas-like content.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("x", buildJsonObject { put("type", "integer"); put("description", "X coordinate") })
                    put("y", buildJsonObject { put("type", "integer"); put("description", "Y coordinate") })
                },
                required = listOf("x", "y")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val x = request.arguments["x"]?.jsonPrimitive?.intOrNull ?: return@addTool errorResult("Missing 'x'")
            val y = request.arguments["y"]?.jsonPrimitive?.intOrNull ?: return@addTool errorResult("Missing 'y'")
            val success = GestureDispatcher.tap(svc, x.toFloat(), y.toFloat())
            jsonResult(buildJsonObject { put("success", success) })
        }
    }

    // --- long_press_node ---

    private fun registerLongPressNode(server: Server) {
        server.addTool(
            name = "long_press_node",
            description = "Long-press a node by its registry ID.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("node_id", buildJsonObject { put("type", "integer"); put("description", "Node ID from get_ui_tree") })
                    put("duration_ms", buildJsonObject { put("type", "integer"); put("description", "Duration in ms (default 600)") })
                },
                required = listOf("node_id")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val nodeId = request.arguments["node_id"]?.jsonPrimitive?.intOrNull ?: return@addTool errorResult("Missing 'node_id'")
            val duration = request.arguments["duration_ms"]?.jsonPrimitive?.longOrNull ?: 600L
            val (success, method) = GestureDispatcher.longPressNode(svc, nodeId, duration)
            jsonResult(buildJsonObject {
                put("success", success)
                put("method", method)
            })
        }
    }

    // --- swipe ---

    private fun registerSwipe(server: Server) {
        server.addTool(
            name = "swipe",
            description = "Swipe from (x1,y1) to (x2,y2) over duration_ms milliseconds.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("x1", buildJsonObject { put("type", "integer") })
                    put("y1", buildJsonObject { put("type", "integer") })
                    put("x2", buildJsonObject { put("type", "integer") })
                    put("y2", buildJsonObject { put("type", "integer") })
                    put("duration_ms", buildJsonObject { put("type", "integer"); put("description", "Swipe duration in ms (default 300)") })
                },
                required = listOf("x1", "y1", "x2", "y2")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val x1 = request.arguments["x1"]?.jsonPrimitive?.intOrNull ?: return@addTool errorResult("Missing 'x1'")
            val y1 = request.arguments["y1"]?.jsonPrimitive?.intOrNull ?: return@addTool errorResult("Missing 'y1'")
            val x2 = request.arguments["x2"]?.jsonPrimitive?.intOrNull ?: return@addTool errorResult("Missing 'x2'")
            val y2 = request.arguments["y2"]?.jsonPrimitive?.intOrNull ?: return@addTool errorResult("Missing 'y2'")
            val duration = request.arguments["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L
            val success = GestureDispatcher.swipe(svc, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration)
            jsonResult(buildJsonObject { put("success", success) })
        }
    }

    // --- type_text ---

    private fun registerTypeText(server: Server) {
        server.addTool(
            name = "type_text",
            description = "Set text on the currently focused editable node, or a specified node. Replaces existing content.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("text", buildJsonObject { put("type", "string"); put("description", "Text to type") })
                    put("node_id", buildJsonObject { put("type", "integer"); put("description", "Optional node ID. If omitted, uses focused node.") })
                },
                required = listOf("text")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val text = request.arguments["text"]?.jsonPrimitive?.content ?: return@addTool errorResult("Missing 'text'")
            val nodeId = request.arguments["node_id"]?.jsonPrimitive?.intOrNull

            val node: AccessibilityNodeInfo? = if (nodeId != null) {
                NodeRegistry.get(nodeId)
            } else {
                svc.rootInActiveWindow?.let { findFocusedEditableNode(it) }
            }

            if (node == null) return@addTool errorResult("No editable node found")

            // Focus the node first
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            jsonResult(buildJsonObject { put("success", success) })
        }
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isFocused && root.isEditable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findFocusedEditableNode(child)
            if (result != null) return result
        }
        return null
    }

    // --- send_key_events ---

    private fun registerSendKeyEvents(server: Server) {
        val keyMap = mapOf(
            "ENTER" to KeyEvent.KEYCODE_ENTER,
            "BACKSPACE" to KeyEvent.KEYCODE_DEL,
            "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
            "TAB" to KeyEvent.KEYCODE_TAB,
            "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
            "UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
            "END" to KeyEvent.KEYCODE_MOVE_END,
            "PAGE_UP" to KeyEvent.KEYCODE_PAGE_UP,
            "PAGE_DOWN" to KeyEvent.KEYCODE_PAGE_DOWN,
            "SPACE" to KeyEvent.KEYCODE_SPACE
        )

        server.addTool(
            name = "send_key_events",
            description = "Send key events. Valid keys: ${keyMap.keys.joinToString(", ")}",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("keys", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "List of key names to send, e.g. [\"ENTER\"], [\"BACKSPACE\",\"BACKSPACE\"]")
                    })
                },
                required = listOf("keys")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val keys = request.arguments["keys"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: return@addTool errorResult("Missing 'keys' array")

            val results = mutableListOf<String>()
            for (key in keys) {
                val keyCode = keyMap[key.uppercase()]
                if (keyCode == null) {
                    results.add("$key: unknown")
                    continue
                }
                // Use soft keyboard dispatch via InputConnection isn't available from a11y service
                // Instead use dispatchGesture isn't possible for key events
                // The proper way is via AccessibilityService.performGlobalAction or inject events
                // Actually: we can use AccessibilityNodeInfo.performAction on the focused node
                val root = svc.rootInActiveWindow
                val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    val args = android.os.Bundle()
                    // For ENTER, try ACTION_IME_ENTER or simulate via text
                    if (keyCode == KeyEvent.KEYCODE_ENTER) {
                        // Try pressing enter via IME action
                        val success = focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        results.add("$key: ${if (success) "ok" else "failed"}")
                    } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                        // Backspace: get current text and remove last char
                        val currentText = focused.text?.toString() ?: ""
                        if (currentText.isNotEmpty()) {
                            val newText = currentText.dropLast(1)
                            val setArgs = android.os.Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                            }
                            val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
                            results.add("$key: ${if (success) "ok" else "failed"}")
                        } else {
                            results.add("$key: empty")
                        }
                    } else {
                        results.add("$key: unsupported_via_a11y")
                    }
                } else {
                    results.add("$key: no_focused_node")
                }
            }
            jsonResult(buildJsonObject {
                put("results", JsonArray(results.map { JsonPrimitive(it) }))
            })
        }
    }

    // --- wait_for_idle ---

    private fun registerWaitForIdle(server: Server) {
        server.addTool(
            name = "wait_for_idle",
            description = "Wait until the UI settles (no accessibility events for quiet_period_ms). Useful after taps to let animations complete.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Max wait time in ms (default 3000)") })
                    put("quiet_period_ms", buildJsonObject { put("type", "integer"); put("description", "Required quiet period in ms (default 500)") })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val timeoutMs = request.arguments["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 3000L
            val quietMs = request.arguments["quiet_period_ms"]?.jsonPrimitive?.longOrNull ?: 500L

            val svc = getService() ?: return@addTool serviceError()

            // Simple implementation: track last event time from the service
            val startTime = System.currentTimeMillis()
            var lastEventTime = BridgeAccessibilityService.lastEventTime

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                delay(100)
                val currentLastEvent = BridgeAccessibilityService.lastEventTime
                if (currentLastEvent != lastEventTime) {
                    lastEventTime = currentLastEvent
                }
                if (System.currentTimeMillis() - lastEventTime >= quietMs) {
                    return@addTool jsonResult(buildJsonObject {
                        put("settled", true)
                        put("waited_ms", System.currentTimeMillis() - startTime)
                    })
                }
            }

            jsonResult(buildJsonObject {
                put("settled", false)
                put("waited_ms", timeoutMs)
            })
        }
    }

    // --- get_screenshot ---

    private fun registerGetScreenshot(server: Server) {
        server.addTool(
            name = "get_screenshot",
            description = "Capture the current screen as a PNG. Requires an active MediaProjection grant (open AgentBridge and tap 'Grant Screen Capture' once per session). By default returns an MCP ImageContent block; use format='data_url' or 'base64' for text output.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "string")
                        put("description", "Return format: 'image' (default, ImageContent), 'data_url', or 'base64'")
                        put("enum", JsonArray(listOf(JsonPrimitive("image"), JsonPrimitive("data_url"), JsonPrimitive("base64"))))
                    })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val cap = ScreenCaptureService.instance
            if (cap == null || !ScreenCaptureService.isActive) {
                return@addTool errorResult("Screen capture not granted. Open AgentBridge on the device and tap 'Grant Screen Capture'.")
            }
            // First acquire may be null before the first frame lands; retry briefly.
            var bytes = cap.captureFrame()
            if (bytes == null) {
                delay(150)
                bytes = cap.captureFrame()
            }
            if (bytes == null) return@addTool errorResult("No frame available. Try again in a moment.")
            val format = request.arguments["format"]?.jsonPrimitive?.contentOrNull ?: "image"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            when (format) {
                "base64" -> textResult(b64)
                "data_url" -> textResult("data:image/png;base64,$b64")
                else -> CallToolResult(content = listOf(ImageContent(data = b64, mimeType = "image/png")))
            }
        }
    }

    // --- list_apps ---

    private fun registerListApps(server: Server) {
        server.addTool(
            name = "list_apps",
            description = "List installed apps with their package name and user-visible label. Optionally filter by a substring match against label or package.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("filter", buildJsonObject {
                        put("type", "string")
                        put("description", "Case-insensitive substring. Matches against label and package name.")
                    })
                    put("launchable_only", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Only apps that have a launcher activity (default true).")
                    })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val pm = svc.packageManager
            val filter = request.arguments["filter"]?.jsonPrimitive?.contentOrNull?.lowercase()
            val launchableOnly = request.arguments["launchable_only"]?.jsonPrimitive?.booleanOrNull ?: true

            val apps = if (launchableOnly) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0).map { ri ->
                    val pkg = ri.activityInfo.packageName
                    val label = ri.loadLabel(pm).toString()
                    Triple(pkg, label, ri.activityInfo.name)
                }
            } else {
                pm.getInstalledApplications(0).map { ai ->
                    val label = pm.getApplicationLabel(ai).toString()
                    Triple(ai.packageName, label, null as String?)
                }
            }

            val filtered = if (filter.isNullOrBlank()) apps else apps.filter {
                it.first.lowercase().contains(filter) || it.second.lowercase().contains(filter)
            }

            val sorted = filtered.sortedBy { it.second.lowercase() }

            val arr = JsonArray(sorted.map { (pkg, label, activity) ->
                buildJsonObject {
                    put("package", pkg)
                    put("label", label)
                    if (activity != null) put("activity", activity)
                }
            })
            jsonResult(buildJsonObject {
                put("count", sorted.size)
                put("apps", arr)
            })
        }
    }

    // --- launch_app ---

    private fun registerLaunchApp(server: Server) {
        server.addTool(
            name = "launch_app",
            description = "Launch an app by package name using its default launcher activity. Foregrounds the app.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "Package name, e.g. com.android.chrome")
                    })
                },
                required = listOf("package")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val pkg = request.arguments["package"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'package'")
            val intent = svc.packageManager.getLaunchIntentForPackage(pkg)
                ?: return@addTool errorResult("No launcher activity found for $pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                svc.startActivity(intent)
                jsonResult(buildJsonObject {
                    put("success", true)
                    put("package", pkg)
                })
            } catch (e: Exception) {
                errorResult("Failed to launch $pkg: ${e.message}")
            }
        }
    }

    // --- open_url ---

    private fun registerOpenUrl(server: Server) {
        server.addTool(
            name = "open_url",
            description = "Open a URL. By default uses the system default browser; optionally target a specific package (e.g. com.android.chrome).",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "URL to open, e.g. https://example.com")
                    })
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional package to force (e.g. com.android.chrome). If omitted, uses the default browser.")
                    })
                },
                required = listOf("url")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val url = request.arguments["url"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'url'")
            val forcePkg = request.arguments["package"]?.jsonPrimitive?.contentOrNull

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!forcePkg.isNullOrBlank()) intent.setPackage(forcePkg)

            try {
                svc.startActivity(intent)
                jsonResult(buildJsonObject {
                    put("success", true)
                    put("url", url)
                    forcePkg?.let { put("package", it) }
                })
            } catch (e: Exception) {
                errorResult("Failed to open $url: ${e.message}")
            }
        }
    }

    // --- predicate matching shared by find_nodes / wait_for_node ---

    private data class NodePredicate(
        val textContains: String?,
        val textEquals: String?,
        val resourceId: String?,
        val className: String?,
        val contentDescriptionContains: String?,
        val clickable: Boolean?,
        val editable: Boolean?,
        val focused: Boolean?
    ) {
        fun matches(n: TreeNode): Boolean {
            if (textContains != null && n.text?.contains(textContains, ignoreCase = true) != true) return false
            if (textEquals != null && n.text != textEquals) return false
            if (resourceId != null && n.resourceId != resourceId) return false
            if (className != null && n.className?.contains(className, ignoreCase = true) != true) return false
            if (contentDescriptionContains != null && n.contentDescription?.contains(contentDescriptionContains, ignoreCase = true) != true) return false
            if (clickable != null && n.clickable != clickable) return false
            if (editable != null && n.editable != editable) return false
            if (focused != null && n.focused != focused) return false
            return true
        }
    }

    private fun parsePredicate(args: JsonObject): NodePredicate = NodePredicate(
        textContains = args["text_contains"]?.jsonPrimitive?.contentOrNull,
        textEquals = args["text_equals"]?.jsonPrimitive?.contentOrNull,
        resourceId = args["resource_id"]?.jsonPrimitive?.contentOrNull,
        className = args["class_name"]?.jsonPrimitive?.contentOrNull,
        contentDescriptionContains = args["content_description_contains"]?.jsonPrimitive?.contentOrNull,
        clickable = args["clickable"]?.jsonPrimitive?.booleanOrNull,
        editable = args["editable"]?.jsonPrimitive?.booleanOrNull,
        focused = args["focused"]?.jsonPrimitive?.booleanOrNull
    )

    private fun predicateSchema(): JsonObjectBuilder.() -> Unit = {
        put("text_contains", buildJsonObject { put("type", "string"); put("description", "Case-insensitive substring of node text") })
        put("text_equals", buildJsonObject { put("type", "string"); put("description", "Exact match for node text") })
        put("resource_id", buildJsonObject { put("type", "string"); put("description", "Exact match for viewIdResourceName, e.g. com.android.chrome:id/url_bar") })
        put("class_name", buildJsonObject { put("type", "string"); put("description", "Case-insensitive substring of class name, e.g. Button") })
        put("content_description_contains", buildJsonObject { put("type", "string"); put("description", "Case-insensitive substring of contentDescription") })
        put("clickable", buildJsonObject { put("type", "boolean") })
        put("editable", buildJsonObject { put("type", "boolean") })
        put("focused", buildJsonObject { put("type", "boolean") })
    }

    private fun flatten(root: TreeNode, out: MutableList<TreeNode>) {
        if (root.id != 0) out.add(root) // skip synthetic wrapper nodes
        for (c in root.children) flatten(c, out)
    }

    private fun nodeSummary(n: TreeNode): JsonObject = buildJsonObject {
        put("id", n.id)
        n.className?.let { put("class_name", it) }
        n.text?.let { put("text", it) }
        n.contentDescription?.let { put("content_description", it) }
        n.resourceId?.let { put("resource_id", it) }
        put("bounds", buildJsonObject {
            put("x", n.bounds.x); put("y", n.bounds.y)
            put("width", n.bounds.width); put("height", n.bounds.height)
        })
        put("clickable", n.clickable)
        put("editable", n.editable)
        put("focused", n.focused)
    }

    // --- find_nodes ---

    private fun registerFindNodes(server: Server) {
        server.addTool(
            name = "find_nodes",
            description = "Find nodes matching a predicate across all windows. Returns flat list (no tree), so it's much more token-efficient than get_ui_tree when you just need to locate specific elements. Populates node registry so returned IDs work with tap_node.",
            inputSchema = Tool.Input(
                properties = buildJsonObject(predicateSchema()).also {
                    // rebuild with max_results too
                }.let { base ->
                    buildJsonObject {
                        base.forEach { (k, v) -> put(k, v) }
                        put("max_results", buildJsonObject { put("type", "integer"); put("description", "Cap on returned matches (default 25)") })
                    }
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val predicate = parsePredicate(request.arguments)
            val maxResults = request.arguments["max_results"]?.jsonPrimitive?.intOrNull ?: 25

            val root = TreeSnapshotter.snapshotAllWindows(svc, maxDepth = 30, includeInvisible = false)
            val all = mutableListOf<TreeNode>()
            flatten(root, all)
            val matches = all.filter { predicate.matches(it) }.take(maxResults)

            jsonResult(buildJsonObject {
                put("count", matches.size)
                put("total_scanned", all.size)
                put("matches", JsonArray(matches.map { nodeSummary(it) }))
            })
        }
    }

    // --- wait_for_node ---

    private fun registerWaitForNode(server: Server) {
        server.addTool(
            name = "wait_for_node",
            description = "Poll the UI tree until a node matching the predicate appears, or timeout. Returns the first match. Essential for flake-free e2e tests.",
            inputSchema = Tool.Input(
                properties = buildJsonObject(predicateSchema()).let { base ->
                    buildJsonObject {
                        base.forEach { (k, v) -> put(k, v) }
                        put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Max wait in ms (default 5000)") })
                        put("poll_interval_ms", buildJsonObject { put("type", "integer"); put("description", "Poll interval in ms (default 250)") })
                    }
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val predicate = parsePredicate(request.arguments)
            val timeoutMs = request.arguments["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
            val pollMs = request.arguments["poll_interval_ms"]?.jsonPrimitive?.longOrNull ?: 250L

            val start = System.currentTimeMillis()
            while (true) {
                val root = TreeSnapshotter.snapshotAllWindows(svc, maxDepth = 30, includeInvisible = false)
                val all = mutableListOf<TreeNode>()
                flatten(root, all)
                val match = all.firstOrNull { predicate.matches(it) }
                if (match != null) {
                    return@addTool jsonResult(buildJsonObject {
                        put("found", true)
                        put("waited_ms", System.currentTimeMillis() - start)
                        put("match", nodeSummary(match))
                    })
                }
                if (System.currentTimeMillis() - start >= timeoutMs) {
                    return@addTool jsonResult(buildJsonObject {
                        put("found", false)
                        put("waited_ms", timeoutMs)
                    })
                }
                delay(pollMs)
            }
            @Suppress("UNREACHABLE_CODE")
            errorResult("unreachable")
        }
    }

    // --- wait_for_window ---

    private fun registerWaitForWindow(server: Server) {
        server.addTool(
            name = "wait_for_window",
            description = "Poll until the specified package is the active window, or timeout. Returns package/title when reached.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("package", buildJsonObject { put("type", "string"); put("description", "Package name to wait for, e.g. com.android.chrome") })
                    put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Max wait in ms (default 5000)") })
                    put("poll_interval_ms", buildJsonObject { put("type", "integer"); put("description", "Poll interval in ms (default 200)") })
                },
                required = listOf("package")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val targetPkg = request.arguments["package"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'package'")
            val timeoutMs = request.arguments["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
            val pollMs = request.arguments["poll_interval_ms"]?.jsonPrimitive?.longOrNull ?: 200L

            val start = System.currentTimeMillis()
            while (true) {
                val root = svc.rootInActiveWindow
                val currentPkg = root?.packageName?.toString()
                val title = root?.window?.title?.toString()
                if (currentPkg == targetPkg) {
                    return@addTool jsonResult(buildJsonObject {
                        put("found", true)
                        put("waited_ms", System.currentTimeMillis() - start)
                        put("package", currentPkg)
                        title?.let { put("window_title", it) }
                    })
                }
                if (System.currentTimeMillis() - start >= timeoutMs) {
                    return@addTool jsonResult(buildJsonObject {
                        put("found", false)
                        put("waited_ms", timeoutMs)
                        put("current_package", currentPkg ?: "null")
                    })
                }
                delay(pollMs)
            }
            @Suppress("UNREACHABLE_CODE")
            errorResult("unreachable")
        }
    }

    // --- set_clipboard ---

    private fun registerSetClipboard(server: Server) {
        server.addTool(
            name = "set_clipboard",
            description = "Set the Android primary clipboard text. Useful for pasting long strings into apps that don't expose ACTION_SET_TEXT (terminals, canvases).",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("text", buildJsonObject { put("type", "string"); put("description", "Text to place on the clipboard") })
                    put("label", buildJsonObject { put("type", "string"); put("description", "Optional clip label (default 'agentbridge')") })
                },
                required = listOf("text")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val text = request.arguments["text"]?.jsonPrimitive?.content ?: return@addTool errorResult("Missing 'text'")
            val label = request.arguments["label"]?.jsonPrimitive?.contentOrNull ?: "agentbridge"

            return@addTool try {
                val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, text))
                jsonResult(buildJsonObject {
                    put("success", true)
                    put("length", text.length)
                })
            } catch (e: Exception) {
                errorResult("Failed to set clipboard: ${e.message}")
            }
        }
    }

    // --- get_clipboard ---

    private fun registerGetClipboard(server: Server) {
        server.addTool(
            name = "get_clipboard",
            description = "Read the current primary clipboard text. Android restricts this to foreground/default-IME apps; accessibility services may or may not be permitted depending on OEM policy.",
            inputSchema = Tool.Input(properties = buildJsonObject {}, required = emptyList())
        ) { _: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            return@addTool try {
                val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(svc).toString() else null
                jsonResult(buildJsonObject {
                    put("has_clip", clip != null)
                    text?.let { put("text", it) }
                })
            } catch (e: Exception) {
                errorResult("Failed to read clipboard: ${e.message}")
            }
        }
    }

    // --- paste ---

    private fun registerPaste(server: Server) {
        server.addTool(
            name = "paste",
            description = "Perform ACTION_PASTE on a node (or the focused one). Contents of the primary clipboard get pasted. Works on editable fields; terminals require a long-press→Paste menu, which this tool does not orchestrate.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("node_id", buildJsonObject { put("type", "integer"); put("description", "Optional node ID. If omitted, uses the input-focused node.") })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val nodeId = request.arguments["node_id"]?.jsonPrimitive?.intOrNull

            val node: AccessibilityNodeInfo? = if (nodeId != null) {
                NodeRegistry.get(nodeId)
            } else {
                svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }

            if (node == null) return@addTool errorResult("No target node")

            val success = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            jsonResult(buildJsonObject { put("success", success) })
        }
    }

    // --- send_intent ---

    private fun registerSendIntent(server: Server) {
        val flagMap = mapOf(
            "NEW_TASK" to Intent.FLAG_ACTIVITY_NEW_TASK,
            "CLEAR_TOP" to Intent.FLAG_ACTIVITY_CLEAR_TOP,
            "SINGLE_TOP" to Intent.FLAG_ACTIVITY_SINGLE_TOP,
            "CLEAR_TASK" to Intent.FLAG_ACTIVITY_CLEAR_TASK,
            "NO_HISTORY" to Intent.FLAG_ACTIVITY_NO_HISTORY,
            "GRANT_READ_URI_PERMISSION" to Intent.FLAG_GRANT_READ_URI_PERMISSION,
            "GRANT_WRITE_URI_PERMISSION" to Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        server.addTool(
            name = "send_intent",
            description = "Generic Intent.ACTION_* dispatch. Lets you trigger deep links, share sheets, VIEWs, and other intents beyond what open_url/launch_app cover. Runs as startActivity (FLAG_ACTIVITY_NEW_TASK added automatically). Only string/int/long/boolean extras are supported.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("action", buildJsonObject { put("type", "string"); put("description", "Intent action, e.g. android.intent.action.VIEW") })
                    put("data", buildJsonObject { put("type", "string"); put("description", "URI to set as intent data, e.g. https://... or content://...") })
                    put("package", buildJsonObject { put("type", "string"); put("description", "Restrict intent to this package") })
                    put("component", buildJsonObject { put("type", "string"); put("description", "Explicit component, format pkg/activity") })
                    put("type", buildJsonObject { put("type", "string"); put("description", "MIME type, e.g. text/plain") })
                    put("categories", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Intent categories, e.g. android.intent.category.LAUNCHER")
                    })
                    put("flags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Flag names. Valid: ${flagMap.keys.joinToString(", ")}")
                    })
                    put("extras", buildJsonObject {
                        put("type", "object")
                        put("description", "Map of extras. Values can be string/int/long/boolean. Arrays become string arrays.")
                    })
                },
                required = listOf("action")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val action = request.arguments["action"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'action'")

            val intent = Intent(action)
            request.arguments["data"]?.jsonPrimitive?.contentOrNull?.let { intent.data = Uri.parse(it) }
            request.arguments["package"]?.jsonPrimitive?.contentOrNull?.let { intent.setPackage(it) }
            request.arguments["component"]?.jsonPrimitive?.contentOrNull?.let {
                val cn = ComponentName.unflattenFromString(it)
                if (cn == null) return@addTool errorResult("Bad component '$it' — expected pkg/activity")
                intent.component = cn
            }
            request.arguments["type"]?.jsonPrimitive?.contentOrNull?.let { intent.type = it }
            request.arguments["categories"]?.jsonArray?.forEach { intent.addCategory(it.jsonPrimitive.content) }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            request.arguments["flags"]?.jsonArray?.forEach {
                val name = it.jsonPrimitive.content.uppercase()
                flagMap[name]?.let { f -> intent.addFlags(f) }
            }

            val extras = Bundle()
            (request.arguments["extras"] as? JsonObject)?.forEach { (k, v) ->
                when (v) {
                    is JsonPrimitive -> {
                        when {
                            v.isString -> extras.putString(k, v.content)
                            v.booleanOrNull != null -> extras.putBoolean(k, v.boolean)
                            v.longOrNull != null -> {
                                val lv = v.long
                                if (lv in Int.MIN_VALUE..Int.MAX_VALUE) extras.putInt(k, lv.toInt())
                                else extras.putLong(k, lv)
                            }
                            else -> extras.putString(k, v.content)
                        }
                    }
                    is JsonArray -> {
                        val strs = v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toTypedArray()
                        extras.putStringArray(k, strs)
                    }
                    else -> extras.putString(k, v.toString())
                }
            }
            if (!extras.isEmpty) intent.putExtras(extras)

            return@addTool try {
                svc.startActivity(intent)
                jsonResult(buildJsonObject {
                    put("success", true)
                    put("action", action)
                })
            } catch (e: Exception) {
                errorResult("Failed to dispatch intent: ${e.message}")
            }
        }
    }

    // --- clear_text ---

    private fun registerClearText(server: Server) {
        server.addTool(
            name = "clear_text",
            description = "Clear the text of an editable node (or the currently input-focused one) via ACTION_SET_TEXT with an empty string.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("node_id", buildJsonObject { put("type", "integer"); put("description", "Optional node ID. If omitted, uses the focused node.") })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val nodeId = request.arguments["node_id"]?.jsonPrimitive?.intOrNull

            val node: AccessibilityNodeInfo? = if (nodeId != null) {
                NodeRegistry.get(nodeId)
            } else {
                svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }
            if (node == null) return@addTool errorResult("No target node")

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            jsonResult(buildJsonObject { put("success", success) })
        }
    }

    // --- get_notifications ---

    private fun registerGetNotifications(server: Server) {
        server.addTool(
            name = "get_notifications",
            description = "Return a snapshot of recent notifications as captured by the accessibility service. A rolling buffer of the last 50 TYPE_NOTIFICATION_STATE_CHANGED events, newest first. Useful for e2e assertions like 'did a push notification arrive?'. Notifications that existed before AgentBridge started are not captured.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("package", buildJsonObject { put("type", "string"); put("description", "Filter by exact package name") })
                    put("since_ms", buildJsonObject { put("type", "integer"); put("description", "Only notifications with timestamp >= since_ms (unix millis)") })
                    put("limit", buildJsonObject { put("type", "integer"); put("description", "Max entries returned (default 20)") })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            getService() ?: return@addTool serviceError()
            val pkgFilter = request.arguments["package"]?.jsonPrimitive?.contentOrNull
            val since = request.arguments["since_ms"]?.jsonPrimitive?.longOrNull
            val limit = request.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 20

            var items = BridgeAccessibilityService.recentNotifications(since)
            if (pkgFilter != null) items = items.filter { it.packageName == pkgFilter }
            items = items.take(limit)

            jsonResult(buildJsonObject {
                put("count", items.size)
                put("notifications", JsonArray(items.map { n ->
                    buildJsonObject {
                        put("timestamp", n.timestamp)
                        put("package", n.packageName)
                        put("texts", JsonArray(n.texts.map { JsonPrimitive(it) }))
                    }
                }))
            })
        }
    }

    // --- scroll_to_text ---

    private fun registerScrollToText(server: Server) {
        server.addTool(
            name = "scroll_to_text",
            description = "Swipe the screen up to max_swipes times until a node containing text_contains is visible. Returns the match when found, or found=false on timeout. Uses a center-column swipe across 50% of screen height.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("text_contains", buildJsonObject { put("type", "string"); put("description", "Case-insensitive substring to search for in node text") })
                    put("direction", buildJsonObject {
                        put("type", "string")
                        put("description", "'down' scrolls content upward (default), 'up' scrolls downward")
                        put("enum", JsonArray(listOf(JsonPrimitive("down"), JsonPrimitive("up"))))
                    })
                    put("max_swipes", buildJsonObject { put("type", "integer"); put("description", "Max swipes before giving up (default 10)") })
                    put("settle_ms", buildJsonObject { put("type", "integer"); put("description", "Delay after each swipe in ms (default 400)") })
                },
                required = listOf("text_contains")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val target = request.arguments["text_contains"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'text_contains'")
            val direction = request.arguments["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
            val maxSwipes = request.arguments["max_swipes"]?.jsonPrimitive?.intOrNull ?: 10
            val settleMs = request.arguments["settle_ms"]?.jsonPrimitive?.longOrNull ?: 400L

            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val cx = w / 2
            val (y1, y2) = when (direction.lowercase()) {
                "up" -> (h * 0.3).toInt() to (h * 0.8).toInt()
                "down" -> (h * 0.8).toInt() to (h * 0.3).toInt()
                else -> return@addTool errorResult("direction must be 'up' or 'down'")
            }

            for (iter in 0 until maxSwipes) {
                val root = TreeSnapshotter.snapshotAllWindows(svc, maxDepth = 30, includeInvisible = false)
                val all = mutableListOf<TreeNode>()
                flatten(root, all)
                val hit = all.firstOrNull {
                    it.text?.contains(target, ignoreCase = true) == true ||
                    it.contentDescription?.contains(target, ignoreCase = true) == true
                }
                if (hit != null) {
                    return@addTool jsonResult(buildJsonObject {
                        put("found", true)
                        put("swipes", iter)
                        put("match", nodeSummary(hit))
                    })
                }
                GestureDispatcher.swipe(svc, cx.toFloat(), y1.toFloat(), cx.toFloat(), y2.toFloat(), 300L)
                delay(settleMs)
            }
            jsonResult(buildJsonObject {
                put("found", false)
                put("swipes", maxSwipes)
            })
        }
    }

    // --- browser_navigate ---

    private fun registerBrowserNavigate(server: Server) {
        server.addTool(
            name = "browser_navigate",
            description = "Load a URL in AgentBridge's embedded headless WebView. This browser is separate from Chrome — no shared cookies/sessions. Waits for onPageFinished or times out. The WebView persists across navigate calls.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("url", buildJsonObject { put("type", "string"); put("description", "URL to load, e.g. https://example.com") })
                    put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Max wait for page load (default 15000)") })
                },
                required = listOf("url")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val url = request.arguments["url"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'url'")
            val timeout = request.arguments["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 15000L
            BrowserManager.clearConsole()
            val (ok, err) = BrowserManager.navigate(svc.applicationContext, url, timeout)
            jsonResult(buildJsonObject {
                put("success", ok)
                if (!ok) put("error", err ?: "unknown")
                put("url", url)
            })
        }
    }

    // --- browser_eval ---

    private fun registerBrowserEval(server: Server) {
        server.addTool(
            name = "browser_eval",
            description = "Evaluate JavaScript in the embedded WebView and return the JSON-encoded result. Call browser_navigate first to load a page. Single expressions work directly; wrap multi-statement logic in an IIFE and return the result.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("script", buildJsonObject { put("type", "string"); put("description", "JavaScript to evaluate. Return value is JSON-encoded.") })
                    put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Max wait (default 5000)") })
                },
                required = listOf("script")
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val script = request.arguments["script"]?.jsonPrimitive?.content
                ?: return@addTool errorResult("Missing 'script'")
            val timeout = request.arguments["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L
            val result = BrowserManager.eval(svc.applicationContext, script, timeout)
            if (result == null) return@addTool errorResult("eval returned null (timeout or error)")
            textResult(result)
        }
    }

    // --- browser_console ---

    private fun registerBrowserConsole(server: Server) {
        server.addTool(
            name = "browser_console",
            description = "Return buffered console messages from the embedded WebView. Captures console.log/warn/error/info from onConsoleMessage. Ring buffer holds the last 500 entries; browser_navigate clears it.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("since_ms", buildJsonObject { put("type", "integer"); put("description", "Only entries with timestamp >= since_ms") })
                    put("limit", buildJsonObject { put("type", "integer"); put("description", "Max entries (default 50)") })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            getService() ?: return@addTool serviceError()
            val since = request.arguments["since_ms"]?.jsonPrimitive?.longOrNull
            val limit = request.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 50
            val items = BrowserManager.consoleSince(since, limit)
            jsonResult(buildJsonObject {
                put("count", items.size)
                put("messages", JsonArray(items.map { e ->
                    buildJsonObject {
                        put("timestamp", e.timestamp)
                        put("level", e.level)
                        put("message", e.message)
                        e.source?.let { put("source", it) }
                        put("line", e.line)
                    }
                }))
            })
        }
    }

    // --- browser_info (url + title) ---

    private fun registerBrowserInfo(server: Server) {
        server.addTool(
            name = "browser_info",
            description = "Return the current URL and document.title from the embedded WebView.",
            inputSchema = Tool.Input(properties = buildJsonObject {}, required = emptyList())
        ) { _: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val url = BrowserManager.eval(svc.applicationContext, "window.location.href", 3000L) ?: "null"
            val title = BrowserManager.eval(svc.applicationContext, "document.title", 3000L) ?: "null"
            jsonResult(buildJsonObject {
                put("url", url)
                put("title", title)
            })
        }
    }

    // --- browser_html ---

    private fun registerBrowserHtml(server: Server) {
        server.addTool(
            name = "browser_html",
            description = "Return document.documentElement.outerHTML from the embedded WebView as a JSON-encoded string. Optionally pass a 'selector' to return outerHTML of the first match only.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("selector", buildJsonObject { put("type", "string"); put("description", "Optional CSS selector; if set, returns outerHTML of document.querySelector(selector)") })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val selector = request.arguments["selector"]?.jsonPrimitive?.contentOrNull
            val js = if (selector.isNullOrBlank()) {
                "document.documentElement.outerHTML"
            } else {
                val escaped = selector.replace("\\", "\\\\").replace("'", "\\'")
                "(function(){var e=document.querySelector('$escaped');return e?e.outerHTML:null;})()"
            }
            val result = BrowserManager.eval(svc.applicationContext, js, 5000L)
            if (result == null) return@addTool errorResult("eval failed")
            textResult(result)
        }
    }

    // --- browser_screenshot ---

    private fun registerBrowserScreenshot(server: Server) {
        server.addTool(
            name = "browser_screenshot",
            description = "Capture the embedded WebView's current rendering as a PNG. Unlike get_screenshot this doesn't require MediaProjection consent — it draws the WebView directly. Captures full content height (below the fold).",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "string")
                        put("description", "Return format: 'image' (default, ImageContent), 'data_url', or 'base64'")
                        put("enum", JsonArray(listOf(JsonPrimitive("image"), JsonPrimitive("data_url"), JsonPrimitive("base64"))))
                    })
                },
                required = emptyList()
            )
        ) { request: CallToolRequest ->
            val svc = getService() ?: return@addTool serviceError()
            val bytes = BrowserManager.screenshot(svc.applicationContext)
                ?: return@addTool errorResult("No WebView content to capture. Call browser_navigate first.")
            val format = request.arguments["format"]?.jsonPrimitive?.contentOrNull ?: "image"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            when (format) {
                "base64" -> textResult(b64)
                "data_url" -> textResult("data:image/png;base64,$b64")
                else -> CallToolResult(content = listOf(ImageContent(data = b64, mimeType = "image/png")))
            }
        }
    }
}
