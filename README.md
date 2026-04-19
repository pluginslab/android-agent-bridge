# AndroidAgentBridge

Turn an Android device into a remote-controllable automation surface for LLM agents, over LAN.

The app runs a tiny Ktor + MCP server on the device. An accessibility service reads the UI tree and dispatches gestures. Any MCP-capable client (Claude Code, Claude Desktop, a custom agent) on the same network can read the screen and perform taps, swipes, text input, and intent launches through a bearer-authenticated HTTP/SSE endpoint.

Built for end-to-end testing, lightweight device automation, and "claude, do something on my tablet" demos. v1 is debug-signed and side-loaded; no Play Store distribution.

## Status

v0.2.0 — screen capture + e2e quality-of-life tools. Tested on a Xiaomi Pad 8 Pro (HyperOS, Android 15) and a MacBook client. Other devices/Android versions are untested but nothing is hardware-specific.

## Tool surface

### UI introspection
- `get_active_window_info` — foreground package + window title, cheap.
- `get_ui_tree` — snapshot all interactive windows (app, status bar, IME, system overlays) under a synthetic `AllWindows` root. `single_window=true` reverts to legacy `rootInActiveWindow` behavior. Output in `indented_text` (token-efficient) or `json`.
- `find_nodes` — predicate query across the tree. Matches on `text_contains`, `text_equals`, `resource_id`, `class_name` (substring), `content_description_contains`, plus boolean filters for `clickable` / `editable` / `focused`. Flat result list, far cheaper than a full tree.
- `wait_for_node` — same predicate as `find_nodes`, polls until match or timeout. Replaces sleep-based flakiness in tests.
- `wait_for_window` — polls until a given package is foreground.
- `get_notifications` — rolling buffer of the last 50 notification events captured by the accessibility service (package, texts, timestamp). Filter by `package` or `since_ms`.
- `get_screenshot` — PNG screenshot via MediaProjection. Requires a one-time-per-session consent via the app's **Grant Screen Capture** button. Returns an MCP `ImageContent` block by default; `format="data_url"` or `"base64"` for text output.

### Gestures & input
- `tap_node` — tap a node by its registry ID. Prefers `ACTION_CLICK`, falls back to a center-coord gesture.
- `tap_coords` — absolute-coordinate tap.
- `long_press_node` — long-press a node.
- `swipe` — straight-line swipe with configurable duration.
- `scroll_to_text` — swipes up to `max_swipes` times until a node containing the target text is visible, then returns it. Direction: `down` (default) or `up`.
- `type_text` — `ACTION_SET_TEXT` on a focused editable node. Does not work for terminals and other non-editable views (see `set_clipboard` + `paste`).
- `clear_text` — empty out an editable node's text.
- `send_key_events` — `ENTER`, `BACKSPACE`, `DELETE`, `TAB`, `ESCAPE`, arrows, `HOME`/`END`, `PAGE_UP`/`PAGE_DOWN`, `SPACE`. Best-effort — Android a11y cannot inject arbitrary keystrokes without IME-level privileges.
- `global_action` — `back`, `home`, `recents`, `notifications`, `quick_settings`, `power_dialog`.
- `wait_for_idle` — wait until no a11y events have fired for a quiet period. Useful after triggering an animation.

### Clipboard
- `set_clipboard` — set the Android primary clipboard text.
- `get_clipboard` — read the primary clipboard. Subject to per-OEM restrictions; observed to work on HyperOS.
- `paste` — `ACTION_PASTE` on a node (or the focused one). For terminals you'd need to orchestrate long-press → Paste manually.

### App & intent launching
- `list_apps` — installed apps with package + label, optional substring filter. Defaults to launcher-visible only.
- `launch_app` — foreground an app by package name.
- `open_url` — `ACTION_VIEW` with a URL, optionally targeted at a specific package.
- `send_intent` — generic `Intent.ACTION_*` dispatch with `data`, `package`, `component`, `type`, `categories`, `flags`, and `extras`. Always runs as `startActivity(FLAG_ACTIVITY_NEW_TASK)` plus any flags you add.

## Setup

### On the Android device

1. Build a debug APK (or grab one from a release):
   ```bash
   gradle :app:assembleDebug
   # APK at: app/build/outputs/apk/debug/app-debug.apk
   ```
