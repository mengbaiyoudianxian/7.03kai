package com.mbclaw.nonroot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * MBclaw 无障碍服务 — 模拟用户点击
 *
 * 能力:
 *   - 查找 UI 元素 (by text/id/content-desc)
 *   - 模拟点击/长按/滑动
 *   - 全局手势 (GestureDescription API 24+)
 *   - 输入文本
 *   - 返回键/Home键/最近任务
 *
 * Root 加持:
 *   - INJECT_EVENTS 权限 → 比无障碍更快更底层
 *   - 无障碍作为兼容层，Root 权限做补充
 */

class MBclawAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MBclawAccessibilityService? = null
            private set
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            notificationTimeout = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: 声明可以执行手势
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES
            }
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件监听: 窗口变化、通知到达、按钮点击等
        // 可用于:
        //   1. 检测特定 App 弹窗 → 自动关闭广告
        //   2. 检测删除确认对话框 → 自动点击确认
        //   3. 检测安装界面 → 自动点击安装
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 窗口内容变化 → 检查是否有目标操作
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // 通知到达 → 检查是否需要拦截
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isRunning = false
        instance = null
        super.onDestroy()
    }

    // ── 公开 API — 供 Agent 调用 ──

    /** 按文本查找并点击 */
    fun clickByText(text: String, exact: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable && (!exact || node.text == text)) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    /** 按资源ID点击 */
    fun clickById(resourceId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    /** 按坐标点击 (需要手势API) */
    fun clickAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 长按 */
    fun longClickAt(x: Float, y: Float, durationMs: Long = 800): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 滑动 */
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 输入文本 (当前焦点) */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /** 按返回键 */
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 按Home键 */
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 最近任务 */
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** 获取屏幕尺寸 */
    fun getScreenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    /** 获取当前窗口中所有可点击元素 */
    fun getClickableElements(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<String>()
        collectClickable(root, result)
        return result
    }

    private fun collectClickable(node: AccessibilityNodeInfo, result: MutableList<String>) {
        if (node.isClickable) {
            val desc = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName,
                "(${node.className})"
            ).filter { it.isNotBlank() }.joinToString(" | ")
            result.add(desc)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickable(it, result) }
        }
    }
}
