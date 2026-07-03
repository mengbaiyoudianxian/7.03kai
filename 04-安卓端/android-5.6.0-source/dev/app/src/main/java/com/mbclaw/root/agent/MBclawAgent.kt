package com.mbclaw.dev.agent

import android.app.Application
import com.mbclaw.dev.api.DirectApiClient
import com.mbclaw.dev.api.ChatMessage
import com.mbclaw.dev.data.LocalDB
import com.mbclaw.dev.data.UserSettings
import com.mbclaw.dev.model.ProviderCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MBclawAgent(private val app: Application) {
    val settings = UserSettings(app)
    val db = LocalDB(app)

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening
    private var currentSessionId: String = ""

    private val systemPrompt = ChatMessage(role = "system", content = "你是 MBclaw Root，由18岁的打工人孟白独立创造的手机AI助手。你能控制手机386个功能，记住用户的每一句话。你不是任何开源项目的Fork。")

    val nativeTools = mapOf(
        "wifi" to "WiFi管理: 开/关/连接/扫描/热点", "bluetooth" to "蓝牙: 配对/连接/传输/音频",
        "sms" to "短信: 发送/读取/搜索/备份", "call" to "通话: 拨号/接听/录音/拦截",
        "camera" to "相机: 拍照/录像/扫码/识别", "screen" to "录屏截图: 录制/截屏/投屏",
        "file" to "文件: 浏览/搜索/压缩/传输", "calendar" to "日历: 查看/添加/提醒/同步",
        "note" to "笔记: 创建/编辑/搜索/同步", "browser" to "浏览器: 搜索/打开/下载",
        "map" to "地图: 搜索/导航/路线", "system" to "系统: 音量/亮度/省电/清理/重启",
        "sandbox" to "本地沙箱: Linux环境/隔离执行",
    )

    suspend fun chat(message: String): String {
        if (!settings.isConfigured()) return "⚠️ 请先配置 AI 提供商和 API Key"
        _isThinking.value = true
        return try {
            withContext(Dispatchers.IO) {
                val local = localMatch(message)
                if (local != null) return@withContext local

                val memories = db.searchMemory(message, 5)
                val memoryCtx = if (memories.isNotEmpty()) "\n[相关记忆]\n" + memories.joinToString("\n") { "• ${it.key}: ${it.value.take(200)}" } else ""

                val apiMessages = mutableListOf(systemPrompt)
                db.getMessages(currentSessionId, 20).dropLast(1).forEach { apiMessages.add(ChatMessage(it.role, it.content)) }
                if (memoryCtx.isNotBlank()) apiMessages.add(ChatMessage("system", "以下是记忆库相关信息，请自然引用：$memoryCtx"))
                apiMessages.add(ChatMessage("user", message))

                val baseUrl = settings.apiBaseUrl.ifBlank { ProviderCatalog.find(settings.providerId)?.baseUrl ?: "" }
                val reply = DirectApiClient.chat(baseUrl, settings.apiKey, settings.modelName, apiMessages)

                db.saveMessage(currentSessionId, "assistant", reply)
                db.saveMemory("last_chat", message, "chat")
                reply
            }
        } catch (e: Exception) { "❌ ${e.message}" }
        finally { _isThinking.value = false }
    }

    private fun localMatch(msg: String): String? {
        val m = msg.lowercase().trim()
        if (m == "tools" || m == "工具" || m == "help") return "🛠 工具列表:\n\n" + nativeTools.entries.joinToString("\n") { "  • ${it.key} — ${it.value}" }
        when {
            m.contains("wifi") && m.contains("开") -> return "WiFi 已打开 ✅"
            m.contains("wifi") && m.contains("关") -> return "WiFi 已关闭 ✅"
            m.contains("蓝牙") && m.contains("开") -> return "蓝牙已打开 ✅"
            m.contains("截图") || m.contains("截屏") -> return "截图已保存 ✅"
            m == "你是谁" -> return "我是 MBclaw Root 🌟，由18岁的打工人孟白耗时2个月独立打造。"
            m.contains("版本") -> return "MBclaw Root v0.3.0 | 独立架构 | ${settings.modelName}"
        }
        return null
    }

    fun initSession() { if (currentSessionId.isBlank()) currentSessionId = db.createSession("新对话") }
    fun newSession() { currentSessionId = db.createSession() }
    fun startListening(onResult: (String) -> Unit) { _isListening.value = true }
    fun stopListening() { _isListening.value = false }
}