2. Side-load the APK (file manager → tap → install). On HyperOS/MIUI you need **Developer options → MIUI optimization off** before accessibility services can be enabled for sideloaded apps.
3. Launch **AgentBridge** → **Open Accessibility Settings** → enable `AgentBridge`.
4. Tap **Start Server**. The notification shows `http://<lan-ip>:8080`. Copy the bearer token from the app.

### On your client (Claude Code)

```bash
claude mcp add --transport sse android-pad http://<pad-ip>:8080/ \
  --header "Authorization: Bearer <paste-token-from-app>"
```

Transport is **legacy SSE** (not Streamable HTTP). Base path is `/`, not `/mcp`.

For Claude Desktop, the equivalent config:
```json
{
  "mcpServers": {
    "android-pad": {
      "type": "sse",
      "url": "http://<pad-ip>:8080/",
      "headers": {
        "Authorization": "Bearer <paste-token-from-app>"
      }
    }
  }
}
```

## Tech stack

- Kotlin, min SDK 29, target SDK 34, compile SDK 35
- Ktor 3.x (CIO engine — smaller footprint than Netty on Android)
- `io.modelcontextprotocol:kotlin-sdk-server`
- kotlinx.serialization, kotlinx.coroutines
- Single debug-signed APK, side-loaded. No DI, no release build hardening.

## Architecture

```
┌────────────────────┐       LAN / SSE          ┌──────────────────────────────┐
│  Client (Mac, ...) │◄────────────────────────►│  Android device              │
│  MCP client        │    Bearer token auth     │  ┌────────────────────────┐  │
└────────────────────┘                          │  │ McpForegroundService   │  │
                                                │  │  ├─ Ktor + MCP SDK     │  │
                                                │  │  └─ ToolRegistry       │  │
                                                │  │                        │  │
                                                │  │ BridgeAccessibilityService │
                                                │  │  ├─ TreeSnapshotter    │  │
                                                │  │  ├─ NodeRegistry       │  │
                                                │  │  └─ GestureDispatcher  │  │
                                                │  └────────────────────────┘  │
                                                └──────────────────────────────┘
```

- **McpForegroundService** — long-running foreground service, persistent notification, owns the Ktor server.
- **BridgeAccessibilityService** — single a11y service, self-reference exposed via companion so tool handlers can reach it.
- **TreeSnapshotter** — walks `service.windows` and builds a serializable `TreeNode` tree under a synthetic `AllWindows` root. Registers each live node into `NodeRegistry`.
- **NodeRegistry** — `Int → AccessibilityNodeInfo` map. Cleared on every `get_ui_tree` / `find_nodes` / `wait_for_node` call. IDs are valid only until the next snapshot.
- **ToolRegistry** — registers all MCP tool handlers with the `Server`.

## Known limitations

- **HyperOS dock is invisible.** `AccessibilityService.getWindows()` does not return the HyperOS tablet dock as a separate window, and it's not inside the launcher's accessibility tree either. Workarounds: `send_intent` / `launch_app` bypass the launcher entirely, or `tap_coords` with a known y-offset.
- **Terminal text input is not supported.** `type_text` uses `ACTION_SET_TEXT`, which only works on editable `EditText`-like nodes. Terminals (Termux etc.) need a different path — the `paste` tool plus `set_clipboard` is usable if you orchestrate the long-press → Paste menu manually.
- **`send_key_events` can't inject arbitrary keystrokes.** The a11y API exposes `ACTION_IME_ENTER` and a few other node actions, but not raw `KeyEvent` injection. Full support would require shipping a companion IME.
- **`get_screenshot` needs per-session consent.** MediaProjection requires a user-visible "Start now" confirmation when the permission is requested. One grant lasts until the app is force-stopped or the user revokes. Fine for interactive sessions, awkward for unattended CI.
- **Clipboard access is OEM-dependent.** Android 10+ restricts `ClipboardManager.getPrimaryClip()` to foreground / default-IME apps. HyperOS appears to allow it from our a11y service, but this isn't guaranteed.
- **Cleartext HTTP + bearer token.** Fine on a trusted LAN; don't expose the port to the internet without a TLS proxy.

## License

MIT — see [LICENSE](LICENSE).

## Credits

Built by [Pluginslab](https://pluginslab.com) as an experiment in LLM-driven device automation.
