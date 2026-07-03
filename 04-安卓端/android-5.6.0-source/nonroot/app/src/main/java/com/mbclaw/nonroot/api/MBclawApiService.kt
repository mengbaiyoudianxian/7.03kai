package com.mbclaw.nonroot.api

import com.mbclaw.nonroot.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * MBclaw r0/r1 服务端 API — 对接 14 端点
 */
interface MBclawApiService {

    // ── Agent ──
    @POST("/agent/run")
    suspend fun agentRun(@Body payload: AgentRunRequest): Response<AgentRunResponse>

    @GET("/agent/status")
    suspend fun agentStatus(): Response<AgentStatusResponse>

    // ── Sessions ──
    @POST("/sessions")
    suspend fun createSession(@Body payload: SessionCreateRequest): Response<SessionCreateResponse>

    @POST("/sessions/{sid}/messages")
    suspend fun addMessage(@Path("sid") sid: Int, @Body payload: MessageRequest): Response<MessageResponse>

    @POST("/sessions/{sid}/close")
    suspend fun closeSession(@Path("sid") sid: Int): Response<CloseSessionResponse>

    @GET("/sessions/{sid}/messages")
    suspend fun listMessages(@Path("sid") sid: Int): Response<List<MessageResponse>>

    // ── Search ──
    @GET("/search")
    suspend fun search(@Query("q") q: String, @Query("limit") limit: Int = 5): Response<List<SearchHit>>

    // ── Metrics ──
    @GET("/metrics")
    suspend fun getMetrics(): Response<MetricsResponse>

    // ── Health ──
    @GET("/health")
    suspend fun health(): Response<HealthResponse>

    // ── Tools ──
    @GET("/tools")
    suspend fun listTools(@Query("category") category: String? = null): Response<List<ToolInfo>>

    @POST("/tools/execute")
    suspend fun executeTool(@Body payload: ToolExecRequest): Response<ToolExecResponse>

    // ── Feedback ──
    @POST("/feedback")
    suspend fun submitFeedback(@Body payload: FeedbackRequest): Response<Map<String, Any?>>

    // ── Snapshots ──
    @POST("/snapshots")
    suspend fun createSnapshot(@Body payload: SnapshotRequest): Response<Map<String, Any?>>
}

// ── Request/Response types ──

data class AgentRunRequest(val message: String, val max_turns: Int = 5)
data class AgentRunResponse(val session_id: Int, val response: String, val tools_used: List<String>, val turns: Int, val thinking: List<String>, val messages_added: Int)
data class AgentStatusResponse(val active: Boolean, val session_id: Int?, val title: String?, val message_count: Int, val started_at: String?)
data class SessionCreateRequest(val title: String)
data class SessionCreateResponse(val session_id: Int, val title: String, val status: String, val injected_system_message: Map<String,String>?)
data class MessageRequest(val role: String, val content: String)
data class MessageResponse(val id: Int, val session_id: Int, val role: String, val content: String, val created_at: String)
data class CloseSessionResponse(val session_id: Int, val status: String, val summary: String, val keywords: List<Map<String,Any?>>, val experiences: List<Map<String,Any?>>, val category: String, val skill_extracted: Map<String,Any?>?, val stats: Map<String,Any?>)
data class SearchHit(val session_id: Int, val summary: String, val keywords: List<String>, val score: Double)
data class MetricsResponse(val sessions_created: Int, val sessions_closed: Int, val searches_total: Int, val searches_hit: Int, val search_hit_rate: Double?, val llm_successes: Int, val llm_errors: Int, val llm_error_rate: Double?)
data class HealthResponse(val db_ok: Boolean, val version: String)
data class ToolInfo(val id: Int, val name: String, val category: String, val summary: String, val tags: List<String>, val usage_count: Int)
data class ToolExecRequest(val name: String, val content: String = "")
data class ToolExecResponse(val name: String, val result: String)
data class FeedbackRequest(val session_id: Int, val rating: Int, val category: String = "general", val comment: String = "")
data class SnapshotRequest(val name: String, val description: String = "")
