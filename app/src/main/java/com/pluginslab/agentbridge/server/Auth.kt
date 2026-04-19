package com.pluginslab.agentbridge.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

class BearerAuthPluginConfig {
    var token: String = ""
}

val BearerAuthPlugin = createApplicationPlugin(
    name = "BearerAuth",
    createConfiguration = ::BearerAuthPluginConfig
) {
    val token = pluginConfig.token

    onCall { call ->
        val path = call.request.local.uri
        // Skip auth for health check
        if (path == "/health") return@onCall

        val authHeader = call.request.headers["Authorization"]
        if (authHeader == null || authHeader != "Bearer $token") {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing bearer token"))
            return@onCall
        }
    }
}
