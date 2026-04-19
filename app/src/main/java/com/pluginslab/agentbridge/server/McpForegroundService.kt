package com.pluginslab.agentbridge.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.pluginslab.agentbridge.config.Settings
import com.pluginslab.agentbridge.util.NetworkUtils

class McpForegroundService : Service() {

    companion object {
        private const val TAG = "McpService"
        private const val CHANNEL_ID = "mcp_server_channel"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val port = settings.serverPort
        val token = settings.token
        try {
            KtorServer.start(port, token)
            isRunning = true
            Log.i(TAG, "MCP server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        KtorServer.stop()
        isRunning = false
        Log.i(TAG, "MCP server stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MCP Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the MCP server is running"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val ip = NetworkUtils.getLanIp() ?: "unknown"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AgentBridge MCP Server")
            .setContentText("Running on http://$ip:${settings.serverPort}")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }
}
