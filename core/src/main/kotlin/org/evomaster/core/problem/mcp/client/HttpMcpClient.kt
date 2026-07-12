package org.evomaster.core.problem.mcp.client

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * [McpClient] implementation that uses the Streamable HTTP transport.
 *
 * All MCP messages are sent as HTTP POST requests to [baseUrl] using JSON-RPC 2.0.
 *
 * **Session lifecycle**: [initialize] must be invoked once before any other method. It performs
 * the two-step MCP handshake (`initialize` request + `notifications/initialized` notification)
 * and captures the `Mcp-Session-Id` header returned by the server.
 *
 * @param baseUrl the full URL of the MCP endpoint.
 */
class HttpMcpClient(private val baseUrl: String) : McpClient {

    private val mapper: ObjectMapper = ObjectMapper()
    private val idCounter = AtomicInteger(1)

    @Volatile private var sessionId: String? = null

    private fun nextId() = idCounter.getAndIncrement()

    private fun openConnection(method: String, params: Map<String, Any?>): Pair<HttpURLConnection, String> {
        val body = mapper.writeValueAsString(
            mapOf(
                "jsonrpc" to "2.0",
                "method" to method,
                "params" to params,
                "id" to nextId()
            )
        )
        val conn = URL(baseUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return Pair(conn, body)
    }


    /**
     * Perform the MCP initialization handshake (initialize + notifications/initialized).
     * Must be called once before any other method.
     */
    fun initialize() {
        val (conn, _) = openConnection(
            "initialize",
            mapOf(
                "protocolVersion" to "2024-11-05",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "EvoMaster", "version" to "1.0.0")
            )
        )
        val status = conn.responseCode
        if (status >= 400) {
            throw IllegalStateException(
                "MCP initialize handshake failed with HTTP $status at '$baseUrl'"
            )
        }
        // Capture session ID before reading the body
        conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
        val responseBody = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        if (responseBody.isBlank()) {
            throw IllegalStateException("MCP initialize handshake returned empty body")
        }
        // Send the required follow-up notification (fire-and-forget)
        postNotification("notifications/initialized", emptyMap())
    }

