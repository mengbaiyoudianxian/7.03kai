package com.mbclaw.root.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.HorizontalScrollView
import androidx.core.app.NotificationCompat
import com.mbclaw.root.MainActivity

/**
 * AgentFloatingService — AI 运行时悬浮窗 + 常驻通知
 *
 * 悬浮窗新规格 (v4.4):
 *  - 宽度: 200dp (是之前 ~2 倍)
 *  - 高度: 增加 4mm (约 16dp + 文字)
 *  - 屏幕下 1/3 处
 *  - 半透明背景 (透明度 0.35)
 *  - 内容: 先 "AI 运行中点击可终止" 跑一遍, 然后循环跑 "AI 在干什么..." 
 *  - 一次显 3 个字, 走马灯式横向滚动
 *  - 点击 = 立即终止
 */
class AgentFloatingService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var statusText: TextView? = null
    private var scroller: HorizontalScrollView? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var marqueeRunnable: Runnable? = null
    @Volatile private var currentToolName: String = ""

    companion object {
        const val ACTION_START = "com.mbclaw.action.AGENT_START"
        const val ACTION_UPDATE = "com.mbclaw.action.AGENT_UPDATE"
        const val ACTION_STOP = "com.mbclaw.action.AGENT_STOP"
        const val ACTION_CANCEL_FROM_FLOAT = "com.mbclaw.action.CANCEL_FROM_FLOAT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TOOL = "tool"

        private const val NOTIF_CHANNEL = "mbclaw_agent_running"
        private const val NOTIF_ID = 7788

        fun start(ctx: Context, status: String) {
            val i = Intent(ctx, AgentFloatingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TEXT, status)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun update(ctx: Context, status: String, tool: String = "") {
            val i = Intent(ctx, AgentFloatingService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, status)
                putExtra(EXTRA_TOOL, tool)
            }
            ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, AgentFloatingService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val txt = intent.getStringExtra(EXTRA_TEXT) ?: "AI 运行中"
                startForeground(NOTIF_ID, buildNotification("MBclaw 启动中", ""))
                showFloating()
                startMarquee()
            }
            ACTION_UPDATE -> {
                val txt = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val tool = intent.getStringExtra(EXTRA_TOOL) ?: ""
                currentToolName = tool
                updateNotification(txt, tool)
            }
            ACTION_STOP -> {
                stopMarquee()
                removeFloating()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_CANCEL_FROM_FLOAT -> {
                sendBroadcast(Intent("com.mbclaw.action.USER_CANCEL_AGENT")
                    .setPackage(packageName))
                stopMarquee()
                removeFloating()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
                val ch = NotificationChannel(NOTIF_CHANNEL, "MBclaw 运行状态",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    description = "Agent 运行时常驻"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(text: String, tool: String): Notification {
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancelIntent = PendingIntent.getService(this, 1,
            Intent(this, AgentFloatingService::class.java).apply { action = ACTION_CANCEL_FROM_FLOAT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val displayText = if (tool.isNotBlank()) "MBclaw 正在调用: $tool"
                          else if (text.isNotBlank()) "MBclaw $text"
                          else "MBclaw 思考中..."

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("🤖 MBclaw 运行中")
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_delete, "终止", cancelIntent)
            .build()
    }

    private fun updateNotification(text: String, tool: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, tool))
    }

    /** 显示悬浮窗 - 加长版 */
    private fun showFloating() {
        if (floatingView != null) return
        if (!canDrawOverlays()) {
            // 有 root 时尝试自动授权
            try {
                val tier = com.mbclaw.root.agent.PermissionTier.get(this@AgentFloatingService)
                if (tier.hasRoot) {
                    tier.shellRoot("appops set --user 0 $packageName SYSTEM_ALERT_WINDOW allow; " +
                        "cmd appops set --user 0 $packageName SYSTEM_ALERT_WINDOW allow", timeoutMs = 5000)
                    Thread.sleep(500)
                }
            } catch (_: Exception) {}
            if (!canDrawOverlays()) {
                android.util.Log.w("MBclaw-Float", "悬浮窗权限被拒")
                try {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            this@AgentFloatingService,
                            "⚠️ 请在 设置→应用→MBclaw→悬浮窗 中手动开启",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (_: Exception) {}
                return
            }
        }

        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        // 4mm ≈ 4 * 160dp/inch * (1/25.4mm/inch) * density ≈ 4 * 6.3 = 25dp (高度增量)
        val widthPx = dp(180)      // ~ 2 倍宽
        val extraH = dp(16)        // 高度 +4mm ≈ 16dp
        val padH = dp(8)
        val padV = dp(10) + extraH / 2

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(0x59000000)   // 35% 透明黑
            setStroke(dp(1), 0x33FFFFFF)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            setPadding(padH, padV, padH, padV)
            gravity = Gravity.CENTER_VERTICAL
        }

        // ┌─ [⏸ 停止按钮] [走马灯] ─┐
        // 停止按钮 - 方形 [⏹] 100% 触发暂停
        val stopBtn = TextView(this).apply {
            text = "⏹"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(2), 0, dp(6), 0)
            val btnBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(0xCCFF3B30.toInt())   // 80% 不透明红, 更显眼
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            background = btnBg
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                startService(Intent(this@AgentFloatingService, AgentFloatingService::class.java)
                    .apply { action = ACTION_CANCEL_FROM_FLOAT })
            }
        }
        layout.addView(stopBtn)

        // ScrollView 做走马灯
        val sv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = TextView(this).apply {
            text = "AI 运行中  点击可终止  "
            textSize = 14f
            setTextColor(Color.WHITE)
            includeFontPadding = false
            setPadding(0, dp(2), 0, dp(2))
            setSingleLine(true)
            isHorizontalFadingEdgeEnabled = false
        }
        sv.addView(statusText)
        layout.addView(sv)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - widthPx) / 2   // 默认水平居中
            y = (dm.heightPixels * 0.66).toInt()
        }

        // ★ 拖动逻辑 — 按住非按钮区域拖动
        sv.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0; var initialY = 0
            var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        touchX = e.rawX; touchY = e.rawY
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = (initialX + (e.rawX - touchX)).toInt()
                        params.y = (initialY + (e.rawY - touchY)).toInt()
                        try { windowManager?.updateViewLayout(floatingView, params) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(layout, params)
            floatingView = layout
            scroller = sv
        } catch (e: Exception) {
            android.util.Log.e("MBclaw-Float", "悬浮窗显示失败: ${e.message}")
        }
    }

    /** 走马灯滚动播放 — 第一遍 "AI 运行中点击可终止", 之后循环 "MBclaw 在 <工具名>" */
    @Volatile private var introDone = false
    private fun startMarquee() {
        stopMarquee()
        introDone = false
        scheduleNextSegment()
    }

    private fun scheduleNextSegment() {
        val outer = Runnable { runOneSegment() }
        marqueeRunnable = outer
        handler.post(outer)
    }

    private fun runOneSegment() {
        val sv = scroller ?: return
        val tv = statusText ?: return
        val text = if (!introDone) "AI 运行中  点击可终止"
                   else "MBclaw 在 ${currentToolName.ifBlank { "思考" }}"
        // 头尾相接, 滚到中间再回 0
        tv.text = text + "      " + text
        tv.post {
            val total = tv.width
            val step = 4
            sv.scrollTo(0, 0)
            val anim = object : Runnable {
                var x = 0
                override fun run() {
                    if (floatingView == null) return
                    x += step
                    if (x >= total / 2) {
                        introDone = true
                        // 下一段
                        handler.postDelayed({ runOneSegment() }, 400)
                        return
                    }
                    sv.scrollTo(x, 0)
                    handler.postDelayed(this, 40)
                }
            }
            handler.post(anim)
        }
    }

    private fun stopMarquee() {
        marqueeRunnable?.let { handler.removeCallbacks(it) }
        marqueeRunnable = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun removeFloating() {
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            floatingView = null
            statusText = null
            scroller = null
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else true
    }

    override fun onDestroy() {
        stopMarquee()
        removeFloating()
        super.onDestroy()
    }
}
