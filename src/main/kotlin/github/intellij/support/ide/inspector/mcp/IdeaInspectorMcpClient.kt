package github.intellij.support.ide.inspector.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service(Service.Level.APP)
class IdeaInspectorMcpClient {
    private val log = logger<IdeaInspectorMcpClient>()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    sealed class Result {
        data object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    fun showFileHistoryForFqn(url: String, fqn: String, projectPath: String): Result {
        return try {
            val initBody = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "IDE Inspector")
                        put("version", "1.0")
                    }
                }
            }.toString()
            val initResp = post(url, null, initBody)
            if (initResp.statusCode() !in 200..299) {
                return Result.Failed("initialize HTTP ${initResp.statusCode()}: ${initResp.body()}")
            }
            val sessionId = initResp.headers().firstValue(MCP_SESSION_ID).orElse(null)
                ?: return Result.Failed("missing $MCP_SESSION_ID header in initialize response")

            val initializedBody = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }.toString()
            post(url, sessionId, initializedBody)

            val callBody = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "show_file_history_for_fqn")
                    putJsonObject("arguments") {
                        put("fqn", fqn)
                        put("projectPath", projectPath)
                    }
                }
            }.toString()
            val callResp = post(url, sessionId, callBody)
            if (callResp.statusCode() !in 200..299) {
                return Result.Failed("tools/call HTTP ${callResp.statusCode()}: ${callResp.body()}")
            }
            parseCallResult(callResp.body())
        } catch (t: Throwable) {
            log.info("MCP call failed", t)
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun parseCallResult(body: String): Result {
        val root = json.parseToJsonElement(body).jsonObject
        root["error"]?.let { return Result.Failed("MCP error: $it") }
        val result = root["result"]?.jsonObject ?: return Result.Failed("missing result: $body")
        val isError = result["isError"]?.jsonPrimitive?.boolean == true
        if (isError) {
            val text = result["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            return Result.Failed("tool error: ${text ?: result}")
        }
        return Result.Ok
    }

    private fun post(url: String, session: String?, body: String): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        if (session != null) b.header(MCP_SESSION_ID, session)
        val req = b.POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private const val MCP_SESSION_ID = "mcp-session-id"
    }
}
