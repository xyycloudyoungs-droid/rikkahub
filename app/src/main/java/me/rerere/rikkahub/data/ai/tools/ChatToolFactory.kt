package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.delegation.AssistantDelegationScope
import me.rerere.rikkahub.data.ai.delegation.AssistantDelegationService
import me.rerere.rikkahub.data.ai.delegation.toToolOutput
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val backgroundShellManager: BackgroundShellManager,
    private val assistantDelegationService: Lazy<AssistantDelegationService>,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        conversationId: Uuid,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val workspaceTools =
            createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd, conversationId)
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(localTools.getTools(assistant.localTools))
            // 子会话不能再分派，避免总管 -> 子助手 -> 子助手无限套娃
            if (!AssistantDelegationScope.isDelegatedSubConversation(conversationId)) {
                addAll(createAssistantDelegationTool(settings, assistant, conversationId))
            }
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回。
        // 文件放在本机, 只有本地 Rootfs 挂载了 /tool_outputs, 远程工作区同样读不到
        val canReadToolOutputs = workspaceTools.isNotEmpty() &&
            assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }?.capabilities()?.rootfs == true
        return if (canReadToolOutputs) tools.map(::withOutputTruncation) else tools
    }

    /**
     * 把子任务分派给 App 内另一个助手，同步等待结果。
     *
     * 目前所有助手都会看到这个工具，没有助手级开关；子会话靠 [AssistantDelegationScope]
     * 排除在外，所以总管 -> 子助手 -> 子助手不会无限套娃。
     */
    private fun createAssistantDelegationTool(
        settings: Settings,
        assistant: Assistant,
        conversationId: Uuid,
    ): List<Tool> {
        val candidates = settings.assistants.filter { it.id != assistant.id }
        if (candidates.isEmpty()) return emptyList()

        return listOf(
            Tool(
                name = "delegate_task",
                description = """
                    Delegate a self-contained sub-task to another assistant in this app.
                    The sub-task runs in a separate background conversation with that assistant's own model, system prompt, tools and memory.
                    This call waits until the sub-task finishes and returns its final result.
                    Use it when the task is better handled by a specialized assistant, or when a part of the work can be done independently.
                    Only use it when the user asks you to coordinate other assistants, or when the task clearly needs a specialized assistant; do not use it for ordinary conversation.
                    You must pass an assistant id from the available list. Write a complete task: the sub-assistant cannot see this conversation.
                """.trimIndent(),
                systemPrompt = { _, _ ->
                    buildString {
                        appendLine("**Delegation**")
                        appendLine("You can delegate self-contained sub-tasks to these assistants with the `delegate_task` tool:")
                        appendLine("<available_assistants>")
                        candidates.forEach { candidate ->
                            appendLine("  <assistant>")
                            appendLine("    <id>${candidate.id}</id>")
                            appendLine(
                                "    <name>${
                                    candidate.name.ifBlank { "Unnamed assistant" }.take(100).escapeXml()
                                }</name>"
                            )
                            appendLine("  </assistant>")
                        }
                        append("</available_assistants>")
                        appendLine()
                    }
                },
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("assistant_id", buildJsonObject {
                                put("type", "string")
                                put("description", "Id of the assistant that should execute the sub-task.")
                            })
                            put("task", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "A complete, self-contained description of the sub-task. Include all context the sub-assistant needs."
                                )
                            })
                            put("context", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional background, constraints or style requirements.")
                            })
                            put("expected_output", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional description of the expected output format.")
                            })
                        },
                        required = listOf("assistant_id", "task")
                    )
                },
                execute = { args ->
                    val json = args.jsonObject
                    val assistantIdText = json["assistant_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("assistant_id is required")
                    val targetId = runCatching { Uuid.parse(assistantIdText) }.getOrNull()
                        ?: error("Invalid assistant_id: $assistantIdText")
                    val task = json["task"]?.jsonPrimitive?.contentOrNull
                        ?: error("task is required")
                    val contextText = json["context"]?.jsonPrimitive?.contentOrNull
                    val expectedOutput = json["expected_output"]?.jsonPrimitive?.contentOrNull
                    val result = assistantDelegationService.value.delegate(
                        managerConversationId = conversationId,
                        targetAssistantId = targetId,
                        task = task,
                        context = contextText,
                        expectedOutput = expectedOutput,
                    )
                    listOf(UIMessagePart.Text(result.toToolOutput()))
                }
            )
        )
    }

    private fun String.escapeXml(): String = buildString(length) {
        for (c in this@escapeXml) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String?,
        conversationId: Uuid,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, conversationId, backgroundShellManager)
    }
}
