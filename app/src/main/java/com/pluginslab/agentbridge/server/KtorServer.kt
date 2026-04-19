package com.pluginslab.agentbridge.server

import com.pluginslab.agentbridge.server.tools.ToolRegistry
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.serialization.json.Json

object KtorServer {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start(port: Int, token: String) {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) {
                json(Json { prettyPrint = true })
            }

            install(SSE)

            install(BearerAuthPlugin) {
                this.token = token
            }

            routing {
                get("/health") {
                    call.respond(mapOf("ok" to true))
                }

                route("/mcp") {
                    mcp {
                        val mcpServer = createMcpServer()
                        ToolRegistry.registerAll(mcpServer)
                        mcpServer
                    }
                }
            }
        }.also {
            it.start(wait = false)
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    private fun createMcpServer(): Server {
        val implementation = Implementation(
            name = "AndroidAgentBridge",
            version = "1.0.0"
        )
        val capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false)
        )
        return Server(implementation, ServerOptions(capabilities))
    }
}
