package com.mbclaw.dev.agent

import android.content.Context
import com.mbclaw.dev.api.DirectApiClient
import com.mbclaw.dev.data.LocalDB
import com.mbclaw.dev.data.UserSettings
import com.mbclaw.dev.hermes.RealEngine
import com.mbclaw.dev.hermes.LayeredSearch
import com.mbclaw.dev.model.ProviderCatalog
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Agent 执行循环 — LLM决策 → 工具调用 → 观察结果 → 继续
 *
 * 这才是 OpenClaw 的核心: 不是聊天，是 agent loop
 */
class AgentLoop(
    private val context: Context,
    private val db: LocalDB,
    private val settings: UserSettings,
) {
    private val realEngine = RealEngine(db, settings)
    private val toolExecutor = ToolExecutor(context, db, settings, realEngine)
    private val layeredSearch = LayeredSearch(db, com.mbclaw.dev.hermes.TranscriptLogger(context))
    private val enforcer = MBclawEnforcer(db, layeredSearch)
    private val gson = Gson()
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    // 当前任务状态（UI 顶部可观察）
    @Volatile var running: Boolean = false; private set
    @Volatile var currentTurn: Int = 0; private set
    @Volatile var currentTool: String = ""; private set
    @Volatile var totalTurns: Int = 20; private set
    private val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /** UI 调用：终止当前 agent 循环 */
    fun cancel() { cancelFlag.set(true) }

    /** UI 调用：实时状态 */
    fun statusLine(): String = when {
        !running -> ""
        currentTool.isNotBlank() -> "🤖 第 $currentTurn/$totalTurns 轮 · 调用 $currentTool"
        else -> "🤖 第 $currentTurn/$totalTurns 轮 · 思考中…"
    }

    suspend fun run(
        userMessage: String,
        sessionId: String,
        maxTurns: Int = 20,
        onStatus: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        running = true
        cancelFlag.set(false)
        totalTurns = maxTurns
        currentTurn = 0
        currentTool = ""

        try {
        // 蓝图P7: 保存checkpoint
        val taskId = System.currentTimeMillis()
        com.mbclaw.dev.hermes.BlueprintComplete(context, db).taskEnqueue("agent_loop", 50, userMessage)

        // 蓝图08 P0: 检测上下文长度,接近限制触发Memory Flush
        val history = db.getMessages(sessionId, 20)
        if (history.size > 15) {
            memoryFlush(sessionId, history)
        }
        // ═══ PRE: 代码强制构建上下文 (不等LLM请求) ═══
        val ctx = enforcer.buildContext(userMessage, sessionId)
        val hadMemories = ctx.memoryInjection.isNotBlank()

        // ★ BugE修复: 权限状态已包含在 identityConstraint 中, 不再重复注入
        // AgentLoop 只注入: 身份+权限+工具+记忆, 各一条, 避免 LLM 看到重复信息

        val messages = mutableListOf<AgentMsg>()
        // 1. 身份约束 (含权限声明 — 来自 MBclawEnforcer)
        messages.add(AgentMsg("system", ctx.identityConstraint))
        // 2. 当前助手人格
        val assistantId = context.getSharedPreferences("mb_assistant", android.content.Context.MODE_PRIVATE)
            .getString("id", "default") ?: "default"
        val assistant = com.mbclaw.dev.data.AssistantCatalog.byId(assistantId)
        messages.add(AgentMsg("system", "你当前的人格: ${assistant.name}\n${assistant.systemPrompt}"))
        // 3. 强制能力声明 (工具列表)
        messages.add(AgentMsg("system", ctx.capabilityInjection))
        // 4. 强制记忆注入
        if (hadMemories) {
            messages.add(AgentMsg("system", ctx.memoryInjection))
        }

        // 历史已加载
        for (msg in history.takeLast(10)) {
            messages.add(AgentMsg(msg.role, msg.content))
        }
        messages.add(AgentMsg("user", userMessage))

        var lastResponse = ""
        var turns = 0

        while (turns < maxTurns) {
            if (cancelFlag.get()) { lastResponse = "⏹ 已手动终止 (第 $turns 轮)"; break }
            turns++
            currentTurn = turns
            currentTool = ""
            onStatus?.invoke(statusLine())
            // 最后一轮: 强制 LLM 给最终答案，不再执行工具
            if (turns >= maxTurns) {
                messages.add(AgentMsg("system", "已达最大轮次。请直接给出最终回答，不要再调用工具。"))
            }
            val result = callWithTools(messages)
            if (cancelFlag.get()) { lastResponse = "⏹ 已手动终止 (第 $turns 轮)"; break }
            if (result.toolCall != null && turns < maxTurns) {
                currentTool = result.toolCall.name
                onStatus?.invoke(statusLine())
                val toolResult = toolExecutor.execute(result.toolCall.name, result.toolCall.arguments)
                messages.add(AgentMsg("assistant", null, listOf(result.toolCall)))
                messages.add(AgentMsg("tool", toolResult, toolCallId = result.toolCall.id))
                continue
            } else {
                lastResponse = result.content ?: "完成"
                messages.add(AgentMsg("assistant", lastResponse))
                // ★ BugD修复: 不在这里保存消息 (ChatViewModel.send已经存了, 避免双份)
                // AgentLoop 只负责 Agent 逻辑, 持久化由 ChatViewModel 负责
                break
            }
        }

        if (lastResponse.isBlank()) lastResponse = "已达到最大轮次($maxTurns)，操作结束。"

        // ═══ POST: 代码强制验证 + 修正 ═══
        val check = enforcer.validateResponse(lastResponse, hadMemories)
        if (!check.passed) {
            lastResponse = enforcer.correctResponse(lastResponse)
        }

        // P1: 记录thinking到messages
        db.writableDatabase.execSQL("UPDATE messages SET thinking=?, message_type='thinking' WHERE id=(SELECT id FROM messages WHERE session_id=? AND role='assistant' ORDER BY id DESC LIMIT 1)", arrayOf("agent_loop_${turns}turns", sessionId))

        return@withContext lastResponse
        } finally {
            running = false
            currentTool = ""
            onStatus?.invoke("")
        }
    }

    // 蓝图08 P0: Memory Flush — 上下文接近限制时静默保存
    private suspend fun memoryFlush(sessionId: String, history: List<com.mbclaw.dev.data.MessageRow>) {
        val summary = history.takeLast(10).joinToString("; ") { it.content.take(100) }
        db.saveMemory("flush_${sessionId}_${System.currentTimeMillis()}", summary, "memory_flush")
        // 通知enforcer下次注入时包含flush内容
    }

    // ── function calling API ──

    data class ToolCallRequest(val name: String, val arguments: JSONObject, val id: String = "call_${System.currentTimeMillis()}")
    data class LLMResult(val content: String? = null, val toolCall: ToolCallRequest? = null)

    private suspend fun callWithTools(messages: List<AgentMsg>): LLMResult {
        val baseUrl = settings.apiBaseUrl.ifBlank {
            ProviderCatalog.find(settings.providerId)?.baseUrl ?: ""
        }
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        // 构建请求体: messages + tools + tool_choice
        val body = JSONObject()
        body.put("model", settings.modelName)
        body.put("temperature", 0.7)
        body.put("max_tokens", 2048)
        body.put("tools", ToolRegistry.toOpenAITools())
        body.put("tool_choice", "auto")

        val msgsArr = org.json.JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put("role", msg.role)
            if (msg.content != null) obj.put("content", msg.content)
            if (msg.toolCalls != null) {
                val tcArr = org.json.JSONArray()
                for (tc in msg.toolCalls) {
                    tcArr.put(JSONObject().apply {
                        put("id", tc.id); put("type", "function")
                        put("function", JSONObject().apply { put("name", tc.name); put("arguments", tc.arguments.toString()) })
                    })
                }
                obj.put("tool_calls", tcArr)
            }
            if (msg.toolCallId != null) obj.put("tool_call_id", msg.toolCallId)
            msgsArr.put(obj)
        }
        body.put("messages", msgsArr)

        // X-Utopia: 算力分账标识
        // X-User-Id: 用户标识 (服务端统计/追踪)
        val account = com.mbclaw.dev.data.AccountManager.load(context)
        val userId = account.qqId.ifBlank { account.weixinId }.ifBlank { "anon-${com.mbclaw.dev.agent.AntiTamper.deviceFingerprint(context).take(8)}" }

        val request = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Utopia", if (settings.utopiaEnabled) "1" else "0")
            .addHeader("X-User-Id", userId)
            .addHeader("X-Client-Version", com.mbclaw.dev.BuildConfig.VERSION_NAME)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()

        val response = http.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        val resp = JSONObject(responseBody)

        if (!response.isSuccessful) {
            val err = resp.optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
            return LLMResult(content = "❌ $err")
        }

        val choices = resp.optJSONArray("choices")
        val choice = choices?.optJSONObject(0) ?: return LLMResult(content = "无响应")
        val msg = choice.optJSONObject("message")

        // 检查 tool_calls
        val toolCalls = msg?.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            val tc = toolCalls.getJSONObject(0)
            val func = tc.optJSONObject("function")
            val toolName = func?.optString("name") ?: ""
            val toolArgs = try { JSONObject(func?.optString("arguments") ?: "{}") } catch (_: Exception) { JSONObject() }
            return LLMResult(toolCall = ToolCallRequest(toolName, toolArgs, tc.optString("id", "call_0")))
        }

        // 纯文本回复
        return LLMResult(content = msg?.optString("content") ?: "无回复")
    }
}

// ── Agent专用消息 (不与api.ChatMessage冲突) ──
data class AgentMsg(
    val role: String,
    val content: String? = null,
    val toolCalls: List<AgentLoop.ToolCallRequest>? = null,
    val toolCallId: String? = null,
)
