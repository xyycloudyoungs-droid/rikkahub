package me.rerere.rikkahub.data.ai.delegation

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.runAgent
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.fillModelSnapshots
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "AssistantDelegation"

/** 子任务最多请求模型多少次，避免分派出去的助手无限调用工具 */
private const val MAX_STEPS = 32

/** 单次分派的超时时间：必须同步等待结果，不能一直卡住总管这一轮 */
private const val DELEGATION_TIMEOUT_MS = 10 * 60 * 1000L

/** 返回给总管的正文上限，避免一次工具调用塞进过多上下文 */
private const val MAX_RESULT_CHARS = 12_000

/**
 * 标记正在后台执行的子会话。
 *
 * 子会话里的助手不应该继续分派（否则总管会无限套娃），`ChatToolFactory` 据此不给子会话注册
 * `delegate_task` 工具。放在单例对象里而不是服务里，是为了让 `ChatToolFactory` 在构建工具时
 * 不需要解析服务，避免 `ChatService -> ChatRequestFactory -> ChatToolFactory` 的循环依赖。
 */
internal object AssistantDelegationScope {
    private val runningSubConversations = ConcurrentHashMap<String, Boolean>()

    internal fun enter(conversationId: Uuid) {
        runningSubConversations[conversationId.toString()] = true
    }

    internal fun leave(conversationId: Uuid) {
        runningSubConversations.remove(conversationId.toString())
    }

    internal fun isDelegatedSubConversation(conversationId: Uuid): Boolean =
        runningSubConversations.containsKey(conversationId.toString())
}

/** 一次分派的结果，工具会把它转成 JSON 交给总管 */
data class DelegationResult(
    val assistantId: Uuid,
    val assistantName: String,
    val conversationId: Uuid?,
    /** completed / awaiting_approval / paused / step_limit_reached / failed */
    val status: String,
    val text: String,
    val note: String? = null,
)

fun DelegationResult.toToolOutput(): String = JsonInstantPretty.encodeToString(
    buildJsonObject {
        put("status", status)
        put("assistant_id", assistantId.toString())
        put("assistant_name", assistantName)
        conversationId?.let { put("conversation_id", it.toString()) }
        put("result", text)
        note?.let { put("note", it) }
    }
)

/**
 * 总管助手分派子任务的最小实现。
 *
 * 子任务按目标助手的完整配置跑一个真实的子会话（独立模型、系统提示词、工具、记忆），
 * 同步等待结束后把最终文本返回给总管。子会话会落库，之后可以在该助手的历史里打开查看。
 *
 * 这里直接调用 [ChatRequestFactory] + [runAgent]，没有走 `ChatService`：同步调用不需要
 * 会话任务槽，也避免 `ChatToolFactory -> DelegationService -> ChatService` 的循环依赖；
 * 代价是子任务不占用前台生成服务、也不会出现在聊天页的“正在生成”状态里。
 */
class AssistantDelegationService(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val requestFactory: Lazy<ChatRequestFactory>,
) {
    suspend fun delegate(
        managerConversationId: Uuid,
        targetAssistantId: Uuid,
        task: String,
        context: String? = null,
        expectedOutput: String? = null,
    ): DelegationResult = withContext(Dispatchers.IO) {
        val taskText = task.trim()
        require(taskText.isNotEmpty()) { "task is required" }

        // 正常不会走到这里：子会话没有注册 delegate_task。保留这层检查，防止工具定义被复用时套娃。
        check(!AssistantDelegationScope.isDelegatedSubConversation(managerConversationId)) {
            "A delegated sub-task cannot delegate further. Complete the task yourself."
        }

        val settings = settingsStore.awaitLoaded()
        val assistant = settings.getAssistantById(targetAssistantId)
            ?: error("Assistant not found: $targetAssistantId")

        val prompt = buildDelegationPrompt(taskText, context, expectedOutput)
        val child = Conversation.ofId(Uuid.random(), assistantId = assistant.id)
            .bindConfig(settings)
            .copy(
                title = "子任务：${taskText.replace('\n', ' ').take(30)}",
                messageNodes = listOf(UIMessage.user(prompt).toMessageNode()),
            )

        AssistantDelegationScope.enter(child.id)
        try {
            val turn = requestFactory.value.createContext(settings, child)
            val (request, hooks) = requestFactory.value.createAgent(
                turn = turn,
                messages = child.currentMessages,
                processingStatus = MutableStateFlow<String?>(null),
            )
            val finished = withTimeout(DELEGATION_TIMEOUT_MS) {
                runAgent(request.copy(maxSteps = MAX_STEPS), hooks)
                    .filterIsInstance<AgentEvent.Finished>()
                    .single()
            }

            val text = finished.message.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            val status = when (finished.reason) {
                AgentFinishReason.Completed -> "completed"
                is AgentFinishReason.AwaitingApproval -> "awaiting_approval"
                AgentFinishReason.Paused -> "paused"
                AgentFinishReason.StepLimitReached -> "step_limit_reached"
            }
            val note = when (status) {
                "awaiting_approval" -> "The sub-task is waiting for tool approval in its conversation."
                "paused" -> "The sub-task paused before producing a final answer."
                "step_limit_reached" -> "The sub-task reached the step limit; the result may be incomplete."
                else -> null
            }

            persistChild(child, finished.message, settings)

            DelegationResult(
                assistantId = assistant.id,
                assistantName = assistant.name.ifBlank { "Unnamed assistant" },
                conversationId = child.id,
                status = status,
                text = text.ifBlank { "The sub-task finished without textual output." }
                    .take(MAX_RESULT_CHARS),
                note = note,
            )
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Delegated task ${child.id} timed out")
            DelegationResult(
                assistantId = assistant.id,
                assistantName = assistant.name.ifBlank { "Unnamed assistant" },
                conversationId = child.id,
                status = "failed",
                text = "The sub-task timed out after ${DELEGATION_TIMEOUT_MS / 1000} seconds.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Delegated task ${child.id} failed", e)
            DelegationResult(
                assistantId = assistant.id,
                assistantName = assistant.name.ifBlank { "Unnamed assistant" },
                conversationId = child.id,
                status = "failed",
                text = (e.message ?: e::class.simpleName ?: "Unknown error").take(MAX_RESULT_CHARS),
            )
        } finally {
            AssistantDelegationScope.leave(child.id)
        }
    }

    private suspend fun persistChild(
        child: Conversation,
        reply: UIMessage,
        settings: Settings,
    ) {
        val conversation = child.copy(
            messageNodes = child.messageNodes + reply.toMessageNode(),
            updateAt = Instant.now(),
        ).fillModelSnapshots(settings)
        try {
            conversationRepository.insertConversation(conversation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 子任务已经跑完，落库失败不该让总管拿不到结果
            Log.w(TAG, "Failed to persist delegated conversation ${child.id}", e)
        }
    }
}

private fun buildDelegationPrompt(
    task: String,
    context: String?,
    expectedOutput: String?,
): String = buildString {
    appendLine("You are a sub-assistant executing a task delegated by the main assistant.")
    appendLine("Work only on this task. The user cannot see this conversation, so do not ask questions;")
    appendLine("make reasonable assumptions and state them in the final result.")
    appendLine()
    appendLine("## Task")
    appendLine(task)
    if (!context.isNullOrBlank()) {
        appendLine()
        appendLine("## Context and constraints")
        appendLine(context.trim())
    }
    if (!expectedOutput.isNullOrBlank()) {
        appendLine()
        appendLine("## Expected output")
        appendLine(expectedOutput.trim())
    }
    appendLine()
    appendLine("When finished, reply with the final result only.")
}
