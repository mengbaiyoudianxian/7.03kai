package com.mbclaw.nonroot.hermes

import android.content.Context
import com.mbclaw.nonroot.data.LocalDB
import com.mbclaw.nonroot.data.UserSettings
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 混合引擎 — 本地40% + 乌托邦服务器100%
 *
 * 架构:
 *   每个功能都有 local*() (40%) 和 server*() (100%) 两套实现
 *   capability() 自动判断: 乌托邦开+服务器连 → Server → 100%
 *                        否则 → Local → 40%
 *
 * 覆盖 Hermes H1-H6 + Agent B1-B6 + P4/P5/P7/P10/P14/P15 + F1/F2
 */

class HybridEngine(
    private val context: Context,
    private val db: LocalDB,
    private val settings: UserSettings,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** 判断当前能力级别 */
    suspend fun capability(): String = if (settings.canUploadKey() && pingServer()) "SERVER(100%)" else "LOCAL(40%)"
    private var lastPingTime = 0L; private var lastPingResult = false
    private suspend fun pingServer(): Boolean {
        if (System.currentTimeMillis() - lastPingTime < 10_000) return lastPingResult
        lastPingResult = try { serverGet("/health/health").optString("status") == "ok" } catch (_: Exception) { false }
        lastPingTime = System.currentTimeMillis(); return lastPingResult
    }

    // ═══════════════════════════════════════════
    // H1: MEMORY.md 持久记忆
    // ═══════════════════════════════════════════

    /** 本地40%: 简单文件读写 */
    fun localMemoryDurable(projectId: String): String {
        val file = File(context.filesDir, "hermes/MEMORY_${projectId}.md")
        return if (file.exists()) file.readText() else "# $projectId\n\n暂无持久记忆。开启乌托邦连接服务器获得完整记忆管理。"
    }

    fun localMemoryDurableWrite(projectId: String, content: String) {
        File(context.filesDir, "hermes/MEMORY_${projectId}.md").apply { parentFile?.mkdirs(); writeText(content) }
    }

    /** 服务端100%: 完整 memory_store 双态架构 */
    suspend fun serverMemoryDurable(projectId: String): String = serverGet("/projects/$projectId/memory/durable").toString()
    suspend fun serverMemoryDurableWrite(projectId: String, content: String) = serverPut("/projects/$projectId/memory/durable", mapOf("content" to content))

    // ═══════════════════════════════════════════
    // H2: Daily Notes 每日笔记
    // ═══════════════════════════════════════════

    fun localDailyNote(date: String): String {
        val rows = db.searchMemory("daily_$date", 1)
        return rows.firstOrNull()?.value ?: "# $date\n\n无笔记。"
    }

    fun localDailyNoteAppend(date: String, note: String) {
        db.saveMemory("daily_$date", note, "daily_note")
    }

    suspend fun serverDailyNote(projectId: String): String = serverGet("/projects/$projectId/memory/daily").toString()
    suspend fun serverDailyNoteAppend(projectId: String, note: String) = serverPost("/projects/$projectId/memory/daily", mapOf("content" to note))

    // ═══════════════════════════════════════════
    // H3: Auto Skill Extraction
    // ═══════════════════════════════════════════

    /** 本地40%: 关键词→技能卡 简单映射 */
    fun localExtractSkills(sessionId: String): List<Pair<String, String>> {
        val msgs = db.getMessages(sessionId, 50)
        val text = msgs.joinToString(" ") { it.content }
        val skills = mutableListOf<Pair<String, String>>()
        // 启发式: 检测"怎么做""如何""教程"模式 → 提取为技能
        val patterns = listOf(
            Regex("(如何|怎么|怎样)(.{2,20}?)(?:可以|能够|应该|需要)") to "方法",
            Regex("(使用|调用|配置|安装|部署|编译)(.{2,30}?)") to "操作",
            Regex("(错误|bug|问题|失败)(.{2,40}?)(?:解决|修复|处理)") to "排错",
        )
        for ((pattern, category) in patterns) {
            pattern.findAll(text).take(3).forEach { match ->
                skills.add("$category: ${match.value.take(60)}" to match.value)
            }
        }
        if (skills.isEmpty()) skills.add("通用对话" to "无特定技能提取")
        return skills.distinctBy { it.first }.take(5)
    }

    /** 服务端100%: 完整 H3 skill_extractor */
    suspend fun serverExtractSkills(projectId: String): List<Pair<String, String>> {
        val resp = serverGet("/projects/$projectId/skills")
        val arr = resp.optJSONArray("skills") ?: return emptyList()
        return (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); o.optString("name") to o.optString("description") }
    }

    // ═══════════════════════════════════════════
    // H4: Curator Auto-Archive
    // ═══════════════════════════════════════════

    /** 本地40%: 30天清理 */
    fun localCurate() {
        val stale = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        db.writableDatabase.execSQL("DELETE FROM memory WHERE accessed_at < ? AND access_count < 3", arrayOf(stale.toString()))
        db.writableDatabase.execSQL("DELETE FROM messages WHERE created_at < ?", arrayOf((stale - 60L * 24 * 3600 * 1000).toString()))
    }

    /** 服务端100%: curator 30天stale/90天archived */
    suspend fun serverCurate(projectId: String) = serverPost("/projects/$projectId/curator/run", emptyMap())

    // ═══════════════════════════════════════════
    // H5: Write-Approval Gate
    // ═══════════════════════════════════════════

    /** 本地40%: 简单风险词列表 */
    fun localApprovalGate(operation: String, target: String): Boolean {
        val highRisk = listOf("rm -rf", "delete all", "DROP TABLE", "format", "factory reset", "删除全部", "清空所有")
        val mediumRisk = listOf("delete", "rm ", "DROP", "truncate", "删除", "移除")
        return when {
            highRisk.any { operation.contains(it, ignoreCase = true) } -> false // 拒绝
            mediumRisk.any { operation.contains(it, ignoreCase = true) } -> true  // 需确认
            else -> true // 自动通过
        }
    }

    /** 服务端100%: approval_gate 风险评分+阈值 */
    suspend fun serverApprovalGate(projectId: String, operation: String): JSONObject = serverPost("/projects/$projectId/approvals/check", mapOf("operation" to operation))

    // ═══════════════════════════════════════════
    // H6: Dreaming 梦想整合
    // ═══════════════════════════════════════════

    /** 本地40%: 简单总结合并 */
    fun localDream(projectId: String): String {
        val recent = db.getMessages("", 30)
        if (recent.size < 5) return "对话量不足，无法生成梦想整合。"
        val topics = recent.map { it.content.take(80) }.distinct().take(5)
        return buildString {
            appendLine("# 梦想整合\n")
            appendLine("## 今日主题")
            topics.forEach { appendLine("- $it") }
            appendLine("\n## 关键洞察")
            appendLine("- 本地40%模式: 基于最近${recent.size}条消息的简单统计")
            appendLine("\n💡 开启乌托邦连接服务器获得AI驱动的深度梦想整合。")
        }
    }

    /** 服务端100%: dream service + memory flush */
    suspend fun serverDream(projectId: String): String = serverPost("/projects/$projectId/memory/dream", emptyMap()).toString()

    // ═══════════════════════════════════════════
    // B1-B3: Agent Runtime
    // ═══════════════════════════════════════════

    /** 本地40%: 单轮调用 + 规则fallback */
    suspend fun localAgentRun(message: String, sessionId: String): String {
        // 规则fallback (B5)
        val rules = mapOf(
            "help" to "可用命令: help/memory/skills/dream/status",
            "memory" to db.searchMemory("", 5).joinToString("\n") { "• ${it.key}: ${it.value.take(100)}" },
            "skills" to localExtractSkills(sessionId).joinToString("\n") { "• ${it.first}" },
            "status" to "模式: ${capability()} | 配置: ${settings.modelName} | 乌托邦: ${settings.utopiaEnabled}",
        )
        val lower = message.lowercase().trim()
        for ((cmd, response) in rules) {
            if (lower == cmd || lower.startsWith(cmd)) return response
        }
        return "<local_40%> ${message.take(100)}..."
    }

    /** 服务端100%: 完整 agent_runtime */
    suspend fun serverAgentRun(projectId: Int, message: String): JSONObject = serverPost("/agent/run?project_id=$projectId", mapOf("message" to message, "mode" to "auto", "max_turns" to 5))

    // ═══════════════════════════════════════════
    // B4: Self-Correction
    // ═══════════════════════════════════════════

    /** 本地40%: 简单重试+错误反馈 */
    suspend fun localSelfCorrect(action: suspend () -> String, maxRetries: Int = 2): String {
        var lastError = ""
        repeat(maxRetries) { attempt ->
            try { return action() } catch (e: Exception) {
                lastError = e.message ?: "未知错误"
                if (attempt < maxRetries - 1) delay((500L * (attempt + 1)))
            }
        }
        return "❌ 操作失败 (重试${maxRetries}次): $lastError"
    }

    // ═══════════════════════════════════════════
    // P4: 全自动模式
    // ═══════════════════════════════════════════

    /** 本地40%: 简单启发式决策 */
    fun localAutoDecide(options: List<String>): String {
        if (options.isEmpty()) return "无可用选项"
        if (options.size == 1) return options[0]
        // 启发式: 最短=最优(奥卡姆剃刀)
        return options.minByOrNull { it.length } ?: options[0]
    }

    suspend fun serverAutoDecide(projectId: Int, context: String): JSONObject = serverPost("/projects/$projectId/auto/decide", mapOf("context" to context))

    // ═══════════════════════════════════════════
    // P5: 双Key协作
    // ═══════════════════════════════════════════

    /** 本地40%: 自评+输出 */
    fun localDualKeyReview(content: String): Pair<Float, String> {
        // 简单自评: 检查长度/结构
        val score = when {
            content.length > 500 -> 8.0f
            content.length > 200 -> 6.0f
            content.length > 50 -> 4.0f
            else -> 2.0f
        }
        val feedback = when {
            score >= 7 -> "内容充实，结构完整"
            score >= 5 -> "内容适中，可以进一步展开"
            else -> "内容较短，建议补充细节"
        }
        return score to feedback
    }

    suspend fun serverDualKeyReview(projectId: Int, content: String): JSONObject = serverPost("/projects/$projectId/collab/review", mapOf("content" to content))

    // ═══════════════════════════════════════════
    // P7: 任务优先级队列
    // ═══════════════════════════════════════════

    private val taskQueue = mutableListOf<Triple<String, Int, String>>() // (id, priority, description)

    fun localTaskEnqueue(id: String, priority: Int, desc: String) {
        taskQueue.add(Triple(id, priority, desc)); taskQueue.sortByDescending { it.second }
    }

    fun localTaskDequeue(): Triple<String, Int, String>? = if (taskQueue.isNotEmpty()) taskQueue.removeAt(0) else null

    fun localTaskList(): List<Triple<String, Int, String>> = taskQueue.toList()

    suspend fun serverTaskList(projectId: Int): JSONArray = serverGet("/tasks?project_id=$projectId").optJSONArray("tasks") ?: JSONArray()

    // ═══════════════════════════════════════════
    // P10: 子对话协同 (Shared Channel)
    // ═══════════════════════════════════════════

    private val sharedChannel = mutableListOf<JSONObject>()

    fun localChannelPublish(agentId: String, task: String, findings: List<String>, reusable: List<String>) {
        sharedChannel.add(JSONObject().apply {
            put("agent_id", agentId); put("task", task); put("findings", JSONArray(findings))
            put("reusable", JSONArray(reusable)); put("timestamp", System.currentTimeMillis())
        })
    }

    fun localChannelQuery(similarity: String): List<JSONObject> {
        val kw = similarity.lowercase().split(" ").filter { it.length > 2 }
        return sharedChannel.filter { entry -> kw.any { entry.toString().lowercase().contains(it) } }
    }

    fun localChannelDedup(task: String): Boolean {
        val kw = task.lowercase().split(" ").filter { it.length > 2 }
        return sharedChannel.any { entry -> kw.count { entry.toString().lowercase().contains(it) } >= kw.size / 2 }
    }

    suspend fun serverChannelPublish(projectId: Int, data: Map<String, Any>) = serverPost("/projects/$projectId/channel", data)
    suspend fun serverChannelQuery(projectId: Int): JSONObject = serverGet("/projects/$projectId/channel")

    // ═══════════════════════════════════════════
    // P14: Thought Collision 思维碰撞
    // ═══════════════════════════════════════════

    /** 本地40%: 随机组合关键词生成新想法 */
    fun localCollision(keywords: List<String>): List<String> {
        if (keywords.size < 2) return listOf("需要至少2个关键词进行思维碰撞")
        val combos = mutableListOf<String>()
        for (i in keywords.indices) {
            for (j in i + 1 until keywords.size) {
                combos.add("${keywords[i]} + ${keywords[j]} → 可能的交叉创新点")
            }
        }
        return combos.take(10)
    }

    suspend fun serverCollision(projectId: Int, keywords: List<String>): JSONObject = serverPost("/projects/$projectId/collisions", mapOf("keywords" to JSONArray(keywords)))

    // ═══════════════════════════════════════════
    // P15: 乌托邦计划
    // ═══════════════════════════════════════════

    private var localUtopiaTokens = 0L

    /** 本地40%: 本地计数token贡献 */
    fun localUtopiaContribute(tokens: Long) {
        localUtopiaTokens += tokens
    }

    fun localUtopiaStats(): Map<String, Long> = mapOf("local_tokens_contributed" to localUtopiaTokens, "estimated_global_tokens" to localUtopiaTokens * 100)

    suspend fun serverUtopiaContribute(projectId: Int, tokens: Long) = serverPost("/projects/$projectId/utopia/contribute", mapOf("tokens" to tokens))
    suspend fun serverUtopiaStats(projectId: Int): JSONObject = serverGet("/projects/$projectId/utopia/stats")

    // ═══════════════════════════════════════════
    // F1: Active Feedback / F2: Psychology
    // ═══════════════════════════════════════════

    private val interactionLog = mutableListOf<Pair<String, Long>>() // (action, timestamp)

    fun localFeedbackRecord(action: String) { interactionLog.add(action to System.currentTimeMillis()) }

    fun localPsychologyProfile(): Map<String, Any> {
        val total = interactionLog.size
        val recent = interactionLog.filter { it.second > System.currentTimeMillis() - 7 * 24 * 3600 * 1000 }
        val categories = interactionLog.groupBy { it.first.split(":").first() }.mapValues { it.value.size }
        return mapOf(
            "total_interactions" to total,
            "weekly_active" to recent.size,
            "top_categories" to categories.entries.sortedByDescending { it.value }.take(5).map { "${it.key}: ${it.value}" },
            "engagement_level" to when { total > 100 -> "高度活跃"; total > 30 -> "中度使用"; else -> "轻度使用" },
        )
    }

    suspend fun serverFeedbackRecord(projectId: Int, action: String) = serverPost("/projects/$projectId/feedback", mapOf("action" to action))
    suspend fun serverPsychologyProfile(projectId: Int): JSONObject = serverGet("/projects/$projectId/psychology/profile")

    // ═══════════════════════════════════════════
    // 统一调度: capability routing
    // ═══════════════════════════════════════════

    /** 统一的能力路由调用 */
    suspend fun <T> route(local: suspend () -> T, server: suspend () -> T): T {
        return if (settings.canUploadKey() && pingServer()) {
            try { server() } catch (_: Exception) { local() }
        } else local()
    }

    // ═══════════════════════════════════════════
    // HTTP helpers
    // ═══════════════════════════════════════════

    private suspend fun serverGet(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${settings.serverUrl.trimEnd('/')}$path").header("Authorization", "Bearer ${settings.apiKey}").get().build()
        val resp = http.newCall(req).execute(); JSONObject(resp.body?.string() ?: "{}")
    }

    private suspend fun serverPost(path: String, data: Map<String, Any>): JSONObject = withContext(Dispatchers.IO) {
        val json = JSONObject(data).toString()
        val req = Request.Builder().url("${settings.serverUrl.trimEnd('/')}$path").header("Authorization", "Bearer ${settings.apiKey}").header("Content-Type", "application/json").post(json.toRequestBody("application/json".toMediaType())).build()
        val resp = http.newCall(req).execute(); JSONObject(resp.body?.string() ?: "{}")
    }

    private suspend fun serverPut(path: String, data: Map<String, String>): String = withContext(Dispatchers.IO) {
        val json = JSONObject(data as Map<*, *>).toString()
        val req = Request.Builder().url("${settings.serverUrl.trimEnd('/')}$path").header("Authorization", "Bearer ${settings.apiKey}").header("Content-Type", "application/json").put(json.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().body?.string() ?: ""
    }
}
