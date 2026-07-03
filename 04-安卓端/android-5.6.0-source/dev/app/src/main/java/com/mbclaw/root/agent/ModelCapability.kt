package com.mbclaw.dev.agent

import com.mbclaw.dev.data.UserSettings

/**
 * ModelCapability — 检测当前模型是否原生支持视觉/语音
 *
 * 检测逻辑:
 *  1. 先看用户是否专门配了对应 Key (visionEnabled/voiceEnabled)
 *  2. 没配 → 根据 modelName 推断主模型是否原生支持
 *  3. 都不支持 → 返回 NotSupported, UI 提示并跳转到设置
 */
object ModelCapability {

    sealed class Result {
        data class Ready(val baseUrl: String, val key: String, val model: String, val source: String) : Result()
        data class NotSupported(val reason: String, val suggestSetup: Boolean = true) : Result()
    }

    // 主流支持视觉的模型 (substring 匹配)
    private val VISION_MODELS = listOf(
        "gpt-4o", "gpt-4-vision", "gpt-4-turbo", "o1",
        "claude-3", "claude-sonnet-4", "claude-opus-4", "claude-haiku-4",
        "gemini-2.5", "gemini-2.0", "gemini-1.5",
        "qwen-vl", "qwen2-vl", "qwen2.5-vl",
        "glm-4v", "glm-4.1v",
        "step-1v", "step-2-16k",
        "yi-vision", "doubao-vision",
        "mimo-v2.5-vl",
    )

    // 主流支持 (内置) TTS/ASR 的模型 — 实际很少, 多数需要专门接口
    private val VOICE_MODELS = listOf(
        "gpt-4o-realtime", "gpt-4o-mini-tts", "tts-1", "whisper",
        "qwen-audio", "qwen2-audio",
    )

    fun checkVision(settings: UserSettings): Result {
        if (settings.visionEnabled && settings.visionApiKey.isNotBlank() && settings.visionBaseUrl.isNotBlank()) {
            return Result.Ready(settings.visionBaseUrl, settings.visionApiKey, settings.visionModel, "专配")
        }
        val main = settings.modelName.lowercase()
        if (VISION_MODELS.any { main.contains(it) }) {
            return Result.Ready(
                settings.apiBaseUrl, settings.apiKey, settings.modelName, "主模型自带"
            )
        }
        return Result.NotSupported(
            "当前模型「${settings.modelName}」不支持识图。\n" +
            "请到 设置 → 模型 API 配置 → 视觉模型 填入支持识图的 Key。"
        )
    }

    fun checkVoice(settings: UserSettings): Result {
        if (settings.voiceEnabled && settings.voiceApiKey.isNotBlank() && settings.voiceBaseUrl.isNotBlank()) {
            return Result.Ready(settings.voiceBaseUrl, settings.voiceApiKey, settings.voiceTtsModel, "专配")
        }
        val main = settings.modelName.lowercase()
        if (VOICE_MODELS.any { main.contains(it) }) {
            return Result.Ready(
                settings.apiBaseUrl, settings.apiKey, settings.modelName, "主模型自带"
            )
        }
        return Result.NotSupported(
            "当前模型「${settings.modelName}」不支持语音。\n" +
            "请到 设置 → 模型 API 配置 → 语音模型 填入支持 TTS/ASR 的 Key。"
        )
    }
}
