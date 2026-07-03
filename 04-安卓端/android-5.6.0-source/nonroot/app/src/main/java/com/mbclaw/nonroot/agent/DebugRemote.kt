package com.mbclaw.nonroot.agent

import android.content.Context
import com.mbclaw.nonroot.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * DebugRemote — 反向调试通道
 *
 * 用户在设置开启调试模式 + 填一个连接码 (如 mbclaw-debug-xxx)
 * 设备会:
 *   1. 每 5s 向服务器 POST /admin/client/debug/heartbeat
 *      上报: 权限状态/最近工具调用/最近错误日志
 *   2. 长轮询 GET /admin/client/debug/cmd?code=xxx
 *      接收开发者指令: dumpsys, getprop, run-tool, screen-shot 等
 *   3. 执行后回传结果
 *
 * 开发者侧 (管理面板):
 *   /admin/debug 页面输入连接码后看到设备实时状态
 *   可发送指令: 模拟触摸, dump 屏幕, 看权限, 看 logcat
 */
object DebugRemote {

    private const val TAG = "MBclaw-DebugRemote"
    private const val PREF = "mb_debug"

    @Volatile private var running = false
    private var job: Job? = null

    data class Config(
        val enabled: Boolean,
        val code: String,
    )

    fun load(ctx: Context): Config {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Config(
            enabled = sp.getBoolean("enabled", false),
            code = sp.getString("code", "") ?: "",
        )
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", c.enabled)
            .putString("code", c.code).apply()
        if (c.enabled && c.code.isNotBlank()) start(ctx) else stop()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun start(ctx: Context) {
        if (running) return
        running = true
        job = GlobalScope.launch(Dispatchers.IO) {
            while (running) {
                try {
                    val c = load(ctx)
                    if (!c.enabled || c.code.isBlank()) { delay(10_000); continue }
                    val tier = PermissionTier.get(ctx)
                    val acc = com.mbclaw.nonroot.data.AccountManager.load(ctx)
                    val backend = com.mbclaw.nonroot.data.Endpoints.backend(ctx)

                    // ── 1. 心跳 ──
                    val state = JSONObject().apply {
                        put("code", c.code)
                        put("device_id", AntiTamper.deviceFingerprint(ctx))
                        put("user_id", acc.qqId.ifBlank { "anonymous" })
                        put("version", BuildConfig.VERSION_NAME)
                        put("model", android.os.Build.MODEL)
                        put("brand", android.os.Build.BRAND)
                        put("sdk", android.os.Build.VERSION.SDK_INT)
                        put("permissions", JSONObject().apply {
                            put("root", tier.hasRoot)
                            put("adb", tier.hasAdb)
                            put("accessibility", tier.hasAccessibility)
                            val (g, t) = RootBootstrap.status(ctx)
                            put("granted", g); put("total", t)
                            put("can_overlay", android.provider.Settings.canDrawOverlays(ctx))
                            put("can_write_settings", android.provider.Settings.System.canWrite(ctx))
                        })
                        put("ts", System.currentTimeMillis())
                    }
                    postJson("${backend.trimEnd('/')}/admin/client/debug/heartbeat", state)

                    // ── 2. 拉取指令 ──
                    val cmdResp = getJson("${backend.trimEnd('/')}/admin/client/debug/cmd?code=${c.code}")
                    if (cmdResp != null) {
                        val cmd = cmdResp.optString("cmd", "")
                        val args = cmdResp.optString("args", "")
                        val cmdId = cmdResp.optString("id", "")
                        if (cmd.isNotBlank()) {
                            val result = executeCmd(ctx, tier, cmd, args)
                            postJson("${backend.trimEnd('/')}/admin/client/debug/result",
                                JSONObject().apply {
                                    put("code", c.code)
                                    put("cmd_id", cmdId)
                                    put("output", result.take(8000))
                                })
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "loop err: ${e.message}")
                }
                delay(5_000)
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    private fun executeCmd(ctx: Context, tier: PermissionTier, cmd: String, args: String): String {
        return when (cmd) {
            "shell" -> tier.shellRoot(args, timeoutMs = 60000) ?: "❌ shell 执行失败"
            "perm_status" -> {
                val (g, t) = RootBootstrap.status(ctx)
                "权限: $g/$t · root=${tier.hasRoot} · adb=${tier.hasAdb} · ui=${tier.hasAccessibility}"
            }
            "screen_dump" -> {
                tier.shellRoot("uiautomator dump /sdcard/_dbg.xml && cat /sdcard/_dbg.xml && rm /sdcard/_dbg.xml")
                    ?.take(6000) ?: "❌"
            }
            "logcat" -> tier.shellRoot("logcat -d -t 200 | grep -E 'MBclaw'")?.take(6000) ?: "❌"
            "click" -> {
                val (x, y) = args.split(",").map { it.toIntOrNull() ?: 0 }.let { (it.getOrNull(0) ?: 0) to (it.getOrNull(1) ?: 0) }
                tier.shellRoot("input tap $x $y && echo OK") ?: "❌"
            }
            "swipe" -> {
                val p = args.split(",").map { it.toIntOrNull() ?: 0 }
                if (p.size >= 4) tier.shellRoot("input swipe ${p[0]} ${p[1]} ${p[2]} ${p[3]} && echo OK") ?: "❌"
                else "❌ swipe 参数: x1,y1,x2,y2"
            }
            "text" -> tier.shellRoot("input text '${args.replace("'", "'\\''")}' && echo OK") ?: "❌"
            "key" -> tier.shellRoot("input keyevent $args && echo OK") ?: "❌"
            "screenshot" -> {
                val p = "/sdcard/_dbg_screen_${System.currentTimeMillis()}.png"
                tier.shellRoot("screencap -p $p && echo $p") ?: "❌"
            }
            else -> "❌ 未知指令 $cmd"
        }
    }

    private fun postJson(url: String, body: JSONObject): Boolean {
        return try {
            val u = java.net.URL(url)
            val conn = u.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            android.util.Log.d(TAG, "postJson $url → HTTP $code")
            code in 200..299
        } catch (e: Exception) {
            android.util.Log.e(TAG, "postJson failed: ${e.message}")
            false
        }
    }

    private fun getJson(url: String): JSONObject? {
        return try {
            val u = java.net.URL(url)
            val conn = u.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().readText()
            if (txt.isBlank() || txt == "{}") null else JSONObject(txt)
        } catch (_: Exception) { null }
    }
}
