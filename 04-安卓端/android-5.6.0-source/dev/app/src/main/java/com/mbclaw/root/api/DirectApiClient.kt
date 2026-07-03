package com.mbclaw.dev.api

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
 * 直连大模型 API — OpenAI 兼容格式
 *
 * 不依赖服务器，直接调 25 个提供商任一
 * 仅需 baseUrl + apiKey + modelName
 */

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = false,
)

data class ChatMessage(
    val role: String,       // system / user / assistant
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
 * 直接调用 LLM API
 */
object DirectApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 发送聊天请求
     *
     * @param baseUrl  API 地址 (如 https://api.deepseek.com)
     * @param apiKey   API Key
     * @param model    模型名 (如 deepseek-chat)
     * @param messages 消息历史
     * @return 回复文本，失败时抛异常
     */
    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        utopiaEnabled: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val body = ChatCompletionRequest(
            model = model,
            messages = messages,
        )
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
            val content = completion.choices
                ?.firstOrNull()
                ?.message
                ?.content
            if (content != null) {
                content
            } else {
                throw Exception("API 返回空内容: ${responseBody.take(200)}")
            }
        } else {
            val err = try {
                gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            } catch (_: Exception) { null }
            val errMsg = err?.error?.message
                ?: "HTTP ${response.code}: ${responseBody.take(200)}"
            throw Exception(errMsg)
        }
    }
}
