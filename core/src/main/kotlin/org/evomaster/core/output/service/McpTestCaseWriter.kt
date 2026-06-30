package org.evomaster.core.output.service

import org.evomaster.core.output.Lines
import org.evomaster.core.output.OutputFormat
import org.evomaster.core.output.TestCase
import org.evomaster.core.problem.mcp.McpCallResult
import org.evomaster.core.problem.mcp.McpIndividual
import org.evomaster.core.problem.mcp.McpResourceReadAction
import org.evomaster.core.problem.mcp.McpToolCallAction
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.action.Action
import org.evomaster.core.search.action.ActionResult
import org.evomaster.core.search.gene.BooleanGene
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.gene.ObjectGene
import org.evomaster.core.search.gene.collection.ArrayGene
import org.evomaster.core.search.gene.numeric.IntegerGene
import org.evomaster.core.search.gene.numeric.LongGene
import org.evomaster.core.search.gene.string.StringGene
import java.nio.file.Path

class McpTestCaseWriter : ApiTestCaseWriter() {

    override fun addTestCommentBlock(lines: Lines, test: TestCase) {
        val ind = test.test.individual
        if (ind is McpIndividual) {
            val actionNames = ind.seeMainExecutableActions().joinToString(", ") { it.getName() }
            lines.addBlockCommentLine(" MCP test: $actionNames")
        }
    }

    override fun handleTestInitialization(
        lines: Lines,
        baseUrlOfSut: String,
        ind: EvaluatedIndividual<*>,
        insertionVars: MutableList<Pair<String, String>>,
        testName: String
    ) {
        // delegate to super for SQL/Mongo init (no-op for MCP blackbox, but correct hook)
        super.handleTestInitialization(lines, baseUrlOfSut, ind, insertionVars, testName)
    }

    override fun handleActionCalls(
        lines: Lines,
        baseUrlOfSut: String,
        ind: EvaluatedIndividual<*>,
        insertionVars: MutableList<Pair<String, String>>,
        testCaseName: String,
        testSuitePath: Path?
    ) {
        ind.evaluatedMainActions().forEachIndexed { index, evaluatedAction ->
            addActionLines(
                evaluatedAction.action,
                index,
                testCaseName,
                lines,
                evaluatedAction.result,
                testSuitePath,
                baseUrlOfSut
            )
        }
    }

    override fun addActionLinesPerType(
        action: Action,
        index: Int,
        testCaseName: String,
        lines: Lines,
        result: ActionResult,
        testSuitePath: Path?,
        baseUrlOfSut: String
    ) {
        val mcpResult = result as? McpCallResult
            ?: throw IllegalStateException("Expected McpCallResult but got ${result::class.simpleName}")

        val resVarName = createUniqueResponseVariableName()

        when (action) {
            is McpToolCallAction -> addToolCallLines(action, mcpResult, lines, resVarName)
            is McpResourceReadAction -> addResourceReadLines(action, mcpResult, lines, resVarName)
            else -> throw IllegalStateException("Unsupported MCP action type: ${action::class.simpleName}")
        }
    }

    override fun shouldFailIfExceptionNotThrown(result: ActionResult): Boolean = true

    private fun addToolCallLines(
        action: McpToolCallAction,
        result: McpCallResult,
        lines: Lines,
        resVarName: String
    ) {
        val argsLiteral = geneToLiteralKotlin(action.inputSchema)
        lines.add("val $resVarName = callMcp(BASE_URL, \"tools/call\",")
        lines.indented {
            lines.add("mapOf(\"name\" to \"${action.toolName}\", \"arguments\" to $argsLiteral))")
        }

        if (!result.getIsError()) {
            lines.add("assertFalse($resVarName[\"isError\"] as? Boolean ?: false)")
            lines.add("assertNotNull($resVarName[\"content\"])")
        } else {
            lines.addSingleCommentLine("Server returned isError=true during search; replaying call anyway")
            lines.addSingleCommentLine("assertFalse($resVarName[\"isError\"] as? Boolean ?: false)")
            lines.add("assertNotNull($resVarName[\"content\"])")
        }
    }

    private fun addResourceReadLines(
        action: McpResourceReadAction,
        result: McpCallResult,
        lines: Lines,
        resVarName: String
    ) {
        val uri = action.resolvedUri()
        lines.add("val $resVarName = callMcp(BASE_URL, \"resources/read\",")
        lines.indented {
            lines.add("mapOf(\"uri\" to \"$uri\"))")
        }
        lines.addSingleCommentLine("callMcp throws if JSON-RPC returned an error; reaching here means the call succeeded")
        lines.add("assertNotNull($resVarName)")
    }

