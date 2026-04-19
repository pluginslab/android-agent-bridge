package com.pluginslab.agentbridge.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

object BrowserManager {

    private const val TAG = "BrowserMgr"
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 2000
    private const val MAX_CONSOLE = 500

    data class ConsoleEntry(
        val timestamp: Long,
        val level: String,
        val message: String,
        val source: String?,
        val line: Int
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var webView: WebView? = null
    @Volatile private var pageLoadDeferred: CompletableDeferred<Unit>? = null
    private val consoleBuffer = ArrayDeque<ConsoleEntry>()

    val isInitialized: Boolean get() = webView != null

    fun consoleSince(sinceMs: Long?, limit: Int): List<ConsoleEntry> = synchronized(consoleBuffer) {
        val items = if (sinceMs == null) consoleBuffer.toList() else consoleBuffer.filter { it.timestamp >= sinceMs }
        items.take(limit)
    }

    fun clearConsole() = synchronized(consoleBuffer) { consoleBuffer.clear() }

    /** Make the WebView available for the caller to attach to a ViewGroup.
     *  Detaches from any current parent. Caller owns re-attachment. */
    fun acquire(): WebView? {
        val wv = webView ?: return null
        (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        return wv
    }

    /** Reset to headless mode: detach and re-apply a manual measure/layout
     *  so JS/DOM operations (and browser_screenshot) keep working off-screen. */
    fun releaseToHeadless() {
        val wv = webView ?: return
        mainHandler.post {
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(DEFAULT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(DEFAULT_HEIGHT, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        }
    }

    suspend fun ensureInitialized(context: Context) = ensureWebView(context)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(context: Context) {
        if (webView != null) return
        val deferred = CompletableDeferred<Unit>()
        mainHandler.post {
            try {
                val wv = WebView(context.applicationContext)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    userAgentString = "$userAgentString AgentBridge/0.3.0"
                }
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                        synchronized(consoleBuffer) {
                            consoleBuffer.addFirst(
                                ConsoleEntry(
                                    timestamp = System.currentTimeMillis(),
                                    level = cm.messageLevel().name,
                                    message = cm.message(),
                                    source = cm.sourceId(),
                                    line = cm.lineNumber()
                                )
                            )
                            while (consoleBuffer.size > MAX_CONSOLE) consoleBuffer.removeLast()
                        }
                        return true
                    }
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoadDeferred?.takeIf { !it.isCompleted }?.complete(Unit)
                    }
                }
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(DEFAULT_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(DEFAULT_HEIGHT, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)
                webView = wv
                deferred.complete(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "ensureWebView failed", t)
                deferred.completeExceptionally(t)
            }
        }
        deferred.await()
    }

    suspend fun navigate(context: Context, url: String, timeoutMs: Long): Pair<Boolean, String?> {
        ensureWebView(context)
        val deferred = CompletableDeferred<Unit>()
        pageLoadDeferred = deferred
        val loadOk = CompletableDeferred<Boolean>()
        mainHandler.post {
            try {
                webView!!.loadUrl(url)
                loadOk.complete(true)
            } catch (t: Throwable) {
                Log.e(TAG, "loadUrl failed", t)
                loadOk.complete(false)
            }
        }
        if (!loadOk.await()) return Pair(false, "loadUrl threw")
        val finished = withTimeoutOrNull(timeoutMs) { deferred.await() } != null
        return if (finished) Pair(true, null) else Pair(false, "timeout after ${timeoutMs}ms")
    }

    suspend fun eval(context: Context, script: String, timeoutMs: Long): String? {
        ensureWebView(context)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    try {
                        webView!!.evaluateJavascript(script) { result ->
                            if (cont.isActive) cont.resume(result)
                        }
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }

    suspend fun screenshot(context: Context): ByteArray? {
        ensureWebView(context)
        val deferred = CompletableDeferred<ByteArray?>()
        mainHandler.post {
            try {
                val wv = webView!!
                val w = wv.width.takeIf { it > 0 } ?: DEFAULT_WIDTH
                val hContent = wv.contentHeight.takeIf { it > 0 } ?: DEFAULT_HEIGHT
                // Re-layout to full content height so we capture below the fold.
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(hContent, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, w, hContent)
                val bmp = Bitmap.createBitmap(w, hContent, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                wv.draw(canvas)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 85, out)
                bmp.recycle()
                deferred.complete(out.toByteArray())
            } catch (t: Throwable) {
                Log.e(TAG, "screenshot failed", t)
                deferred.complete(null)
            }
        }
        return deferred.await()
    }

    suspend fun setCookie(context: Context, url: String, cookie: String): Boolean {
        ensureInitialized(context) // makes sure the WebView subsystem is warm
        val deferred = CompletableDeferred<Boolean>()
        mainHandler.post {
            try {
                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setCookie(url, cookie) { ok -> deferred.complete(ok) }
                cm.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "setCookie failed", t)
                deferred.complete(false)
            }
        }
        return deferred.await()
    }

    suspend fun clearCookies(context: Context): Boolean {
        ensureInitialized(context)
        val deferred = CompletableDeferred<Boolean>()
        mainHandler.post {
            try {
                val cm = android.webkit.CookieManager.getInstance()
                cm.removeAllCookies { ok -> deferred.complete(ok) }
                cm.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "clearCookies failed", t)
                deferred.complete(false)
            }
        }
        return deferred.await()
    }

    fun destroy() {
        mainHandler.post {
            try { webView?.destroy() } catch (_: Exception) {}
            webView = null
        }
    }
}
