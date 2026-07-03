package com.mbclaw.root.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mbclaw.root.agent.MBclawAgent
import com.mbclaw.root.MBclawRootApp

/**
 * 通知监听 — 读取所有App通知，AI分析后主动介入
 *
 * Root 版额外能力:
 *   - 读取通知内容 (所有App)
 *   - 拦截/静默特定通知
 *   - 自动回复 (如: 微信消息自动回复)
 *
 * 使用场景:
 *   检测到用户在重复删除QQ好友 → 主动建议批量处理
 *   检测到银行验证码 → 自动提取并填入
 */
class NotificationMonitor : NotificationListenerService() {

    companion object {
        var instance: NotificationMonitor? = null
            private set
        var isRunning = false
            private set

        // 需要监控的包名白名单
        val MONITORED_PACKAGES = setOf(
            "com.tencent.mobileqq",     // QQ
            "com.tencent.mm",            // 微信
            "com.eg.android.AlipayGphone", // 支付宝
            "com.android.mms",           // 短信
            "com.android.phone",         // 电话
            "com.xiaomi.smarthome",      // 米家
        )
    }

    private val agent by lazy { MBclawAgent(application as android.app.Application) }
    private val recentNotifications = mutableMapOf<String, MutableList<String>>() // pkg → texts

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 获取当前通知栏所有通知
        activeNotifications?.forEach { sbn ->
            processNotification(sbn)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        processNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知被移除 (用户划掉或App取消)
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        super.onDestroy()
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val fullText = "$title: $text"

        if (fullText.isBlank()) return

        // 记录到本地
        recentNotifications.getOrPut(pkg) { mutableListOf() }.add(fullText)
        // 限制缓存大小
        val list = recentNotifications[pkg] ?: return
        if (list.size > 50) list.removeAt(0)

        // ── 分析触发 ──
        analyzeAndAct(pkg, title, text, list)
    }

    private fun analyzeAndAct(pkg: String, title: String, text: String, history: List<String>) {
        // 1. 短信验证码 → 自动提取
        if (pkg == "com.android.mms" && text.contains("验证码")) {
            val code = Regex("""\d{4,6}""").find(text)?.value
            if (code != null) {
                agent.db.saveMemory("last_sms_code", code, "notification_sms")
                // 可通过无障碍自动填入当前焦点输入框
            }
        }

        // 2. 重复删除操作检测
        if (pkg == "com.tencent.mobileqq" || pkg == "com.tencent.mm") {
            val deletePatterns = listOf("删除", "移除", "确认删除")
            val deleteCount = history.takeLast(20).count { n ->
                deletePatterns.any { n.contains(it) }
            }
            if (deleteCount >= 3 && agent.settings.isConfigured()) {
                // 触发主动建议 (通过 AgentService 发送通知)
                val intent = android.content.Intent("com.mbclaw.PROACTIVE_SUGGESTION").apply {
                    putExtra("message", "检测到你在重复删除联系人，需要我帮你一键批量删除吗？")
                    putExtra("action", "batch_delete_contacts")
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        // 3. 银行/支付通知 → 记录消费
        if (pkg == "com.eg.android.AlipayGphone") {
            val amount = Regex("""[¥￥]\s*(\d+\.?\d*)""").find(text)?.groupValues?.get(1)
            if (amount != null) {
                agent.db.saveMemory("expense_${System.currentTimeMillis()}", "$title: ¥$amount", "notification_payment")
            }
        }
    }

    /** 获取特定 App 的最近通知 */
    fun getRecentForPackage(pkg: String): List<String> {
        return recentNotifications[pkg]?.toList() ?: emptyList()
    }

    /** 清除 App 的所有缓存通知 */
    fun clearForPackage(pkg: String) {
        recentNotifications.remove(pkg)
    }
}
