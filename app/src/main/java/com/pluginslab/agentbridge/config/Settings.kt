package com.pluginslab.agentbridge.config

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agentbridge_prefs", Context.MODE_PRIVATE)

    var token: String
        get() {
            val existing = prefs.getString("bearer_token", null)
            if (existing != null) return existing
            val newToken = generateToken()
            token = newToken
            return newToken
        }
        set(value) {
            prefs.edit().putString("bearer_token", value).apply()
        }

    var serverPort: Int
        get() = prefs.getInt("server_port", 8080)
        set(value) {
            prefs.edit().putInt("server_port", value).apply()
        }

    fun regenerateToken(): String {
        val newToken = generateToken()
        token = newToken
        return newToken
    }

    private fun generateToken(): String = UUID.randomUUID().toString()
}
