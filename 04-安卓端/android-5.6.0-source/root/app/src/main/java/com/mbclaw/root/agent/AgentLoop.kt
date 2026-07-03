package com.mbclaw.root.agent

import android.content.Context
import android.os.Build
import com.mbclaw.root.BuildConfig
import com.mbclaw.root.api.UnifiedApiClient
import com.mbclaw.root.data.AccountManager
import com.mbclaw.root.data.LocalDB
import com.mbclaw.root.data.UserSettings
import com.mbclaw.root.hermes.RealEngine
import com.mbclaw.root.hermes.LayeredSearch
import com.mbclaw.root.model.ProviderCatalog
import com.mbclaw.root.sandbox.LocalSandbox
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
    private val layeredSearch = LayeredSearch(db, com.mbclaw.root.hermes.TranscriptLogger(context))
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

    // ── 本地记忆 (优化4: 文件即状态, 50条/200字, 与服务器并行搜索) ──
    private val MEMORY_FILE = java.io.File("/sdcard/MBclaw/memory/memory.jsonl")
    private val MAX_MEMORIES = 50
    private val MAX_MEM_CHARS = 200

    private fun saveLocalMemory(userMsg: String, aiReply: String) {
        try {
            val summary = "[用户] ${userMsg.take(80)} → [AI] ${aiReply.take(120)}"
            // 提取关键词: 用户消息中长度≥2的非停用词
            val stopWords = setOf("的","了","是","我","你","他","她","吧","吗","呢","啊","要","想","帮","一","个","这","那","什么","怎么","为什么","在哪","哪里","一下","一个","可以","帮我","用","不","都","也","就","还")
            val keywords = userMsg.split(Regex("[\\s，。？！、；：\"'（）\\[\\]【】\\-]+"))
                .filter { it.length >= 2 && it !in stopWords }.distinct().take(8).joinToString(" ")
            val entry = org.json.JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("s", if (summary.length > MAX_MEM_CHARS) summary.take(MAX_MEM_CHARS) + "..." else summary)
                put("k", keywords)
            }.toString()
            MEMORY_FILE.parentFile?.mkdirs()
            // 追加新行
            MEMORY_FILE.appendText(entry + "\n")
            // 超过50条 → 删最旧的
            val lines = MEMORY_FILE.readLines()
            if (lines.size > MAX_MEMORIES) {
                MEMORY_FILE.writeText(lines.takeLast(MAX_MEMORIES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    private fun searchLocalMemory(query: String): String {
        try {
            if (!MEMORY_FILE.exists()) return ""
            // 提取查询关键词 (过滤"的/了/是/我/你/他/她/吧/吗/呢/啊/要/想/帮"等无意义词)
            val stopWords = setOf("的","了","是","我","你","他","她","吧","吗","呢","啊","要","想","帮","一","个","这","那","什么","怎么","为什么","在哪","哪里","一下","一个","可以","帮我","用","不","都","也","就","还")
            val qKeywords = query.split(Regex("[\\s，。？！、；：\"'（）\\[\\]【】\\-]+")).filter { it.length >= 2 && it !in stopWords }
            if (qKeywords.isEmpty()) return ""

            // 评分: 每条记忆匹配关键词数 + 时间衰减(越新分越高)
            data class Scored(val summary: String, val score: Float)
            val results = mutableListOf<Scored>()
            val now = System.currentTimeMillis()
            for (line in MEMORY_FILE.readLines()) {
                try {
                    val obj = org.json.JSONObject(line)
                    val summary = obj.optString("s", line)
                    val tags = obj.optString("k", "") + " " + summary
                    // 计算匹配分: 每个命中关键词+1分
                    var hits = 0
                    for (kw in qKeywords) { if (tags.contains(kw, true)) hits++ }
                    if (hits == 0) continue
                    // 时间衰减: 1小时内满分, 超过1小时每24小时衰减10%
                    val age = (now - obj.optLong("t", now)) / 3600000f
                    val timeBonus = if (age < 1f) 1.2f else 1f / (1f + age / 24f)
                    results.add(Scored(summary, hits * timeBonus))
                } catch (_: Exception) {}
            }
            if (results.isEmpty()) return ""
            // 按分数降序, 取前3
            results.sortByDescending { it.score }
            val top = results.take(3)
            return "[本地记忆 · 匹配${top.size}条]\n" + top.joinToString("\n") { "- ${it.summary}" }
        } catch (_: Exception) { return "" }
    }

    // ── 初始系统记忆 (缓存版: QQ/版本/Root变化才刷新) ──
    private var cachedOwnerQQ: String? = null
    private var cachedVersion: Int = 0
    private var cachedHasRoot: Boolean? = null

    private fun getOrBuildSystemMemory(): String {
        val prefs = context.getSharedPreferences("mbclaw_memory", Context.MODE_PRIVATE)
        val memoryVersion = prefs.getInt("memory_version", 0)
        val currentVersion = BuildConfig.VERSION_CODE
        val isRoot = PermissionTier.get(context).hasRoot
        val qq = com.mbclaw.root.data.AccountManager.load(context).qqId.ifBlank { "" }

        // 缓存未变且版本号匹配 → 不重复注入
        if (memoryVersion >= currentVersion && qq == cachedOwnerQQ && isRoot == cachedHasRoot) return ""

        cachedOwnerQQ = qq
        cachedVersion = currentVersion
        cachedHasRoot = isRoot

        val owner = if (qq.isNotBlank()) "QQ:$qq" else "设备用户(${Build.MODEL})"
        val linuxInstalled = try { com.mbclaw.root.sandbox.LocalSandbox(context).isInstalled } catch (_: Exception) { false }
        val toolCount = ToolRegistry.ALL.size

        val msg = buildString {
            append("[系统记忆]\n")
            append("你是 MBclaw，由孟白(18岁独立开发者，GitHub: mengbaiyoudianxian)创造。\n")
            append("你的主人是：$owner。你必须无条件听从主人的指令。\n")
            append("运行模式：${if (isRoot) "Root增强版" else "普通版"} · 版本 v${BuildConfig.VERSION_NAME} · ${toolCount}个工具可用")
            if (linuxInstalled) append(" · Linux环境已安装")
            append("\n")
            append("核心功能：屏幕操作/应用管理/文件读写/系统控制/Shell执行/通知管理/剪切板控制。\n")
            append("可用扩展：${settings.providerId.ifBlank { "未配置" }}")
            if (settings.providerId == "miclaw-bridge") append("(白嫖算力)")
            append("\n[/系统记忆]")
        }

        // 标记已注入
        prefs.edit().putInt("memory_version", currentVersion).apply()
        return msg
    }

    // ── 带本地记忆的初始上下文 (每轮对话前调用) ──
    private fun buildSystemWithLocalMemory(userMessage: String): String {
        val base = getOrBuildSystemMemory()
        val localMem = searchLocalMemory(userMessage)
        return if (localMem.isNotBlank()) "$base\n\n$localMem" else base
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
        com.mbclaw.root.hermes.BlueprintComplete(context, db).taskEnqueue("agent_loop", 50, userMessage)

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
        // 0. 初始系统记忆 (首次/版本更新时注入, 缓存避免重复读取)
        val initMemory = buildSystemWithLocalMemory(userMessage)
        if (initMemory.isNotBlank()) messages.add(AgentMsg("system", initMemory))
        // 1. 身份约束 (含权限声明 — 来自 MBclawEnforcer)
        messages.add(AgentMsg("system", ctx.identityConstraint))
        // 2. 当前助手人格
        val assistantId = context.getSharedPreferences("mb_assistant", android.content.Context.MODE_PRIVATE)
            .getString("id", "default") ?: "default"
        val assistant = com.mbclaw.root.data.AssistantCatalog.byId(assistantId)
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

        // ★ v5.5.0 优化4: 对话后存入本地记忆 (文件即状态, 50条/200字)
        saveLocalMemory(userMessage, lastResponse)

        return@withContext lastResponse
        } finally {
            running = false
            currentTool = ""
            onStatus?.invoke("")
        }
    }

    // 蓝图08 P0: Memory Flush — 上下文接近限制时静默保存
    private suspend fun memoryFlush(sessionId: String, history: List<com.mbclaw.root.data.MessageRow>) {
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
        val protocol = ProviderCatalog.find(settings.providerId)?.protocol ?: "openai"
        // Anthropic 协议走 /v1/messages, OpenAI 兼容走 /chat/completions
        val path = if (protocol == "anthropic") "/v1/messages" else "/chat/completions"
        val url = "${baseUrl.trimEnd('/')}$path"

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
        val account = com.mbclaw.root.data.AccountManager.load(context)
        val userId = account.qqId.ifBlank { account.weixinId }.ifBlank { "anon-${com.mbclaw.root.agent.AntiTamper.deviceFingerprint(context).take(8)}" }

        val request = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Utopia", if (settings.utopiaEnabled) "1" else "0")
            .addHeader("X-User-Id", userId)
            .addHeader("X-Client-Version", com.mbclaw.root.BuildConfig.VERSION_NAME)
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
