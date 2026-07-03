package com.mbclaw.root.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 直连 Anthropic Messages API
 *
 * 协议: POST /v1/messages
 * 认证: x-api-key (NOT Bearer)
 * 版本: anthropic-version: 2023-06-01
 * 格式: 非 OpenAI 兼容, 需独立处理
 */

// ── Request types ──

data class AnthropicMessage(
    val role: String,       // user / assistant
    val content: String,    // 纯文本 (多模态可用 content 数组)
)

data class AnthropicRequest(
    val model: String,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val system: String? = null,                       // 系统提示词 (顶层字段, 不是 message)
    val messages: List<AnthropicMessage>,
    val temperature: Double = 0.7,
    val stream: Boolean = false,
)

// ── Response types ──

data class AnthropicResponse(
    val id: String? = null,
    val type: String? = null,
    val content: List<AnthropicContentBlock>? = null,
    @SerializedName("stop_reason") val stopReason: String? = null,
    val error: AnthropicErrorDetail? = null,
)

data class AnthropicContentBlock(
    val type: String? = null,       // "text" / "tool_use"
    val text: String? = null,
)

data class AnthropicErrorDetail(
    val type: String? = null,
    val message: String? = null,
)

/**
 * Anthropic 原生协议客户端
 */
object AnthropicApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 发送 Anthropic Messages 请求
     *
     * @param baseUrl  API 地址 (如 https://api.anthropic.com 或 https://api.deepseek.com/anthropic)
     * @param apiKey   API Key
     * @param model    模型名 (如 claude-sonnet-4-6 / deepseek-v4-pro)
     * @param messages 消息历史
     * @param systemPrompt  系统提示词 (Anthropic 顶层字段)
     * @return 回复文本
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        utopiaEnabled: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/v1/messages"

        // 分离 system 消息 (Anthropic 要求 system 是顶层字段)
        val systemFromMessages = messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content }
        val effectiveSystem = systemPrompt
            ?: systemFromMessages.ifEmpty { null }

        val anthropicMessages = messages
            .filter { it.role != "system" }
            .map { AnthropicMessage(role = it.role, content = it.content) }

        val body = AnthropicRequest(
            model = model,
            messages = anthropicMessages,
            system = effectiveSystem,
        )
        val json = gson.toJson(body)

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Utopia", if (utopiaEnabled) "1" else "0")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (response.isSuccessful) {
            val completion = gson.fromJson(responseBody, AnthropicResponse::class.java)
            val text = completion.content
                ?.firstOrNull { it.type == "text" }
                ?.text
            if (text != null) {
                text
            } else {
                throw Exception("Anthropic 返回空内容: ${responseBody.take(200)}")
            }
        } else {
            val err = try {
                gson.fromJson(responseBody, AnthropicResponse::class.java)
            } catch (_: Exception) { null }
            val errMsg = err?.error?.message
                ?: "HTTP ${response.code}: ${responseBody.take(200)}"
            throw Exception(errMsg)
        }
    }
}
