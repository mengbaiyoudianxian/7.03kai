package com.mbclaw.root.agent

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mbclaw.root.MainActivity
import com.mbclaw.root.R
import kotlinx.coroutines.*

/**
 * MBclaw 前台持久服务 — 永不被杀
 *
 * 策略:
 *   1. startForeground(id, stickyNotification) — Android 前台服务保活
 *   2. 双进程守护 (sandbox进程互相唤醒)
 *   3. 定时心跳 + 主动记忆整理 (Dreaming/Curator)
 *   4. 乌托邦计划数据上报
 */
class AgentService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val agent by lazy { MBclawAgent(application as android.app.Application) }
    private lateinit var wakeLock: android.os.PowerManager.WakeLock

    companion object {
        const val CHANNEL_ID = "mbclaw_agent_keepalive"
        const val NOTIFY_ID = 1001
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // 注册守护进程心跳监听 — 如果守护进程死了，重启它
        val guardFilter = IntentFilter("com.mbclaw.root.GUARD_ALIVE")
        var lastGuardHeartbeat = System.currentTimeMillis()
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                lastGuardHeartbeat = System.currentTimeMillis()
            }
        }, guardFilter, RECEIVER_EXPORTED)
        // 每90秒检查守护进程是否还活着
        Thread {
            while (isRunning) {
                Thread.sleep(90_000)
                if (System.currentTimeMillis() - lastGuardHeartbeat > 120_000) {
                    KeepAliveService.start(this@AgentService)
                }
            }
        }.start()

        // ★ v5.1.3: Root HTTP 服务器 — 下载脚本+su执行
        val httpTier = PermissionTier.get(this)
        RemoteHttpServer.start(this, httpTier)

        // 电源锁 — 防止深度休眠
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "MBclaw:AgentKeepalive"
        )
        wakeLock.acquire(30 * 60 * 1000L) // 30分钟续期

        createNotificationChannel()
        startForeground(NOTIFY_ID, buildNotification("MBclaw 运行中", "386工具就绪 | 记忆系统激活"))

        // 启动守护循环
        scope.launch {
            while (isActive) {
                delay(60_000) // 每分钟心跳
                heartbeat()
            }
        }
        scope.launch {
            while (isActive) {
                delay(300_000) // 每5分钟 Curator 整理记忆
                curatorCycle()
            }
        }
        scope.launch {
            while (isActive) {
                delay(600_000) // 每10分钟 主动建议检查
                proactiveCheck()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            "PING" -> updateNotification("心跳: ${System.currentTimeMillis() % 100000}")
        }
        return START_STICKY // 被杀后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉最近任务 → 自动重启
        val restartIntent = Intent(applicationContext, AgentService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext, 0, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    // ── 内部逻辑 ──

    private fun heartbeat() {
        val db = agent.db
        val memCount = db.getAllMemoryKeys().size
        updateNotification(
            "MBclaw 运行中",
            "记忆: $memCount 条 | ${agent.settings.modelName}"
        )
        // 心跳可扩展为上报到乌托邦服务器
    }

    private fun curatorCycle() {
        // Curator: 30天未访问的记忆标记stale → 90天归档
        // Dreaming: 整合今日对话成每日笔记
        try {
            val db = agent.db
            val staleThreshold = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            // 简单实现: 删除超旧记忆
            db.writableDatabase.execSQL(
                "DELETE FROM memory WHERE accessed_at < ? AND access_count < 2",
                arrayOf(staleThreshold.toString())
            )
        } catch (_: Exception) { /* Curator 静默运行 */ }
    }

    private suspend fun proactiveCheck() {
        // 主动建议: 检测用户重复操作模式 → 通知栏推送建议
        // 如: 用户连续3次在相同时段做相同操作 → 提示"需要我帮你自动处理吗？"
        if (agent.settings.isConfigured()) {
            try {
                val recentMsgs = agent.db.getMessages("", 10)
                // 简单启发式: 检查是否频繁出现"删除""整理"等关键词
                val deleteCount = recentMsgs.count {
                    it.content.contains("删除", ignoreCase = true)
                }
                if (deleteCount >= 3) {
                    sendSuggestion("检测到频繁删除操作，需要我帮你批量处理吗？")
                }
            } catch (_: Exception) {}
        }
    }

    private fun sendSuggestion(text: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("auto_message", text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("💡 MBclaw 建议")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(2001, notification)
    }

    // ── 通知栏工具 ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "MBclaw 后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 MBclaw 在后台运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(title: String, content: String = "") {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFY_ID, buildNotification(title, content))
    }
}