    /** Send a JSON-RPC notification (no response expected). */
    private fun postNotification(method: String, params: Map<String, Any?>) {
        val body = mapper.writeValueAsString(
            mapOf(
                "jsonrpc" to "2.0",
                "method" to method,
                "params" to params
                // notifications have no "id"
            )
        )
        val conn = URL(baseUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        // Read (and discard) the response to complete the HTTP exchange
        try { conn.inputStream.close() } catch (_: Exception) {}
    }

    /**
     * Send a JSON-RPC request and return the parsed top-level response map (which may carry
     * either a "result" or a JSON-RPC "error" object). Returns null only when no body could be
     * read/parsed at all (e.g. a truly unsupported method with an empty response).
     */
    private fun post(method: String, params: Map<String, Any?> = emptyMap()): Map<String, Any?>? {
        val (conn, _) = openConnection(method, params)
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val responseBody = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        if (responseBody.isBlank()) {
            return null
        }
        return try {
            mapper.readValue(responseBody, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            null
        }
    }

    /** Extract a JSON-RPC "error" object from a parsed response map, if present. */
    private fun extractProtocolError(response: Map<String, Any?>): McpProtocolError? {
        val error = response["error"] as? Map<String, Any?> ?: return null
        val code = (error["code"] as? Number)?.toInt() ?: 0
        val message = error["message"] as? String ?: ""
        return McpProtocolError(code, message)
    }

    override fun listTools(): List<McpToolDefinition> {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        do {
            val params: Map<String, Any?> = if (cursor != null) mapOf("cursor" to cursor) else emptyMap()
            val response = post("tools/list", params) ?: break
            val result = response["result"] as? Map<String, Any?> ?: break
            val items = result["tools"] as? List<*> ?: emptyList<Any>()
            items.filterIsInstance<Map<String, Any?>>().forEach { t ->
                tools.add(
                    McpToolDefinition(
                        name = t["name"] as? String ?: "",
                        description = t["description"] as? String ?: "",
                        inputSchema = t["inputSchema"] as? Map<String, Any?> ?: emptyMap(),
                        outputSchema = t["outputSchema"] as? Map<String, Any?>
                    )
                )
            }
            cursor = result["nextCursor"] as? String
        } while (cursor != null)
        return tools
    }

    override fun listResources(): List<McpResourceDefinition> {
        val resources = mutableListOf<McpResourceDefinition>()
        var cursor: String? = null
        do {
            val params: Map<String, Any?> = if (cursor != null) mapOf("cursor" to cursor) else emptyMap()
            val response = post("resources/list", params) ?: break
            val result = response["result"] as? Map<String, Any?> ?: break
            val items = result["resources"] as? List<*> ?: emptyList<Any>()
            items.filterIsInstance<Map<String, Any?>>().forEach { r ->
                resources.add(
                    McpResourceDefinition(
                        uri = r["uri"] as? String ?: "",
                        name = r["name"] as? String ?: "",
                        description = r["description"] as? String ?: "",
                        mimeType = r["mimeType"] as? String
                    )
                )
            }
            cursor = result["nextCursor"] as? String
        } while (cursor != null)
        return resources
    }

    override fun listResourceTemplates(): List<McpResourceTemplate> {
        val templates = mutableListOf<McpResourceTemplate>()
        var cursor: String? = null
        do {
            val params: Map<String, Any?> = if (cursor != null) mapOf("cursor" to cursor) else emptyMap()
            val response = post("resources/templates/list", params) ?: break
            val result = response["result"] as? Map<String, Any?> ?: break
            val items = result["resourceTemplates"] as? List<*> ?: emptyList<Any>()
            items.filterIsInstance<Map<String, Any?>>().forEach { t ->
                templates.add(
                    McpResourceTemplate(
                        uriTemplate = t["uriTemplate"] as? String ?: "",
                        name = t["name"] as? String ?: "",
                        description = t["description"] as? String ?: ""
                    )
                )
            }
            cursor = result["nextCursor"] as? String
        } while (cursor != null)
        return templates
    }

    override fun callTool(name: String, arguments: Map<String, Any?>): McpToolResult {
        val response = post("tools/call", mapOf("name" to name, "arguments" to arguments))
            ?: return McpToolResult(isError = true)
        val protocolError = extractProtocolError(response)
        if (protocolError != null) {
            return McpToolResult(isError = true, protocolError = protocolError)
        }
        val result = response["result"] as? Map<String, Any?> ?: return McpToolResult(isError = true)
        val rawContent = result["content"] as? List<*> ?: emptyList<Any>()
        val content = rawContent.filterIsInstance<Map<String, Any?>>().map { c ->
            McpContent(
                type = c["type"] as? String ?: "text",
                text = c["text"] as? String,
                uri = c["uri"] as? String,
                mimeType = c["mimeType"] as? String
            )
        }
        return McpToolResult(
            content = content,
            isError = result["isError"] as? Boolean ?: false,
            structuredContent = result["structuredContent"] as? Map<String, Any?>
        )
    }

    override fun readResource(uri: String): McpResourceResult {
        val response = post("resources/read", mapOf("uri" to uri))
            ?: return McpResourceResult()
        val protocolError = extractProtocolError(response)
        if (protocolError != null) {
            return McpResourceResult(protocolError = protocolError)
        }
        val result = response["result"] as? Map<String, Any?> ?: return McpResourceResult()
        val rawContents = result["contents"] as? List<*> ?: emptyList<Any>()
        val contents = rawContents.filterIsInstance<Map<String, Any?>>().map { c ->
            McpContent(
                type = c["type"] as? String ?: "text",
                text = c["text"] as? String,
                uri = c["uri"] as? String,
                mimeType = c["mimeType"] as? String
            )
        }
        return McpResourceResult(contents = contents)
    }
}
