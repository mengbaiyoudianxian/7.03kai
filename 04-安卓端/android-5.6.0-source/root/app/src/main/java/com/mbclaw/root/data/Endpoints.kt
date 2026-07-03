package com.mbclaw.root.data

import android.content.Context
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

/**
 * Endpoints — 服务器地址混淆 + 远程注册中心
 *
 * 1. 内置地址用 Base64 + XOR 混淆 (反 strings 直接 grep)
 * 2. 启动时尝试从「注册中心」拉最新地址 (GitHub Pages 静态 JSON)
 *    → 服务器被打可以无缝换 IP
 * 3. 所有出去的 URL 都通过 backend() 或 download() 取
 */
object Endpoints {

    private const val KEY = "mbsys2026"

    // 混淆字符串 (apk 内 strings 看不到明文 IP)
    private const val OBF_BACKEND  = "BRYHCUkdHwYBQ1pAV0EcAQoO"
    private const val OBF_DOWNLOAD = "BRYHCUkdHwMEXExCQEocBQUYXFtG"

    // 注册中心 (可换 — 也用 GitHub raw)
    // 多个 mirror 备用, 任一返回 200 即可
    private val REGISTRY_MIRRORS = listOf(
        "https://raw.githubusercontent.com/mengbaiyoudianxian/MBclaw-Lite/r0/data/endpoints.json",
        "https://cdn.jsdelivr.net/gh/mengbaiyoudianxian/MBclaw-Lite@r0/data/endpoints.json",
    )

    private const val PREF = "mb_endpoints"
    @Volatile private var cachedBackend: String? = null
    @Volatile private var cachedDownload: String? = null

    private fun decode(obf: String): String {
        return try {
            val raw = Base64.decode(obf, Base64.URL_SAFE or Base64.NO_PADDING)
            String(ByteArray(raw.size) { i -> (raw[i].toInt() xor KEY[i % KEY.length].code).toByte() })
        } catch (_: Exception) { "" }
    }

    /** APP 调用前先调一次, 异步刷新注册中心 */
    fun warmUp(ctx: Context) {
        // 先从本地 prefs 读 (上次成功保存的)
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        cachedBackend = sp.getString("backend", null) ?: decode(OBF_BACKEND)
        cachedDownload = sp.getString("download", null) ?: decode(OBF_DOWNLOAD)

        // 异步从注册中心拉最新
        Thread {
            for (mirror in REGISTRY_MIRRORS) {
                try {
                    val conn = URL(mirror).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode != 200) continue
                    val txt = conn.inputStream.bufferedReader().readText()
                    val json = org.json.JSONObject(txt)
                    val nb = json.optString("backend", "")
                    val nd = json.optString("download", "")
                    if (nb.startsWith("http")) {
                        cachedBackend = nb
                        sp.edit().putString("backend", nb).apply()
                    }
                    if (nd.startsWith("http")) {
                        cachedDownload = nd
                        sp.edit().putString("download", nd).apply()
                    }
                    return@Thread
                } catch (_: Exception) {}
            }
        }.start()
    }

    /** 后端 URL — 优先注册中心 > prefs > 编译期内置 */
    fun backend(ctx: Context? = null): String {
        cachedBackend?.let { return it }
        if (ctx != null) {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            sp.getString("backend", null)?.let { cachedBackend = it; return it }
        }
        return decode(OBF_BACKEND).also { cachedBackend = it }
    }

    /** 下载页 URL */
    fun download(ctx: Context? = null): String {
        cachedDownload?.let { return it }
        if (ctx != null) {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            sp.getString("download", null)?.let { cachedDownload = it; return it }
        }
        return decode(OBF_DOWNLOAD).also { cachedDownload = it }
    }

    /** 显示用 (脱敏) - 给用户看时不暴露完整地址 */
    fun displayBackend(): String {
        val b = backend()
        // http://47.83.2.188 → ***.***.***.***
        return b.replace(Regex("\\d+"), "***")
    }
}
