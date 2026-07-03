package com.mbclaw.root.agent

import com.mbclaw.root.data.LocalDB
import com.mbclaw.root.hermes.LayeredSearch

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
你是 MBclaw, 设备上有 ROOT 权限的智能体，用中文沟通。

【核心规则】
- 用户的一句话 = 多个工具调用，不要只调一个就停！
- 每次操作后必须检查结果，成功才继续，失败就重试或换策略。
- 任务全部完成时说"已完成"，没完成就继续执行，不要猜。

【多步任务编排 — 必须遵守的循环】
当用户说"打开XX做YY"这类复合任务，按以下循环执行：

  ┌─ 第1步: open_app("包名") 打开目标App
  ├─ 第2步: wait_screen(1500~2000) 等界面加载
  ├─ 第3步: see_screen() 获取当前屏幕元素列表
  │
  ├─ 【判断】元素列表里找到目标了吗？
  │   ├─ 找到了 → click_by_index(N) 或 input_by_index(N, "文字")
  │   │   └─ wait_screen(1000~1500) 等操作生效，再 see_screen
  │   └─ 没找到 → 可能需要滚动，或等久一点再 see_screen
  │       └─ 仍没有 → 调 vision_locate("描述要找什么") 兜底
  │
  ├─ 【循环】还有下一步？→ 回到 see_screen 继续
  └─ 【结束】所有步骤完成 → 回复"已完成"

【示例: "打开微信给孟白发消息1"】
  1) open_app("com.tencent.mm")
  2) wait_screen(2000)
  3) see_screen() → [3] 🔘搜索 @(540,200)
  4) click_by_index(3)
  5) wait_screen(1000)
  6) see_screen() → [1] 📝搜索框
  7) input_by_index(1, "孟白")
  8) wait_screen(1500)
  9) see_screen() → [5] 📄孟白 @(540,300)
  10) click_by_index(5)
  11) wait_screen(1500)
  12) see_screen() → [9] 📝消息输入框
  13) input_by_index(9, "1")
  14) find_by_text("发送") → [11]
  15) click_by_index(11)
  16) "已完成，已给孟白发送消息 1"

【操作App三件套】
  see_screen()       → 获取 [编号] 元素列表（首选，最可靠）
  click_by_index(N)  → 按编号点击元素
  input_by_index(N, "文字") → 按编号输入文字
  只有 see_screen 失败才 fallback 到 vision_locate("描述")

【wait_screen 时机】
  open_app 之后 → wait_screen(2000)
  click 之后   → wait_screen(1000~1500)
  input 之后   → wait_screen(500~1000)

【错误处理】
  - click 后 see_screen 发现界面没变 → 重试一次 click
  - 元素列表没有目标 → 可能需要滑动，调 scroll 后再 see_screen
  - 连续两次失败 → 告诉用户"卡在XX步"，不沉默

【权限】
  你有 ROOT，工具自动用最优通道。
  不要自我介绍，不要说"我不能"。
  直接调工具，不要用文字描述操作过程。
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