    private fun geneToLiteralKotlin(gene: Gene): String {
        return when (gene) {
            is StringGene -> "\"${gene.value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            is IntegerGene -> "${gene.value}"
            is LongGene -> "${gene.value}"
            is BooleanGene -> "${gene.value}"
            is ObjectGene -> {
                val fields = gene.fixedFields.joinToString(", ") { f ->
                    "\"${f.name}\" to ${geneToLiteralKotlin(f)}"
                }
                if (fields.isEmpty()) "emptyMap<String, Any?>()" else "mapOf($fields)"
            }
            is ArrayGene<*> -> {
                val items = gene.getViewOfElements().joinToString(", ") { geneToLiteralKotlin(it) }
                if (items.isEmpty()) "emptyList<Any?>()" else "listOf($items)"
            }
            else -> "\"${gene.getValueAsRawString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
    }

    override fun addExtraStaticVariables(lines: Lines) {
        if (!format.isKotlin()) return
        val url = config.bbTargetUrl
        lines.add("const val BASE_URL = \"$url\"")
        lines.add("var sessionId: String = \"\"")
        emitCallMcpHelper(lines)
        emitInitMcpSession(lines, url)
    }

    override fun addExtraInitStatement(lines: Lines) {
        // initMcpSession() is already emitted as a @BeforeAll method via addExtraStaticVariables;
        // nothing extra needed inside initClass()
    }

    override fun additionalTestHandling(tests: List<TestCase>) {
        // nothing extra needed
    }

    private fun emitCallMcpHelper(lines: Lines) {
        lines.addEmpty(1)
        lines.add("fun callMcp(url: String, method: String, params: Map<String, Any?>): Map<String, Any?> {")
        lines.indented {
            lines.add("val mapper = com.fasterxml.jackson.databind.ObjectMapper()")
            lines.add("val requestId = System.currentTimeMillis()")
            lines.add("val bodyMap = mapOf(\"jsonrpc\" to \"2.0\", \"id\" to requestId, \"method\" to method, \"params\" to params)")
            lines.add("val bodyBytes = mapper.writeValueAsBytes(bodyMap)")
            lines.add("val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection")
            lines.add("conn.requestMethod = \"POST\"")
            lines.add("conn.setRequestProperty(\"Content-Type\", \"application/json\")")
            lines.add("conn.setRequestProperty(\"Accept\", \"application/json, text/event-stream\")")
            lines.add("if (sessionId.isNotEmpty()) conn.setRequestProperty(\"Mcp-Session-Id\", sessionId)")
            lines.add("conn.doOutput = true")
            lines.add("conn.outputStream.use { it.write(bodyBytes) }")
            lines.add("val response = conn.inputStream.bufferedReader().readText()")
            lines.add("@Suppress(\"UNCHECKED_CAST\")")
            lines.add("val parsed = mapper.readValue(response, Map::class.java) as Map<String, Any?>")
            lines.add("check(parsed[\"error\"] == null) { \"JSON-RPC error: \${parsed[\\\"error\\\"]}\" }")
            lines.add("@Suppress(\"UNCHECKED_CAST\")")
            lines.add("return parsed[\"result\"] as? Map<String, Any?> ?: emptyMap()")
        }
        lines.add("}")
    }

    private fun emitInitMcpSession(lines: Lines, baseUrl: String) {
        lines.addEmpty(1)
        lines.add("@BeforeAll")
        lines.add("@JvmStatic")
        lines.add("fun initMcpSession() {")
        lines.indented {
            lines.add("val mapper = com.fasterxml.jackson.databind.ObjectMapper()")
            lines.add("val initBody = mapper.writeValueAsBytes(mapOf(")
            lines.indented {
                lines.add("\"jsonrpc\" to \"2.0\", \"id\" to 1, \"method\" to \"initialize\",")
                lines.add("\"params\" to mapOf(")
                lines.indented {
                    lines.add("\"protocolVersion\" to \"2024-11-05\",")
                    lines.add("\"clientInfo\" to mapOf(\"name\" to \"EvoMasterTest\", \"version\" to \"1.0\"),")
                    lines.add("\"capabilities\" to emptyMap<String, Any>()")
                }
                lines.add(")")
            }
            lines.add("))")
            lines.add("val conn = java.net.URL(\"$baseUrl\").openConnection() as java.net.HttpURLConnection")
            lines.add("conn.requestMethod = \"POST\"")
            lines.add("conn.setRequestProperty(\"Content-Type\", \"application/json\")")
            lines.add("conn.setRequestProperty(\"Accept\", \"application/json, text/event-stream\")")
            lines.add("conn.doOutput = true")
            lines.add("conn.outputStream.use { it.write(initBody) }")
            lines.add("val sessionHeader = conn.getHeaderField(\"Mcp-Session-Id\")")
            lines.add("if (sessionHeader != null) sessionId = sessionHeader")
            lines.addEmpty(1)
            lines.addSingleCommentLine("send notifications/initialized")
            lines.add("val notifBody = mapper.writeValueAsBytes(mapOf(")
            lines.indented {
                lines.add("\"jsonrpc\" to \"2.0\", \"method\" to \"notifications/initialized\", \"params\" to emptyMap<String, Any>()")
            }
            lines.add("))")
            lines.add("val conn2 = java.net.URL(\"$baseUrl\").openConnection() as java.net.HttpURLConnection")
            lines.add("conn2.requestMethod = \"POST\"")
            lines.add("conn2.setRequestProperty(\"Content-Type\", \"application/json\")")
            lines.add("conn2.setRequestProperty(\"Accept\", \"application/json, text/event-stream\")")
            lines.add("if (sessionId.isNotEmpty()) conn2.setRequestProperty(\"Mcp-Session-Id\", sessionId)")
            lines.add("conn2.doOutput = true")
            lines.add("conn2.outputStream.use { it.write(notifBody) }")
            lines.add("conn2.responseCode")
        }
        lines.add("}")
    }
}
