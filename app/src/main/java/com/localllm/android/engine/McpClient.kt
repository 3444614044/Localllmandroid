package com.localllm.android.engine

import com.localllm.android.R
import com.localllm.android.i18n.AppStrings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class McpTool(
    val name: String,
    val description: String,
    val parameters: String
)

data class McpConnectionResult(
    val isSuccess: Boolean,
    val serverName: String,
    val tools: List<McpTool>,
    val latencyMs: Long,
    val message: String
)

data class McpToolCallResult(
    val isSuccess: Boolean,
    val text: String,
    val message: String
)

/**
 * Real MCP client speaking JSON-RPC 2.0.
 *
 * Transports tried in order:
 *  1. Streamable HTTP — POST initialize/tools/list/tools/call directly to the URL,
 *     accepting both plain-JSON and SSE (`data:`) response bodies.
 *  2. Legacy SSE transport — GET event stream, read the `endpoint` event, then
 *     POST JSON-RPC messages there.
 *
 * Deliberately reports failure instead of inventing tools: a previous version
 * returned hard-coded tools with `isSuccess = true` even when the server was
 * unreachable, which misled both the UI and the model prompt.
 */
class McpClient(client: OkHttpClient? = null) {

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val sseHttp: OkHttpClient = http.newBuilder()
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val idSeq = AtomicLong(0)

    @Volatile private var sessionId: String? = null
    @Volatile private var messageEndpoint: String? = null
    @Volatile private var serverName: String = ""
    @Volatile private var cachedTools: List<McpTool> = emptyList()

    fun currentTools(): List<McpTool> = cachedTools

    fun disconnect() {
        sessionId = null
        messageEndpoint = null
        serverName = ""
        cachedTools = emptyList()
    }

    suspend fun connectServer(url: String): McpConnectionResult = withContext(Dispatchers.IO) {
        disconnect()
        val clean = url.trim()
        if (clean.isBlank()) {
            return@withContext McpConnectionResult(false, "Unknown", emptyList(), 0, AppStrings.get(R.string.mcp_enter_url))
        }
        val parsed = try {
            clean.toHttpUrlOrNull()
        } catch (_: Throwable) {
            null
        }
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return@withContext McpConnectionResult(false, "Unknown", emptyList(), 0, AppStrings.get(R.string.mcp_bad_url))
        }

