package com.mbclaw.nonroot.agent

import android.content.Context
import com.mbclaw.nonroot.data.UserSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MiclawBridge — APK 端申请 + 轮询白嫖
 */
object MiclawBridge {

    data class ApplyResult(
        val approved: Boolean,
        val applicationId: String = "",
        val loginUrl: String = "",
        val message: String = "",
        val killCommand: Boolean = false,
    )

    data class StatusResult(
        val ready: Boolean,
        val userToken: String = "",
        val model: String = "",
        val isStub: Boolean = false,
        val reason: String = "",
    )

    /** 申请白嫖 — 服务器会为该用户启动专属代理实例 */
    suspend fun apply(ctx: Context, serverUrl: String, userId: String): ApplyResult {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val u = URL("${serverUrl.trimEnd('/')}/bridge/miclaw/apply")
                val conn = u.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("device_id", AntiTamper.deviceFingerprint(ctx))
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val txt = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                          else conn.errorStream.bufferedReader().readText()
                val j = JSONObject(txt)
                ApplyResult(
                    approved = j.optBoolean("approved", false),
                    applicationId = j.optString("application_id", ""),
                    loginUrl = j.optString("login_url", ""),
                    message = j.optString("status", "") + j.optString("reason", "") + j.optString("message", ""),
                    killCommand = j.optBoolean("kill_command", false),
                )
            }
        } catch (e: Exception) {
            ApplyResult(approved = false, message = "网络失败: ${e.message}")
        }
    }

    /** 轮询状态 */
    suspend fun status(serverUrl: String, applicationId: String): StatusResult {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val u = URL("${serverUrl.trimEnd('/')}/bridge/miclaw/status?application_id=$applicationId")
                val conn = u.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                val txt = conn.inputStream.bufferedReader().readText()
                val j = JSONObject(txt)
                StatusResult(
                    ready = j.optBoolean("ready", false),
                    userToken = j.optString("user_token", ""),
                    model = j.optString("model", "miclaw"),
                    isStub = j.optBoolean("is_stub", false),
                    reason = j.optString("reason", "") + j.optString("status", ""),
                )
            }
        } catch (e: Exception) {
            StatusResult(false, reason = "网络失败: ${e.message}")
        }
    }

    /** 配置成功后写入 UserSettings */
    fun applyToSettings(settings: UserSettings, serverUrl: String, userToken: String, model: String) {
        settings.providerId = "miclaw-bridge"
        settings.apiBaseUrl = "${serverUrl.trimEnd('/')}/bridge/miclaw/v1"
        settings.apiKey = userToken
        settings.modelName = model
    }
}
