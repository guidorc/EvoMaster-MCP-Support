package org.evomaster.core.problem.mcp.service

import com.google.inject.Inject
import org.evomaster.core.problem.mcp.McpCallResult
import org.evomaster.core.problem.mcp.McpIndividual
import org.evomaster.core.problem.mcp.McpResourceReadAction
import org.evomaster.core.problem.mcp.McpToolCallAction
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.FitnessValue
import org.evomaster.core.search.action.ActionResult
import org.evomaster.core.search.gene.BooleanGene
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.gene.ObjectGene
import org.evomaster.core.search.gene.collection.ArrayGene
import org.evomaster.core.search.gene.numeric.NumberGene
import org.evomaster.core.search.gene.string.StringGene
import org.evomaster.core.problem.enterprise.DetectedFault
import org.evomaster.core.problem.enterprise.ExperimentalFaultCategory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Blackbox fitness function for MCP servers.
 *
 * Executes each action in an [McpIndividual] sequentially via the [McpSampler]'s client
 * and scores coverage targets based on observable protocol signals (isError flag,
 * successful reads). Mirrors [GraphQLBlackBoxFitness] in structure.
 */
class McpBlackBoxFitness : McpFitness() {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(McpBlackBoxFitness::class.java)
    }

    @Inject
    private lateinit var sampler: McpSampler

    override fun doCalculateCoverage(
        individual: McpIndividual,
        targets: Set<Int>,
        allTargets: Boolean,
        fullyCovered: Boolean,
        descriptiveIds: Boolean,
    ): EvaluatedIndividual<McpIndividual>? {

        val fv = FitnessValue(individual.size().toDouble())
        val actionResults: MutableList<ActionResult> = mutableListOf()
        val client = sampler.getMcpClient()

        val actions = individual.seeMainExecutableActions()

        for (i in actions.indices) {
            val action = actions[i]
            val result = McpCallResult(action.getLocalId())
            actionResults.add(result)

            try {
                when (action) {
                    is McpToolCallAction -> {
                        val args = geneToMap(action.inputSchema)
                        val toolResult = client.callTool(action.toolName, args)

                        result.setIsError(toolResult.isError)
                        result.stopping = false

                        val successTargetId = idMapper.handleLocalTarget("tool:${action.toolName}:success")
                        val errorTargetId = idMapper.handleLocalTarget("tool:${action.toolName}:error")
                        if (!toolResult.isError) {
                            fv.updateTarget(successTargetId, 1.0, i)
                            fv.updateTarget(errorTargetId, 0.5, i)
                        } else {
                            fv.updateTarget(successTargetId, 0.5, i)
                            fv.updateTarget(errorTargetId, 1.0, i)
                            val faultTargetId = idMapper.handleLocalTarget(
                                idMapper.getFaultDescriptiveId(ExperimentalFaultCategory.MCP_TOOL_ERROR, action.toolName)
                            )
                            fv.updateTarget(faultTargetId, 1.0, i)
                            result.addFault(DetectedFault(ExperimentalFaultCategory.MCP_TOOL_ERROR, action.toolName, null))
                        }
                    }

                    is McpResourceReadAction -> {
                        val resourceResult = client.readResource(action.resolvedUri())
                        result.setIsError(false)
                        result.stopping = false

                        val targetId = idMapper.handleLocalTarget("resource:${action.id}")
                        // A successful read (even empty contents) scores 1.0
                        fv.updateTarget(targetId, 1.0, i)
                    }

                    else -> {
                        result.stopping = false
                    }
                }
            } catch (e: Exception) {
                log.warn("Exception evaluating MCP action ${action.id}: ${e.message}")
                result.setIsError(true)
                result.stopping = true
                val operationId = when (action) {
                    is McpToolCallAction -> action.toolName
                    is McpResourceReadAction -> "resource:${action.resolvedUri()}"
                    else -> action.id
                }
                val faultTargetId = idMapper.handleLocalTarget(
                    idMapper.getFaultDescriptiveId(ExperimentalFaultCategory.MCP_TOOL_ERROR, operationId)
                )
                fv.updateTarget(faultTargetId, 1.0, i)
                result.addFault(DetectedFault(ExperimentalFaultCategory.MCP_TOOL_ERROR, operationId, null))
                break
            }
        }

        return EvaluatedIndividual(
            fv,
            individual.copy() as McpIndividual,
            actionResults,
            trackOperator = individual.trackOperator,
            index = time.evaluatedIndividuals,
            config = config
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Convert an [ObjectGene] to a [Map] of argument values suitable for passing
     * to [McpClient.callTool]. Uses typed gene values when available, falling
     * back to printable string representation for complex types.
     */
    private fun geneToMap(gene: ObjectGene): Map<String, Any?> {
        /**
         * TODO: This will be replaced with an McpActionBuilder module
         * that will convert a tool's input schema into an [ObjectGene]
         */
        val map = mutableMapOf<String, Any?>()
        for (field in gene.fields) {
            map[field.name] = extractGeneValue(field)
        }
        return map
    }

    private fun extractGeneValue(gene: Gene): Any? {
        /**
         * TODO: The McpActionBuilder module will reuse [org.evomaster.core.problem.rest.builder.RestActionBuilderV3]
         * to handle nullable, optional and choice genes within an MCP action gene.
         */
        return when (gene) {
            is StringGene -> gene.value
            is BooleanGene -> gene.value
            is NumberGene<*> -> gene.value
            is ObjectGene -> geneToMap(gene)
            is ArrayGene<*> -> gene.getViewOfElements().map { extractGeneValue(it) }
            else -> gene.getValueAsPrintableString(listOf(), null, null, false)
        }
    }
}
