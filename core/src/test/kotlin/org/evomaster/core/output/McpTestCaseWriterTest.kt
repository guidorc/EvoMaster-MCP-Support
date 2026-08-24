package org.evomaster.core.output

import org.evomaster.core.EMConfig
import org.evomaster.core.TestUtils
import org.evomaster.core.output.service.McpTestCaseWriter
import org.evomaster.core.problem.enterprise.EnterpriseActionGroup
import org.evomaster.core.problem.enterprise.SampleType
import org.evomaster.core.problem.mcp.McpAction
import org.evomaster.core.problem.mcp.McpCallResult
import org.evomaster.core.problem.mcp.McpIndividual
import org.evomaster.core.problem.mcp.McpResourceReadAction
import org.evomaster.core.problem.mcp.McpToolCallAction
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.FitnessValue
import org.evomaster.core.search.gene.ObjectGene
import org.evomaster.core.search.gene.numeric.IntegerGene
import org.evomaster.core.search.gene.string.StringGene
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpTestCaseWriterTest {

    private fun buildWriter(): McpTestCaseWriter {
        val config = EMConfig()
        config.outputFormat = OutputFormat.KOTLIN_JUNIT_5
        config.testTimeout = -1
        config.addTestComments = false
        val writer = McpTestCaseWriter()
        val configField = writer.javaClass.superclass.superclass.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(writer, config)
        return writer
    }

    private fun makeGroup(action: McpAction): EnterpriseActionGroup<McpAction> =
        EnterpriseActionGroup(action)

    private fun buildToolCallEI(
        toolName: String,
        vararg fields: Pair<String, String>,
        isError: Boolean = false
    ): EvaluatedIndividual<McpIndividual> {
        val inputGene = ObjectGene("input", fields.map { (k, v) -> StringGene(k, v) })
        val action = McpToolCallAction(toolName, inputGene)
        val individual = McpIndividual(SampleType.RANDOM, mutableListOf(makeGroup(action)))
        TestUtils.doInitializeIndividualForTesting(individual)

        val result = McpCallResult(action.getLocalId())
        result.setIsError(isError)

        return EvaluatedIndividual(FitnessValue(0.0), individual, listOf(result))
    }

    private fun buildResourceReadEI(uri: String): EvaluatedIndividual<McpIndividual> {
        val action = McpResourceReadAction(uri, emptyList(), isTemplate = false)
        val individual = McpIndividual(SampleType.RANDOM, mutableListOf(makeGroup(action)))
        TestUtils.doInitializeIndividualForTesting(individual)

        val result = McpCallResult(action.getLocalId())
        result.setIsError(false)

        return EvaluatedIndividual(FitnessValue(0.0), individual, listOf(result))
    }

    private fun generate(ei: EvaluatedIndividual<McpIndividual>, testName: String = "test0"): String {
        val writer = buildWriter()
        val test = TestCase(test = ei, name = testName)
        return writer.convertToCompilableTestCode(test, "BASE_URL").toString()
    }

    @Test
    fun singleToolCallSuccess_containsCallMcpAndAssertions() {
        val ei = buildToolCallEI("echo", "message" to "hello")
        val code = generate(ei)

        assertTrue(code.contains("callMcp(BASE_URL, \"tools/call\""), "Expected callMcp call, got:\n$code")
        assertTrue(code.contains("\"name\" to \"echo\""), "Expected tool name, got:\n$code")
        assertTrue(code.contains("\"message\" to \"hello\""), "Expected argument, got:\n$code")
        assertTrue(code.contains("assertFalse"), "Expected assertFalse for isError, got:\n$code")
        assertTrue(code.contains("assertNotNull"), "Expected assertNotNull for content, got:\n$code")
    }

    @Test
    fun singleToolCallServerError_assertsTrueIsError() {
        val ei = buildToolCallEI("echo", "message" to "hello", isError = true)
        val code = generate(ei)

        assertTrue(code.contains("callMcp(BASE_URL, \"tools/call\""), "Expected callMcp call, got:\n$code")
        assertTrue(code.contains("assertTrue"), "Expected assertTrue for isError, got:\n$code")
        assertFalse(code.contains("assertFalse"), "Should not contain assertFalse in a fault test, got:\n$code")
        assertFalse(code.contains("assertNotNull"), "Should not contain assertNotNull in a fault test, got:\n$code")
    }

    @Test
    fun singleResourceRead_containsCallMcpAndAssertions() {
        val ei = buildResourceReadEI("file:///data/foo")
        val code = generate(ei)

        assertTrue(code.contains("callMcp(BASE_URL, \"resources/read\""), "Expected resources/read call, got:\n$code")
        assertTrue(code.contains("\"uri\" to \"file:///data/foo\""), "Expected URI, got:\n$code")
        assertTrue(code.contains("assertNotNull(res_"), "Expected assertNotNull on result, got:\n$code")
    }

    @Test
    fun multiActionIndividual_variablesNamedInOrder() {
        val toolAction = McpToolCallAction("ping", ObjectGene("input", emptyList()))
        val resourceAction = McpResourceReadAction("file:///log", emptyList(), isTemplate = false)
        val individual = McpIndividual(SampleType.RANDOM, mutableListOf(makeGroup(toolAction), makeGroup(resourceAction)))
        TestUtils.doInitializeIndividualForTesting(individual)

        val toolResult = McpCallResult(toolAction.getLocalId()).also { it.setIsError(false) }
        val resourceResult = McpCallResult(resourceAction.getLocalId()).also { it.setIsError(false) }

        val ei = EvaluatedIndividual(FitnessValue(0.0), individual, listOf(toolResult, resourceResult))
        val code = generate(ei)

        assertTrue(code.contains("res_0"), "Expected res_0 variable, got:\n$code")
        assertTrue(code.contains("res_1"), "Expected res_1 variable, got:\n$code")
        val indexOfRes0 = code.indexOf("res_0")
        val indexOfRes1 = code.indexOf("res_1")
        assertTrue(indexOfRes0 < indexOfRes1, "res_0 should appear before res_1")
    }

    @Test
    fun nestedObjectGene_emitsMapOf() {
        val nestedGene = ObjectGene("input", listOf(
            ObjectGene("query", listOf(StringGene("q", "test")))
        ))
        val action = McpToolCallAction("search", nestedGene)
        val individual = McpIndividual(SampleType.RANDOM, mutableListOf(makeGroup(action)))
        TestUtils.doInitializeIndividualForTesting(individual)

        val result = McpCallResult(action.getLocalId()).also { it.setIsError(false) }
        val ei = EvaluatedIndividual(FitnessValue(0.0), individual, listOf(result))
        val code = generate(ei)

        assertTrue(code.contains("mapOf("), "Expected nested mapOf, got:\n$code")
    }

    @Test
    fun integerGene_emitsNumericLiteral() {
        val inputGene = ObjectGene("input", listOf(IntegerGene("count", 42)))
        val action = McpToolCallAction("countTool", inputGene)
        val individual = McpIndividual(SampleType.RANDOM, mutableListOf(makeGroup(action)))
        TestUtils.doInitializeIndividualForTesting(individual)

        val result = McpCallResult(action.getLocalId()).also { it.setIsError(false) }
        val ei = EvaluatedIndividual(FitnessValue(0.0), individual, listOf(result))
        val code = generate(ei)

        assertTrue(code.contains("\"count\" to 42"), "Expected integer literal in emitted code, got:\n$code")
    }

    @Test
    fun addExtraStaticVariables_emitsBASE_URLAndSessionId() {
        val writer = buildWriter()
        val lines = Lines(OutputFormat.KOTLIN_JUNIT_5)
        writer.addExtraStaticVariables(lines)
        val output = lines.toString()

        assertTrue(output.contains("BASE_URL"), "Expected BASE_URL constant")
        assertTrue(output.contains("sessionId"), "Expected sessionId variable")
    }
}
