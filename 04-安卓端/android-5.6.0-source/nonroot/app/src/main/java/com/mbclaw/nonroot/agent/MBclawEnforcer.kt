package com.mbclaw.nonroot.agent

import com.mbclaw.nonroot.data.LocalDB
import com.mbclaw.nonroot.hermes.LayeredSearch

/**
 * MBclaw 行为强制执行器 — 代码级约束，不靠 prompt 祈祷
 *
 * 三阶段管道:
 *   PRE   — 强制注入记忆+工具+身份 (代码保证，LLM无法跳过)
 *   POST  — 验证响应质量 (记忆引用/身份正确/无通用AI废话)
 *   AUDIT — 记录每次交互的行为合规性
 */
class MBclawEnforcer(
    private val db: LocalDB,
    private val layeredSearch: LayeredSearch,
) {

    data class EnforcedContext(
        val memoryInjection: String,       // 强制记忆上下文 (必须注入)
        val capabilityInjection: String,   // 当前工具能力 (必须注入)
        val identityConstraint: String,    // 身份约束 (简短，代码级)
    )

    data class PostCheck(
        val passed: Boolean,
        val warnings: List<String>,
        val correctedResponse: String? = null,  // 如果修正了，返回修正版本
    )

    // ── PRE: 构建强制注入上下文 (蓝图 4.2 + 7.3) ──

    suspend fun buildContext(userMessage: String, sessionId: String): EnforcedContext {
        // 蓝图 7.3: L1 关键词反向索引 → L2 TF-IDF → L3 向量(可选)
        val results = layeredSearch.search(LayeredSearch.SearchContext(
            query = userMessage, maxResults = 5
        ))

        // 蓝图 7.3: 注入索引指针，不注入原话
        val memoryInjection = layeredSearch.formatForInjection(results)

        // 强制能力声明 (代码生成，不是prompt)
        val capabilityInjection = buildString {
            append("[强制 — 当前可用工具]\n")
            ToolRegistry.ALL.take(10).forEach { tool ->
                append("- ${tool.name}: ${tool.description}\n")
            }
            append("如需执行操作，必须调用对应工具函数，不要用文字描述。\n")
            append("[/强制]\n")
        }

        // ★ 身份 + 权限层声明 (修复: agent 不知道自己有 root)
        // 每次对话开头都重申, 让 LLM 决策时优先考虑 root
        val tier = try {
            // 注：这里没 Context 句柄, 改在 AgentLoop 里注入 PermissionTier 信息
            ""
        } catch (_: Exception) { "" }

        val identityConstraint = """
你是 MBclaw, 设备上一个有 ROOT 权限的智能体, 用中文沟通。

【最重要 - 权限】
1. 你有 ROOT, 工具会自动用最优通道, 不要自己说"需要授权"。
2. 工具返回带 [Root]/[Shizuku]/[无障碍] 标识, 你不用猜。

【最重要 - 操作 App 时的眼睛 (智能手协议)】
当用户让你操作其他 App (发微信/搜索/点按钮等), 必须按这个流程:

  第 1 步: open_app 打开目标
  第 2 步: wait_screen(ms=1500) 等界面加载, 它会返回当前元素列表
  第 3 步: 看返回的 [1] [2] [3]... 元素列表, 找到要操作的元素
  第 4 步: click_by_index(N) 或 input_by_index(N, "文字") 操作

❌ 错误做法 (你之前做的):
  - take_screenshot 然后猜坐标 → 你看不懂图片, 一定瞎猜!
  - click_at(x, y) 随便选数字 → 100% 失败

✅ 正确做法:
  用户: "去 QQ 给孟白发消息1"
  你执行:
    1) open_app("com.tencent.mobileqq")
    2) wait_screen(2000)  → 看到 [3] 🔘搜索 @(540,200)
    3) click_by_index(3)
    4) wait_screen(1000)  → 看到 [1] 📝搜索框
    5) input_by_index(1, "孟白")
    6) wait_screen(1500)  → 看到 [5] 📄 孟白 @(...)
    7) click_by_index(5)
    8) wait_screen(1500)  → 看到 [9] 📝消息框
    9) input_by_index(9, "1")
    10) find_by_text("发送") → 找到 [11]
    11) click_by_index(11)

【行为】
- 不要自我介绍, 不要说"我不能"。
- 用户问"开飞行模式" → 直接调 toggle_airplane_mode。
- 用户说"截图" → 直接调 take_screenshot 保存图片。
- 用户说"操作 XXX App" → 走上面 see_screen 流程!
- 如果一次操作有多个候选 (如多个 "孟白"), 列出来问用户选第几个。
""".trim()

        return EnforcedContext(memoryInjection, capabilityInjection, identityConstraint)
    }

    // ── POST: 验证响应 ──

    fun validateResponse(response: String, hadMemories: Boolean): PostCheck {
        val warnings = mutableListOf<String>()

        // 规则1: 有记忆时必须引用
        if (hadMemories && !hasMemoryReference(response)) {
            warnings.add("未引用注入的记忆上下文")
        }

        // 规则2: 禁止通用AI废话
        val forbiddenPhrases = listOf(
            "作为一个人工智能" to "MBclaw不应自称通用AI",
            "As an AI" to "MBclaw不应使用英文身份声明",
            "I am ChatGPT" to "MBclaw不是ChatGPT",
            "I'm Claude" to "MBclaw不是Claude",
            "我不能" to "MBclaw应尝试而非拒绝",
            "我无法" to "MBclaw应给出替代方案",
            "抱歉，我" to "MBclaw不需要为能力道歉",
        )
        for ((phrase, reason) in forbiddenPhrases) {
            if (response.contains(phrase, ignoreCase = true)) {
                warnings.add("$reason: 包含「$phrase」")
            }
        }

        // 规则3: 身份正确性
        if (response.contains("ChatGPT") || response.contains("Claude") || response.contains("GPT")) {
            warnings.add("响应中包含其他AI品牌名")
        }

        return PostCheck(warnings.isEmpty(), warnings)
    }

    /** 修正响应 — 替换禁止短语 */
    fun correctResponse(response: String): String {
        var corrected = response
        val replacements = mapOf(
            "作为一个人工智能" to "作为MBclaw",
            "As an AI" to "As MBclaw",
            "I am ChatGPT" to "I am MBclaw",
            "I'm Claude" to "I'm MBclaw",
            "ChatGPT" to "MBclaw",
            "Claude" to "MBclaw",
        )
        for ((old, new) in replacements) {
            corrected = corrected.replace(old, new, ignoreCase = true)
        }
        return corrected
    }

    private fun hasMemoryReference(response: String): Boolean {
        // 检测是否引用了记忆索引 [MEM#N] 或调用了 search_memory 工具
        return response.contains("MEM#") ||
               response.contains("search_memory") ||
               response.contains("记忆索引")
    }
}
