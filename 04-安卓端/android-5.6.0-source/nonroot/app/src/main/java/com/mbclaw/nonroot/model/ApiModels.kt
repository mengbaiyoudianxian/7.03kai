package com.mbclaw.nonroot.model

/**
 * MBclaw API 数据模型
 */

// ── Agent 聊天 ──

data class AgentRequest(
    val message: String,
    val user_id: Int = 1,
    val session_id: Int = 0,
    val max_turns: Int = 5,
    val mode: String = "auto",
)

data class AgentResponse(
    val result: String? = null,
    val response: String? = null,
    val status: String? = null,
    val error: String? = null,
)

// ── 记忆搜索 ──

data class SearchRequest(
    val query: String,
)

data class SearchResult(
    val id: Int? = null,
    val title: String? = null,
    val content: String? = null,
    val score: Float? = null,
    val source: String? = null,
)

// ── 健康检查 ──

data class HealthResponse(
    val status: String? = null,
    val version: String? = null,
    val uptime: Double? = null,
)

// ── 通用错误 ──

data class ApiError(
    val detail: String? = null,
    val message: String? = null,
)


// ── R0-ext 工具系统模型 ──

data class ToolInfo(
    val id: Int = 0,
    val name: String = "",
    val category: String = "",
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val usage_count: Int = 0
)

data class ToolExecuteRequest(
    val name: String,
    val content: String = ""
)

data class ToolExecuteResponse(
    val name: String = "",
    val result: String = ""
)
