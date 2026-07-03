package com.mbclaw.dev.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AccountManager — 账号信息读写
 *  • 优先 QQ，其次微信
 *  • QQ 头像: https://q.qlogo.cn/headimg_dl?dst_uin={qq}&spec=640
 *  • 微信 ID: 用户自己填 (微信不开放公开头像 URL)
 *  • 云端: 上传到我们的后端 /admin/client/account
 *      symbol 一致 → 自动登录返回所有同步数据
 */
data class Account(
    val qqId: String = "",
    val weixinId: String = "",
    val nickname: String = "",
    val avatarCacheFile: String = "",
) {
    fun isLogged() = qqId.isNotBlank() || weixinId.isNotBlank()
    fun displayName(): String = when {
        nickname.isNotBlank() -> nickname + (if (qqId.isNotBlank()) " (QQ $qqId)" else if (weixinId.isNotBlank()) " (微信 $weixinId)" else "")
        qqId.isNotBlank() -> "QQ $qqId"
        weixinId.isNotBlank() -> "微信 $weixinId"
        else -> "未登录 · 点击设置"
    }
    fun avatarUrl(): String? = if (qqId.isNotBlank()) "https://q.qlogo.cn/headimg_dl?dst_uin=$qqId&spec=640" else null
}

object AccountManager {

    private const val PREF = "mbclaw_account"

    fun load(ctx: Context): Account {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Account(
            qqId = sp.getString("qq_id", "") ?: "",
            weixinId = sp.getString("wx_id", "") ?: "",
            nickname = sp.getString("nickname", "") ?: "",
            avatarCacheFile = sp.getString("avatar_cache", "") ?: "",
        )
    }

    fun save(ctx: Context, account: Account) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("qq_id", account.qqId)
            .putString("wx_id", account.weixinId)
            .putString("nickname", account.nickname)
            .putString("avatar_cache", account.avatarCacheFile)
            .apply()
    }

    /** 异步下载 QQ 头像缓存到本地 */
    suspend fun downloadAvatarIfNeeded(ctx: Context, account: Account): String? {
        val url = account.avatarUrl() ?: return null
        val cacheFile = File(ctx.cacheDir, "avatar_${account.qqId}.jpg")
        if (cacheFile.exists() && cacheFile.length() > 100) return cacheFile.absolutePath
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 MBclaw")
                conn.inputStream.use { input ->
                    cacheFile.outputStream().use { out -> input.copyTo(out) }
                }
                if (cacheFile.length() > 100) {
                    save(ctx, account.copy(avatarCacheFile = cacheFile.absolutePath))
                    cacheFile.absolutePath
                } else null
            }
        } catch (_: Exception) { null }
    }

    /** 同步账号到服务器 (任务 8: 云端自动登录) */
    suspend fun syncToServer(ctx: Context, account: Account, serverUrl: String): Boolean = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val u = URL("${serverUrl.trimEnd('/')}/admin/client/account/sync")
            val conn = u.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            val body = """{"qq":"${account.qqId}","wx":"${account.weixinId}","nick":"${account.nickname}"}"""
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode in 200..299
        }
    } catch (_: Exception) { false }

    /** 从服务器拉取上次保存（任务 8: 自动登录） */
    suspend fun fetchFromServer(serverUrl: String, qqOrWx: String): Account? = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val u = URL("${serverUrl.trimEnd('/')}/admin/client/account/lookup?id=$qqOrWx")
            val conn = u.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            val txt = conn.inputStream.bufferedReader().readText()
            val j = org.json.JSONObject(txt)
            if (j.optBoolean("found")) Account(
                qqId = j.optString("qq", ""),
                weixinId = j.optString("wx", ""),
                nickname = j.optString("nick", ""),
            ) else null
        }
    } catch (_: Exception) { null }
}
