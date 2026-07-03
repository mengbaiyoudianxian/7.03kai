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
 * 直连大模型 API — OpenAI 兼容格式 (OpenAI / DeepSeek / 智谱 等)
 */

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = false,
)

data class ChatMessage(
    val role: String,
    val content: String,
)

data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice>? = null,
    val error: ApiErrorDetail? = null,
)

data class Choice(
    val index: Int? = null,
    val message: MessageContent? = null,
    @SerializedName("finish_reason") val finishReason: String? = null,
)

data class MessageContent(
    val role: String? = null,
    val content: String? = null,
)

data class ApiErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

/**
 * OpenAI 兼容协议客户端
 */
object DirectApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        utopiaEnabled: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val body = ChatCompletionRequest(model = model, messages = messages)
        val json = gson.toJson(body)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Utopia", if (utopiaEnabled) "1" else "0")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (response.isSuccessful) {
            val completion = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            val content = completion.choices?.firstOrNull()?.message?.content
            if (content != null) content
            else throw Exception("API 返回空内容: ${responseBody.take(200)}")
        } else {
            val err = try { gson.fromJson(responseBody, ChatCompletionResponse::class.java) } catch (_: Exception) { null }
            val errMsg = err?.error?.message ?: "HTTP ${response.code}: ${responseBody.take(200)}"
            throw Exception(errMsg)
        }
    }
}

/**
 * 统一入口 — 根据 protocol 自动路由
 * "openai"     → DirectApiClient (OpenAI 兼容)
 * "anthropic"  → AnthropicApiClient (Anthropic 原生)
 */
object UnifiedApiClient {

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        protocol: String = "openai",
        utopiaEnabled: Boolean = false,
    ): String {
        return when (protocol) {
            "anthropic" -> AnthropicApiClient.chat(baseUrl, apiKey, model, messages, null, utopiaEnabled)
            else -> DirectApiClient.chat(baseUrl, apiKey, model, messages, utopiaEnabled)
        }
    }
}
