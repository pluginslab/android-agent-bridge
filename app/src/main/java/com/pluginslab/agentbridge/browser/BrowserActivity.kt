package com.pluginslab.agentbridge.browser

import android.os.Bundle
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.pluginslab.agentbridge.R
import kotlinx.coroutines.launch

class BrowserActivity : ComponentActivity() {

    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var header: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        container = findViewById(R.id.browserContainer)
        urlBar = findViewById(R.id.urlBar)
        header = findViewById(R.id.browserHeader)

        findViewById<MaterialButton>(R.id.browserGo).setOnClickListener { navigate() }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigate(); true
            } else false
        }

        findViewById<MaterialButton>(R.id.browserBack).setOnClickListener {
            BrowserManager.acquire()?.let { if (it.canGoBack()) it.goBack() }
        }
        findViewById<MaterialButton>(R.id.browserReload).setOnClickListener {
            BrowserManager.acquire()?.reload()
        }
    }

    private fun navigate() {
        val url = urlBar.text.toString().trim().ifBlank { return }
        lifecycleScope.launch {
            BrowserManager.ensureInitialized(this@BrowserActivity)
            BrowserManager.clearConsole()
            BrowserManager.navigate(this@BrowserActivity, url, 15000L)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            BrowserManager.ensureInitialized(this@BrowserActivity)
            val wv = BrowserManager.acquire() ?: return@launch
            if (wv.parent != container) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                container.addView(
                    wv,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
            }
            // Keep URL bar in sync with current location.
            val url = BrowserManager.eval(this@BrowserActivity, "window.location.href", 2000L)
            if (url != null && url != "null") {
                urlBar.setText(url.trim('"'))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        BrowserManager.releaseToHeadless()
    }
}
