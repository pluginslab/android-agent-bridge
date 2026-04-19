# AndroidAgentBridge

Turn an Android device into a remote-controllable automation surface for LLM agents, over LAN.

The app runs a tiny Ktor + MCP server on the device. An accessibility service reads the UI tree and dispatches gestures. Any MCP-capable client (Claude Code, Claude Desktop, a custom agent) on the same network can read the screen and perform taps, swipes, text input, and intent launches through a bearer-authenticated HTTP/SSE endpoint.

Built for end-to-end testing, lightweight device automation, and "claude, do something on my tablet" demos. v1 is debug-signed and side-loaded; no Play Store distribution.

## Status

v0.4.2 — stable batch scripting + cookie injection for the embedded browser. Tested on a Xiaomi Pad 8 Pro (HyperOS, Android 15) and a MacBook client. Other devices/Android versions are untested but nothing is hardware-specific.

See the [GitHub Releases page](https://github.com/pluginslab/android-agent-bridge/releases) for the full history. Recent stops:

- **0.4.2** Cookie injection fix — `CookieManager` calls must run on a thread with a Looper, so they're now dispatched through `BrowserManager`'s main handler. `browser_set_cookie` confirmed working end-to-end with LinkedIn (authenticated feed scraping).
- **0.4.1** `browser_set_cookie` / `browser_clear_cookies` — inject HttpOnly session cookies into the WebView's jar.
- **0.4.0** `run_script` + `probe` — batch a multi-step flow in a single MCP call, no per-step model round-trip.
- **0.3.1–0.3.2** Visible embedded-browser UI (URL bar + back/reload), no-auto-keyboard polish.
- **0.3.0** Embedded headless WebView with JS eval + console capture.

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

### Embedded browser (the DevTools-without-CDP trick)
A second browser lives inside AgentBridge — an Android WebView with a simple URL bar (tap **Open Embedded Browser** in the app). It's sandboxed from Chrome, so logged-in sessions and cookies *don't* carry over from Chrome by default — which is actually a feature for deterministic E2E testing. The tools drive it from outside; the UI lets you inspect what's happening.

- `browser_navigate` — load a URL, wait for `onPageFinished`. Clears the console buffer on each call.
- `browser_eval` — run JavaScript, returns the JSON-encoded result. Multi-statement? Wrap in an IIFE and return.
- `browser_console` — ring buffer (last 500) of captured `console.*` messages with level, source, and line.
- `browser_info` — current URL + `document.title`.
- `browser_html` — `documentElement.outerHTML`, or pass a CSS `selector` to get a single element's `outerHTML`.
- `browser_screenshot` — draws the WebView's current rendering to a PNG. Captures full content height, below the fold included. No MediaProjection needed.
- `browser_set_cookie` — inject a cookie into the WebView's jar via Android's native `CookieManager`. Works for `HttpOnly` cookies that JavaScript can't set (e.g. LinkedIn's `li_at`). Typical workflow: in desktop Chrome's DevTools → Application → Cookies, copy the session cookie value, paste it here, then `browser_navigate` to an authenticated URL.
- `browser_clear_cookies` — reset the jar.

### Batch scripting
Eliminates model round-trips for multi-step flows. The agent composes a sequence of ops once, the device executes them at device speed.

- `probe` — compact, curated screen survey: active window + up to N clickable/editable/focused/scrollable nodes + distinct visible texts. Much cheaper to consume than a full `get_ui_tree` when you just want to decide "what's actionable here?". Populates the node registry so IDs stay usable with subsequent calls.
- `run_script` — execute a sequence of device-side ops in a single MCP call. Node predicates (`text_contains`, `resource_id`, `class_name`, …) are re-resolved on every step, so ephemeral node IDs never go stale. Ops supported include everything under gestures & input, app & intent launching, and embedded browser, plus `wait_for_node`, `wait_for_window`, `sleep`, `assert_node`, and `capture` (gathers final state: `find_nodes` results, device screenshot, browser screenshot, `browser_info`, active window — into named buckets in the return value). Returns a per-step trace (`{step, op, ok, ms, error?, detail?}`) so failures are diagnosable.

Typical pattern on a new flow: one `probe` to learn the surface, one `run_script` to execute the whole thing.

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

## Contributing

This is a hobby/research project — PRs and issues welcome. The feedback loop feels tight once you've internalised the ritual, but the first time through there are a surprising number of small things that can trip you up. Below is everything we've learned running it day-to-day.

### Build & install

Anywhere with JDK 17 + Android SDK 35 works (Mac, Linux, even Termux on the target device itself).

