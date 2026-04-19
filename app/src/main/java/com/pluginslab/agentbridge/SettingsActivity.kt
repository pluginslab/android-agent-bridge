package com.pluginslab.agentbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.pluginslab.agentbridge.accessibility.BridgeAccessibilityService
import com.pluginslab.agentbridge.config.Settings
import com.pluginslab.agentbridge.server.McpForegroundService
import com.pluginslab.agentbridge.util.NetworkUtils

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvLanIp: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvToken: TextView
    private lateinit var btnToggleServer: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = Settings(this)

        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvLanIp = findViewById(R.id.tvLanIp)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvToken = findViewById(R.id.tvToken)
        btnToggleServer = findViewById(R.id.btnToggleServer)

        findViewById<MaterialButton>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnCopyUrl).setOnClickListener {
            val url = getServerUrl()
            copyToClipboard("Server URL", url)
        }

        findViewById<MaterialButton>(R.id.btnCopyToken).setOnClickListener {
            copyToClipboard("Token", settings.token)
        }

        findViewById<MaterialButton>(R.id.btnRegenToken).setOnClickListener {
            settings.regenerateToken()
            updateUI()
            Toast.makeText(this, "Token regenerated", Toast.LENGTH_SHORT).show()
        }

        btnToggleServer.setOnClickListener {
            if (McpForegroundService.isRunning) {
                McpForegroundService.stop(this)
            } else {
                McpForegroundService.start(this)
            }
            // Small delay to let service start/stop
            btnToggleServer.postDelayed({ updateUI() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val isAccessibilityEnabled = BridgeAccessibilityService.instance != null
        tvAccessibilityStatus.text = "Accessibility Service: ${if (isAccessibilityEnabled) "Connected" else "Disabled"}"

        val isServerRunning = McpForegroundService.isRunning
        tvServerStatus.text = "MCP Server: ${if (isServerRunning) "Running" else "Stopped"}"
        btnToggleServer.text = if (isServerRunning) "Stop Server" else "Start Server"

        val lanIp = NetworkUtils.getLanIp() ?: "No LAN IP"
        tvLanIp.text = "LAN IP: $lanIp"

        val serverUrl = getServerUrl()
        tvServerUrl.text = "Server URL: $serverUrl"

        tvToken.text = "Token: ${settings.token}"
    }

    private fun getServerUrl(): String {
        val ip = NetworkUtils.getLanIp() ?: "unknown"
        return "http://$ip:${settings.serverPort}/mcp"
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }
}
