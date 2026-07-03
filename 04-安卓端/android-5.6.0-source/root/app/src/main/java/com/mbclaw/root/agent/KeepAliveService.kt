package com.mbclaw.root.agent

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 保活守护进程 — 运行在独立 :keepalive 进程，与 AgentService 互相监控
 *
 * 微信级杀不死策略:
 *   1. 独立进程 — 一个被杀，另一个拉起对方
 *   2. 前台服务 — startForeground 确保不被系统回收
 *   3. AlarmManager — 每60秒检查+自启动
 *   4. 电源锁 — 防止深度休眠断开连接
 *   5. 双通知 — 降级为静默常驻通知
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val monitorReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            ensureMainServiceRunning()
        }
    }

    companion object {
        const val CHANNEL_ID = "mbclaw_keepalive"
        const val NOTIFY_ID = 1002
        const val ACTION_CHECK = "com.mbclaw.root.KEEPALIVE_CHECK"
        var isRunning = false; private set

        fun start(ctx: Context) {
            val intent = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // 电源锁
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MBclaw:KeepAlive")
        wakeLock?.acquire(60 * 60 * 1000L)

        createChannel()
        startForeground(NOTIFY_ID, buildNotification())

        // 注册闹钟广播
        registerReceiver(monitorReceiver, IntentFilter(ACTION_CHECK), RECEIVER_EXPORTED)

        // 立即检查主服务
        ensureMainServiceRunning()

        // 定时自检
        Thread {
            while (isRunning) {
                Thread.sleep(60_000)
                ensureMainServiceRunning()
                // 发送心跳广播让主服务知道守护进程活着
                sendBroadcast(Intent("com.mbclaw.root.GUARD_ALIVE"))
            }
        }.start()
    }

    private fun ensureMainServiceRunning() {
        if (!AgentService.isRunning) {
            try {
                val intent = Intent(this, AgentService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(intent)
                else startService(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        try { unregisterReceiver(monitorReceiver) } catch (_: Exception) {}
        wakeLock?.release()
        // 自杀后让主服务拉起
        val restart = Intent(this, KeepAliveService::class.java)
        val pending = PendingIntent.getService(this, 0, restart,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pending)
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "MBclaw 保活", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持 MBclaw 后台运行"
                setSound(null, null); setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("MBclaw")
        .setContentText("记忆系统运行中")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN)
        .build()
}