```bash
gradle :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Side-load on the target device. No signing configuration needed for debug.

### The inner loop

Once the app is installed and running, the minimum repro cycle after each edit is:

1. `gradle :app:assembleDebug` — incremental builds are 5–15s.
2. Push the APK (e.g. `scp` / `adb install -r` / copy to `/sdcard/Download` and tap).
3. Tap-install on the device → **Update** (replaces the prior install).
4. **Re-enable the accessibility toggle** — see HyperOS gotcha below; on most OEMs it survives updates but HyperOS drops it.
5. **Start Server** in the app if it isn't still running.
6. In your MCP client, reconnect to pick up new tools (Claude Code: `/mcp` → reconnect `android-pad`). Tool schemas are cached at connect time — without a reconnect new tools are invisible to the client.

Steps 4–6 are annoying but mandatory. Build muscle memory for them.

### HyperOS / MIUI gotchas

- **MIUI optimization must be OFF** before any sideloaded accessibility service can be enabled. Developer options → *MIUI optimization* → off → reboot. Without this the Accessibility toggle either doesn't appear or silently refuses to flip.
- **The accessibility toggle gets dropped after most APK updates.** Same package, same signing — doesn't matter. Re-enable manually: Settings → Accessibility → AgentBridge → On.
- **HyperOS dock is invisible to `service.windows`.** The tablet dock is drawn on screen but isn't exposed through the accessibility API at all, even with `flagRetrieveInteractiveWindows`. Don't waste time trying to tap dock icons — use `launch_app` / `open_url` / `send_intent` instead, which bypass the launcher entirely.
- **Background activity starts from Termux uid are blocked.** You can SSH in and run `am start`, but HyperOS will silently refuse to foreground the target. This is why the bridge has its own `open_url` / `launch_app` / `send_intent` — they run from the accessibility service's context, which has more latitude.
- **HyperOS doesn't let a regular app read `logcat` from other UIDs.** See "Debugging crashes without ADB" below.

### Debugging crashes without ADB

The app installs a `Thread.setDefaultUncaughtExceptionHandler` in `App.kt` that writes the stack trace to `/sdcard/Download/agentbridge-crash.txt`. This is readable from Termux or over `scp`, so you don't need root or ADB to see why the app died.

```bash
# From Termux on the device:
cat /sdcard/Download/agentbridge-crash.txt
# From your laptop:
scp <pad>:/sdcard/Download/agentbridge-crash.txt .
```

If you're about to reproduce a crash, delete the file first so you know the new trace is fresh.

### MCP transport quirks

- **The server speaks legacy SSE, not Streamable HTTP.** When wiring up clients, the transport type must be `sse` and the base URL is `/`, not `/mcp`. Example for Claude Code:
  ```bash
  claude mcp add --transport sse android-pad http://<pad-ip>:8080/ \
    --header "Authorization: Bearer <token>"
  ```
- **The URL shown in `SettingsActivity` says `/mcp`** — known cosmetic bug. The actual endpoint is `/`. Ignore the displayed path when configuring clients. (Fix is coming; the typo predates the transport switch.)
- **Tool lists are snapshotted at connect time.** If you've added, removed, or renamed a tool, the client won't see it until you disconnect + reconnect. In Claude Code: `/mcp` dialog → pick the server → disconnect → reconnect.

### MediaProjection (for `get_screenshot`)

- One consent dialog per capture session. Survives until the app is force-stopped or the user revokes via the notification shade.
- On Android 14+, there's a tight time window between the user granting consent and the service successfully creating the `VirtualDisplay`. If screen capture crashes right after granting, it's usually a `SecurityException` about using the MediaProjection token after / before the foreground service was properly started. Check `/sdcard/Download/agentbridge-crash.txt`.
- The capture service has its own foreground notification separate from the MCP server's. Both show in the shade when capture is active.

### Building on the device (Termux)

Building inside Termux + proot-debian works but has one wrinkle: AGP bundles an `aapt2` that's x86_64-only. On an arm64 device this silently fails with cryptic errors. Workaround in `gradle.properties`:

```
android.aapt2FromMavenOverride=/path/to/arm64-aapt2
```

Most contributors won't hit this because most contributors will build on x86_64 hosts.

### Adding a new tool

Minimal recipe:

1. Add `registerMyTool(server: Server)` to `ToolRegistry.kt`, copying the shape of a neighbouring tool. Each tool gets: a name, a description (the client sees this — make it specific), an input schema, and a handler.
2. Reference the new registration from `registerAll` at the top of the object.
3. Rebuild, reinstall, reconnect MCP. Call it.

Rules of thumb that match the existing style:

- Keep the input schema tight. Only add a parameter if it changes behaviour meaningfully.
- Prefer `jsonResult` for structured output, `textResult` for strings, `ImageContent` for PNGs.
- If the tool needs the accessibility service, call `getService() ?: return@addTool serviceError()` first. If it just needs a `Context`, grab `svc.applicationContext`.
- `NodeRegistry` IDs are ephemeral — valid only until the next `get_ui_tree` / `find_nodes` / `wait_for_node` / `scroll_to_text` / `probe` call. Tools that operate on a node ID should be called soon after the snapshot that produced it.
- If the new op is also useful inside a scripted flow, add a branch for it in `runScriptStep(...)` so `run_script` can dispatch it.

### Adding a new embedded-browser capability

If it's just JavaScript, `browser_eval` probably covers it from the client side — no new tool needed. Add a dedicated tool only when:

- The feature requires the native Android surface (e.g. `CookieManager` for `HttpOnly` cookies, clipboard, downloads, file pickers).
- You want idiomatic input/output types instead of asking callers to stringify their own JS.

The `BrowserManager` singleton owns the WebView. If your new tool needs a WebView-level hook (e.g. intercepting requests, subscribing to load events), add it there and expose a thin wrapper in `ToolRegistry`. The `BrowserActivity` borrows the WebView on resume and returns it on pause — don't assume the Activity is alive, but do assume the WebView is (`BrowserManager.ensureInitialized` is safe to call).

### Filing issues

Reproducers that include:
- Android version + OEM skin (HyperOS, One UI, Pixel, stock AOSP…)
- AgentBridge version (from SettingsActivity — still shows Server URL with a typo, use `versionName` from `build.gradle.kts`)
- Output of `find_nodes` or `get_ui_tree` near the failure
- Contents of `agentbridge-crash.txt` if the app died

are massively easier to act on than "it doesn't work". If you can record the exact sequence of tool calls that led to the failure, even better.

## License

MIT — see [LICENSE](LICENSE).

## Credits

Built by [Pluginslab](https://pluginslab.com) as an experiment in LLM-driven device automation.
