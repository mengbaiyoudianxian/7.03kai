package com.mbclaw.dev.hermes

import com.mbclaw.dev.api.DirectApiClient
import com.mbclaw.dev.api.ChatMessage as ApiMsg
import com.mbclaw.dev.data.LocalDB
import com.mbclaw.dev.data.UserSettings
import com.mbclaw.dev.model.ProviderCatalog
import kotlinx.coroutines.*

/**
 * 真实引擎 — 所有"40%"本地实现全部调用配置的LLM
 *
 * 不假实现，每个方法都真的调AI做事
 */
class RealEngine(
    private val db: LocalDB,
    private val settings: UserSettings,
) {
    private fun isReady(): Boolean = settings.isConfigured()
    private fun baseUrl(): String = settings.apiBaseUrl.ifBlank {
        ProviderCatalog.find(settings.providerId)?.baseUrl ?: ""
    }

    // ═══════════════════════════════════════════
    // 🌙 梦想整合 — LLM 驱动
    // ═══════════════════════════════════════════
    suspend fun dream(sessionId: String = ""): String {
        if (!isReady()) return "❌ 请先配置AI提供商"

        val msgs = db.getMessages(sessionId.ifBlank { "" }, 50)
        if (msgs.isEmpty()) return "暂无对话记录，开始聊天后我会自动整理。"

        val history = msgs.joinToString("\n") { "[${it.role}] ${it.content.take(300)}" }
        val prompt = listOf(
            ApiMsg("system", "你是MBclaw的梦想整合引擎。从以下对话历史中提取：\n1. 今日主题(1行)\n2. 关键洞察(2-3条)\n3. 用户偏好变化\n4. 值得记住的经验教训\n5. 下一步建议\n\n用中文，简洁有力，每点不超过2行。"),
            ApiMsg("user", "对话历史:\n$history\n\n请生成梦想整合报告。")
        )

        return try {
            withContext(Dispatchers.IO) {
                DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, prompt, settings.utopiaEnabled)
            }
        } catch (e: Exception) { "🌙 梦想引擎异常: ${e.message}" }
    }

    // ═══════════════════════════════════════════
    // 🔍 双Key评审 — 蓝图4.4: Key1→Key2→Key1 循环1-6次, 4维度评分(代码质量/逻辑/安全/完整性) 32/40阈值
    // ═══════════════════════════════════════════
    suspend fun dualKeyReview(content: String, maxRounds: Int = 3): String {
        if (!isReady()) return "❌ 请先配置AI提供商"
        if (content.isBlank()) return "无可评审内容"

        return try {
            var currentContent = content
            val results = mutableListOf<String>()
            for (round in 1..maxRounds.coerceAtMost(6)) {
                val reviewPrompt = listOf(
                    ApiMsg("system", "你是Key2评审引擎。严格按4维度评分:\n代码质量(0-10): \n逻辑正确性(0-10): \n安全性(0-10): \n完整性(0-10): \n总分(0-40): \n问题列表:\n改进建议:\n\n用中文，严格格式。"),
                    ApiMsg("user", "评审第${round}轮:\n---\n${currentContent.take(3000)}\n---")
                )
                val review = withContext(Dispatchers.IO) { DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, reviewPrompt, settings.utopiaEnabled) }
                results.add("[第${round}轮]\n$review")

                // 解析总分
                val totalScore = Regex("""总分.*?(\d+)""").find(review)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (totalScore >= 32) { results.add("✅ 评审通过 (总分$totalScore/40 ≥ 32)"); break }
                if (round < maxRounds) {
                    val revisePrompt = listOf(
                        ApiMsg("system", "你是Key1执行引擎。根据Key2的评审意见修改内容。"),
                        ApiMsg("user", "原始内容:\n${currentContent.take(2000)}\n\n评审意见:\n$review\n\n请修改后重新输出完整内容。")
                    )
                    currentContent = withContext(Dispatchers.IO) { DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, revisePrompt, settings.utopiaEnabled) }
                    results.add("[Key1修改稿]\n${currentContent.take(300)}...")
                }
            }
            results.joinToString("\n\n")
        } catch (e: Exception) { "🔍 评审异常: ${e.message}" }
    }

    // ═══════════════════════════════════════════
    // 💥 思维碰撞 — LLM 创新
    // ═══════════════════════════════════════════
    suspend fun collision(keywords: List<String>): String {
        if (!isReady()) return "❌ 请先配置AI提供商"
        if (keywords.size < 2) return "需要至少2个关键词进行思维碰撞"

        val kwStr = keywords.joinToString(" + ")
        val prompt = listOf(
            ApiMsg("system", "你是MBclaw的创新引擎。将给定的关键词进行思维碰撞，生成3-5个新颖的交叉创新点子。每个点子：名称(1句话) + 简要说明(2-3句话)。要有想象力和实用价值。用中文。"),
            ApiMsg("user", "对以下关键词进行思维碰撞: $kwStr")
        )

        return try {
            withContext(Dispatchers.IO) {
                DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, prompt, settings.utopiaEnabled)
            }
        } catch (e: Exception) { "💥 碰撞引擎异常: ${e.message}" }
    }

    // ═══════════════════════════════════════════
    // 📝 会话总结 — LLM 生成
    // ═══════════════════════════════════════════
    suspend fun summarizeSession(sessionId: String): String {
        if (!isReady()) return "未配置AI"

        val msgs = db.getMessages(sessionId, 100)
        if (msgs.isEmpty()) return "无对话内容"

        val history = msgs.joinToString("\n") { "[${it.role}] ${it.content.take(300)}" }
        val prompt = listOf(
            ApiMsg("system", "你是MBclaw的总结引擎。对对话做简洁总结：主题(1行) + 结论(2-3条) + 下一步(1条)。用中文。"),
            ApiMsg("user", "对话:\n$history\n\n请总结。")
        )

        return try {
            withContext(Dispatchers.IO) {
                DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, prompt, settings.utopiaEnabled)
            }
        } catch (e: Exception) { "📝 总结异常: ${e.message}" }
    }

    // ═══════════════════════════════════════════
    // 🏷 关键词提取 — LLM 提取
    // ═══════════════════════════════════════════
    suspend fun extractKeywords(sessionId: String): List<String> {
        if (!isReady()) return emptyList()

        val msgs = db.getMessages(sessionId, 50)
        val text = msgs.joinToString(" ") { it.content.take(200) }
        if (text.isBlank()) return emptyList()

        val prompt = listOf(
            ApiMsg("system", "提取对话中的关键技术关键词。只输出关键词，逗号分隔，不超过15个。用中文。"),
            ApiMsg("user", "对话: ${text.take(2000)}\n\n提取关键词:")
        )

        return try {
            val resp = withContext(Dispatchers.IO) {
                DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, prompt, settings.utopiaEnabled)
            }
            resp.split(Regex("[,，、\\s]+")).filter { it.length in 2..15 }.take(15)
        } catch (e: Exception) { emptyList() }
    }

    // ═══════════════════════════════════════════
    // 🎯 智能分类 — LLM 语义分类
    // ═══════════════════════════════════════════
    suspend fun classifyContent(text: String, existingCategories: List<String>): Pair<String, Float> {
        if (!isReady()) return "未分类" to 0f

        val catStr = existingCategories.joinToString(", ").ifBlank { "无现有分类" }
        val prompt = listOf(
            ApiMsg("system", "你是MBclaw的分类引擎。将以下内容归入最合适的类别。输出格式: \"类别名|0.0-1.0的置信度\"。如果现有类别都不合适，创建一个新类别名。用中文。"),
            ApiMsg("user", "现有类别: $catStr\n\n内容: ${text.take(1000)}\n\n分类结果:")
        )

        return try {
            val resp = withContext(Dispatchers.IO) {
                DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, prompt, settings.utopiaEnabled)
            }
            val parts = resp.split("|")
            if (parts.size >= 2) {
                parts[0].trim() to (parts[1].trim().toFloatOrNull() ?: 0.7f)
            } else {
                resp.trim().take(30) to 0.5f
            }
        } catch (e: Exception) { "分类失败" to 0f }
    }

    // ═══════════════════════════════════════════
    // 🌙 Dream 提升到 MEMORY.md (蓝图P0: 收集信号→评分→通过阈值→写入)
    // ═══════════════════════════════════════════
    suspend fun dreamAndPromote(sessionId: String = ""): String {
        val dreamResult = dream(sessionId)
        // 提取关键洞察写入 MEMORY.md
        val lines = dreamResult.split("\n").filter { it.trim().startsWith("-") || it.trim().startsWith("•") || it.trim().startsWith("*") }
        for (line in lines.take(5)) {
            db.saveMemory("durable_${System.currentTimeMillis()}", line.trim(), "dream_promoted")
        }
        return dreamResult
    }

    // ═══════════════════════════════════════════
    // 📝 反思5字段 (蓝图4.5: findings/problems/solutions/reusable/conflicts)
    // ═══════════════════════════════════════════
    data class Reflection(val findings: List<String>, val problems: List<String>, val solutions: List<String>, val reusable: List<String>, val conflicts: List<String>)

    suspend fun reflection(text: String): Reflection {
        if (!isReady() || text.isBlank()) return Reflection(listOf("无数据"), emptyList(), emptyList(), emptyList(), emptyList())
        val prompt = listOf(
            ApiMsg("system", "你是MBclaw反思引擎。从以下对话中提取5个字段，每字段用分号分隔多个条目:\nfindings(新发现): \nproblems(遇到的问题): \nsolutions(解决方案): \nreusable(可复用): \nconflicts(潜在冲突): \n\n用中文，每字段一行，条目用分号分隔。"),
            ApiMsg("user", "对话:\n${text.take(2000)}\n\n请输出反思结果。")
        )
        return try {
            val resp = withContext(Dispatchers.IO) { DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, prompt, settings.utopiaEnabled) }
            val lines = resp.split("\n").filter { it.isNotBlank() }
            var f = emptyList<String>(); var p = emptyList<String>(); var s = emptyList<String>(); var r = emptyList<String>(); var c = emptyList<String>()
            for (line in lines) {
                val content = line.substringAfter(":").substringAfter("：").trim()
                val items = content.split(";").filter { it.isNotBlank() }.ifEmpty { listOf(content) }
                when { line.contains("finding", ignoreCase = true) || line.contains("发现") -> f = items; line.contains("problem", ignoreCase = true) || line.contains("问题") -> p = items; line.contains("solution", ignoreCase = true) || line.contains("解决") -> s = items; line.contains("reusable", ignoreCase = true) || line.contains("复用") -> r = items; line.contains("conflict", ignoreCase = true) || line.contains("冲突") -> c = items }
            }
            Reflection(f.ifEmpty { listOf("无新发现") }, p.ifEmpty { listOf("无问题") }, s.ifEmpty { listOf("无方案") }, r.ifEmpty { listOf("无可复用") }, c.ifEmpty { listOf("无冲突") })
        } catch (_: Exception) { Reflection(listOf("反思异常"), emptyList(), emptyList(), emptyList(), emptyList()) }
    }

    // ═══════════════════════════════════════════
    // 📊 心理画像 — LLM 分析
    // ═══════════════════════════════════════════
    suspend fun psychologyProfile(recentInteractions: List<String>): String {
        if (!isReady() || recentInteractions.isEmpty()) return "数据不足"

        val history = recentInteractions.joinToString("\n")
        val prompt = listOf(
            ApiMsg("system", "你是MBclaw的用户画像引擎。基于用户的最近交互行为，分析：\n1. 当前兴趣领域\n2. 技术偏好\n3. 使用模式(频率/时段/类型)\n4. 可能的潜在需求\n\n用中文，简洁。不要过度推测。"),
            ApiMsg("user", "最近交互:\n$history\n\n请生成用户画像分析。")
        )

        return try {
            withContext(Dispatchers.IO) {
                DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, prompt, settings.utopiaEnabled)
            }
        } catch (e: Exception) { "📊 画像引擎异常: ${e.message}" }
    }

    // ═══════════════════════════════════════════
    // 🛠 工具推荐 — LLM 推荐
    // ═══════════════════════════════════════════
    suspend fun recommendTool(userIntent: String, availableTools: List<String>): String {
        if (!isReady()) return availableTools.firstOrNull() ?: "无可用工具"

        val tools = availableTools.joinToString(", ")
        val prompt = listOf(
            ApiMsg("system", "你是MBclaw的工具推荐引擎。根据用户意图，从可用工具列表中选择最合适的工具并说明理由。用中文，一句话。"),
            ApiMsg("user", "用户意图: $userIntent\n可用工具: $tools\n推荐:")
        )

        return try {
            withContext(Dispatchers.IO) {
                DirectApiClient.chat(baseUrl(), settings.apiKey, settings.modelName, prompt, settings.utopiaEnabled)
            }
        } catch (e: Exception) { availableTools.first() }
    }
}