        val start = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - start }
        val initPayload = rpcRequest(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "LocalLLM-Android").put("version", "1.4.0"))
        )

        // 1) Streamable HTTP: initialize directly on the URL.
        val direct = postJson(clean, initPayload, null)
        if (direct != null && direct.code in 200..299) {
            val name = parseServerName(direct.bodyJson) ?: parsed.host
            sessionId = direct.sessionId ?: sessionId
            messageEndpoint = clean
            postJson(clean, rpcNotification("notifications/initialized", JSONObject()), sessionId)
            return@withContext try {
                val tools = fetchTools(clean)
                cachedTools = tools
                serverName = name
                McpConnectionResult(true, name, tools, elapsed(), AppStrings.get(R.string.mcp_connected, tools.size))
            } catch (e: Exception) {
                disconnect()
                McpConnectionResult(false, name, emptyList(), elapsed(), AppStrings.get(R.string.mcp_tools_fail, e.message))
            }
        }

        // 2) Legacy SSE transport handshake.
        val sseEndpoint = openSseEndpoint(clean)
        if (sseEndpoint != null) {
            val init = postJson(sseEndpoint, initPayload, null)
            if (init != null && init.code in 200..299) {
                val name = parseServerName(init.bodyJson) ?: parsed.host
                messageEndpoint = sseEndpoint
                return@withContext try {
                    val tools = fetchTools(sseEndpoint)
                    cachedTools = tools
                    serverName = name
                    McpConnectionResult(true, name, tools, elapsed(), AppStrings.get(R.string.mcp_connected_sse, tools.size))
                } catch (e: Exception) {
                    disconnect()
                    McpConnectionResult(false, name, emptyList(), elapsed(), AppStrings.get(R.string.mcp_tools_fail, e.message))
                }
            }
        }

        val hint = if (direct != null) "HTTP ${direct.code}" else AppStrings.get(R.string.mcp_conn_fail)
        McpConnectionResult(
            false, parsed.host, emptyList(), elapsed(),
            AppStrings.get(R.string.mcp_handshake, hint)
        )
    }

    suspend fun callTool(name: String, argumentsJson: String = "{}"): McpToolCallResult = withContext(Dispatchers.IO) {
        val endpoint = messageEndpoint
            ?: return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_not_connected))
        val args = try {
            JSONObject(argumentsJson.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
        val payload = rpcRequest("tools/call", JSONObject().put("name", name).put("arguments", args))
        val resp = postJson(endpoint, payload, sessionId)
            ?: return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_call_net))
        if (resp.code !in 200..299) {
            return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_call_http, resp.code))
        }
        val body = resp.bodyJson
            ?: return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_call_parse))
        if (body.has("error") && !body.isNull("error")) {
            val msg = body.optJSONObject("error")?.optString("message") ?: AppStrings.get(R.string.eng_unknown_err)
            return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_tool_error, msg))
        }
        val result = body.optJSONObject("result")
        val texts = mutableListOf<String>()
        val content = result?.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") texts.add(item.optString("text"))
            }
        }
        val failed = result?.optBoolean("isError", false) == true
        McpToolCallResult(
            !failed,
            texts.joinToString("\n"),
            if (texts.isEmpty()) AppStrings.get(R.string.mcp_empty) else AppStrings.get(R.string.mcp_exec_ok)
        )
    }

    companion object {
        internal const val MAX_TOOL_SCHEMA_CHARS = 2000
        internal const val MAX_TOOLS_CONTEXT_CHARS = 4000

        /** Renders the *actually connected* tools for prompt injection. Null when none. */
        fun buildToolsContext(server: String, tools: List<McpTool>): String? {
            if (tools.isEmpty()) return null
            val sb = StringBuilder(AppStrings.get(R.string.mcp_sb_head)).append(server).append(AppStrings.get(R.string.mcp_sb_tail))
            for (t in tools) {
                val schema = if (t.parameters.length > MAX_TOOL_SCHEMA_CHARS) {
                    t.parameters.take(MAX_TOOL_SCHEMA_CHARS) + AppStrings.get(R.string.mcp_schema_omitted)
                } else {
                    t.parameters
                }
                val line = "\n" + AppStrings.get(R.string.mcp_tool_line, t.name, t.description, schema)
                if (sb.length + line.length > MAX_TOOLS_CONTEXT_CHARS) {
                    sb.append("\n" + AppStrings.get(R.string.mcp_more_omitted))
                    break
                }
                sb.append(line)
            }
            return sb.toString()
        }
    }

    // ---- JSON-RPC plumbing ----

    private data class JsonResponse(val code: Int, val sessionId: String?, val bodyJson: JSONObject?)

    private fun rpcRequest(method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idSeq.incrementAndGet())
            .put("method", method)
            .put("params", params)
            .toString()

    private fun rpcNotification(method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()

    private fun postJson(url: String, payload: String, session: String?): JsonResponse? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
            if (!session.isNullOrBlank()) builder.header("Mcp-Session-Id", session)
            builder.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            http.newCall(builder.build()).execute().use { resp ->
                val sid = resp.header("Mcp-Session-Id")
                val raw = try {
                    resp.body?.string()
                } catch (_: Exception) {
                    null
                }
                JsonResponse(resp.code, sid, extractJsonMessage(raw))
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Handles both plain-JSON and SSE (`data: {...}`) response bodies. */
    internal fun extractJsonMessage(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                null
            }
        }
        var last: JSONObject? = null
        for (line in trimmed.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("data:")) continue
            val payload = t.removePrefix("data:").trim()
            if (payload == "[DONE]") continue
            try {
                val obj = JSONObject(payload)
                last = obj
                if (obj.has("result") || obj.has("error")) return obj
            } catch (_: Exception) {
                // Heartbeat / comment lines carry no JSON.
            }
        }
        return last
    }

    private fun parseServerName(envelope: JSONObject?): String? {
        val info = envelope?.optJSONObject("result")?.optJSONObject("serverInfo") ?: return null
        val name = info.optString("name").ifBlank { return null }
        val version = info.optString("version")
        return if (version.isBlank()) name else "$name $version"
    }

    private fun fetchTools(endpoint: String): List<McpTool> {
        val first = postJson(endpoint, rpcRequest("tools/list", JSONObject()), sessionId)
            ?: throw IllegalStateException(AppStrings.get(R.string.mcp_list_net))
        if (first.code !in 200..299) throw IllegalStateException(AppStrings.get(R.string.mcp_list_http, first.code))
        val body = first.bodyJson ?: throw IllegalStateException(AppStrings.get(R.string.mcp_list_parse))
        if (body.has("error") && !body.isNull("error")) {
            val msg = body.optJSONObject("error")?.optString("message") ?: AppStrings.get(R.string.eng_unknown_err)
            throw IllegalStateException(AppStrings.get(R.string.mcp_server_error, msg))
        }
        val arr = body.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        val out = mutableListOf<McpTool>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val toolName = t.optString("name")
            if (toolName.isBlank()) continue
            out.add(McpTool(toolName, t.optString("description"), t.opt("inputSchema")?.toString() ?: "{}"))
        }
        return out
    }

    /** Legacy SSE transport handshake: returns the POST message endpoint, or null. */
    private fun openSseEndpoint(baseUrl: String): String? {
        return try {
            val req = Request.Builder().url(baseUrl).header("Accept", "text/event-stream").get().build()
            sseHttp.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return null
                val contentType = resp.header("Content-Type") ?: ""
                if (!contentType.contains("text/event-stream", ignoreCase = true)) return null
                val source = resp.body?.source() ?: return null
                var eventName: String? = null
                repeat(200) {
                    val line = try {
                        source.readUtf8Line()
                    } catch (_: Exception) {
                        return null
                    } ?: return null
                    val t = line.trim()
                    when {
                        t.startsWith("event:") -> eventName = t.removePrefix("event:").trim()
                        t.startsWith("data:") -> {
                            val data = t.removePrefix("data:").trim()
                            if (eventName == "endpoint" && data.isNotBlank()) {
                                return resolveEndpoint(baseUrl, data)
                            }
                        }
                        t.isEmpty() -> eventName = null
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun resolveEndpoint(baseUrl: String, data: String): String? {
        if (data.startsWith("http://") || data.startsWith("https://")) return data
        return try {
            val base = baseUrl.toHttpUrlOrNull() ?: return null
            base.resolve(data)?.toString()
        } catch (_: Exception) {
            null
        }
    }
}
