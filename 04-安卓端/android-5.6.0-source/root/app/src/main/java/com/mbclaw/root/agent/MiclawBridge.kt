package com.mbclaw.root.agent

import android.content.Context
import com.mbclaw.root.data.UserSettings
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
        val tokensUsed: Long = 0,
        val savedYuan: Double = 0.0,
        val uptimeMinutes: Long = 0,
    )

    /** 申请白嫖 — 服务器会为该用户启动专属代理实例 (带重试) */
    suspend fun apply(ctx: Context, serverUrl: String, userId: String): ApplyResult {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val u = URL("${serverUrl.trimEnd('/')}/bridge/miclaw/apply")
                val conn = u.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000; conn.readTimeout = 15000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("device_id", AntiTamper.deviceFingerprint(ctx))
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val txt = try {
                    if (code in 200..299) conn.inputStream.bufferedReader().readText()
                    else conn.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $code\"}"
                } catch (e: Exception) { "{\"error\":\"HTTP $code\"}" }
                val j = try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
                val msg = listOfNotNull(
                    j.optString("message", ""), j.optString("reason", ""), j.optString("error", "")
                ).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "HTTP $code" }
                ApplyResult(
                    approved = j.optBoolean("approved", false),
                    applicationId = j.optString("application_id", ""),
                    loginUrl = j.optString("login_url", ""),
                    message = msg,
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
                conn.connectTimeout = 6000; conn.readTimeout = 10000
                val code = conn.responseCode
                val txt = try {
                    if (code in 200..299) conn.inputStream.bufferedReader().readText()
                    else conn.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $code\"}"
                } catch (e: Exception) { "{\"error\":\"HTTP $code\"}" }
                val j = try { JSONObject(txt) } catch (_: Exception) { JSONObject() }
                StatusResult(
                    ready = j.optBoolean("ready", false),
                    userToken = j.optString("token", j.optString("user_token", "")),
                    model = j.optString("model", "miclaw"),
                    isStub = j.optBoolean("is_stub", false),
                    reason = listOfNotNull(j.optString("reason",""), j.optString("status",""), j.optString("error","")).filter{it.isNotBlank()}.joinToString(" / ").ifBlank { "HTTP $code" },
                    tokensUsed = j.optLong("tokens_used", 0),
                    savedYuan = j.optDouble("saved_yuan", 0.0),
                    uptimeMinutes = j.optLong("uptime_minutes", 0),
                )
            }
        } catch (e: Exception) {
            StatusResult(false, reason = "网络失败: ${e.message}")
        }
    }

    /** 暂停代理 (带重试) */
    suspend fun stopProxy(serverUrl: String, applicationId: String): Boolean {
        repeat(2) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val u = URL("${serverUrl.trimEnd('/')}/bridge/miclaw/stop?application_id=$applicationId")
                    val conn = u.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"; conn.connectTimeout = 6000; conn.readTimeout = 6000
                    if (conn.responseCode in 200..299) return@withContext
                }
                return true  // withContext 成功 → 整体成功
            } catch (_: Exception) {}
        }
        return false
    }

    /** 删除代理并清除配置 (带重试) */
    suspend fun deleteProxy(serverUrl: String, applicationId: String): Boolean {
        repeat(2) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val u = URL("${serverUrl.trimEnd('/')}/bridge/miclaw/destroy?application_id=$applicationId")
                    val conn = u.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"; conn.connectTimeout = 6000; conn.readTimeout = 6000
                    if (conn.responseCode in 200..299) return@withContext
                }
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    /** 配置成功后写入 UserSettings */
    fun applyToSettings(settings: UserSettings, serverUrl: String, userToken: String, model: String) {
        settings.providerId = "miclaw-bridge"
        settings.apiBaseUrl = "${serverUrl.trimEnd('/')}/bridge/miclaw/v1"
        settings.apiKey = userToken
        settings.modelName = model
    }
}
