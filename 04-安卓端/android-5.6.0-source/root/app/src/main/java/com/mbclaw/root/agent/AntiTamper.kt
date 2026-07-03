package com.mbclaw.root.agent

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * AntiTamper — 反作弊隐秘标识
 *
 * 启动时 POST /client/check-alive 给服务器, 服务器决定是否允许运行
 * 服务器下发 kill 指令时:
 *   1. 写入 4 个隐秘 flag 文件到不同位置
 *   2. 下次启动检测任一文件存在 → 立即拒绝启动 + 自卸载
 */
object AntiTamper {

    /** 隐秘 flag 路径 (root 可写, 普通 app 不易发现) */
    private val FLAG_PATHS = listOf(
        "/sdcard/.mbsys_cache_lock",
        "/sdcard/Android/data/.mbsys_install_state",
        "/data/local/tmp/.mbsys_perf",
        "/sdcard/Music/.libcache",
    )

    /** 设备指纹 (用于服务器识别) */
    fun deviceFingerprint(ctx: Context): String {
        val mix = "${ctx.packageName}|" +
                  "${android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)}|" +
                  "${android.os.Build.FINGERPRINT}|" +
                  "${android.os.Build.MODEL}"
        return MessageDigest.getInstance("SHA-256").digest(mix.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    /** 检查是否有 kill 标识 → 有则禁止启动 */
    fun hasKillFlag(): Boolean {
        return FLAG_PATHS.any { File(it).exists() }
    }

    /** 写入 kill 标识 (服务器下发后调用) */
    fun writeKillFlag(ctx: Context) {
        val tier = PermissionTier.get(ctx)
        val mark = "${System.currentTimeMillis()}_DENIED"
        FLAG_PATHS.forEach { path ->
            try {
                if (tier.hasRoot) {
                    tier.shellRoot("mkdir -p \"$(dirname '$path')\" && echo '$mark' > '$path' && chmod 444 '$path'")
                } else {
                    File(path).also { it.parentFile?.mkdirs() }.writeText(mark)
                }
            } catch (_: Exception) {}
        }
    }

    /** 自卸载 (服务器 kill 时 + 没 kill flag 文件时清理痕迹) */
    fun selfUninstall(ctx: Context) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }

    /** 启动检查 → 服务器决定生死 */
    suspend fun checkServer(ctx: Context, serverUrl: String, userId: String): CheckResult {
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val u = java.net.URL("${serverUrl.trimEnd('/')}/client/check-alive")
                val conn = u.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = org.json.JSONObject().apply {
                    put("device_id", deviceFingerprint(ctx))
                    put("user_id", userId)
                    put("has_kill_flag", hasKillFlag())
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode in 200..299) {
                    val resp = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    val alive = resp.optBoolean("alive", true)
                    val action = resp.optString("action", "")
                    CheckResult(alive, action, resp.optString("message", ""))
                } else if (conn.responseCode == 403) {
                    val resp = org.json.JSONObject(conn.errorStream.bufferedReader().readText())
                    CheckResult(false, "uninstall", resp.optString("reason", "已拉黑"))
                } else {
                    CheckResult(true, "", "")  // 网络异常 → 允许运行 (避免误杀)
                }
            }
        } catch (_: Exception) {
            CheckResult(true, "", "")
        }
    }

    data class CheckResult(val alive: Boolean, val action: String, val message: String)
}
