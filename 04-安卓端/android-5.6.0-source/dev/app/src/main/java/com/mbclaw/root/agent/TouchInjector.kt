package com.mbclaw.dev.agent

import android.content.Context
import android.os.Build
import android.util.Log
import com.mbclaw.dev.service.MBclawAccessibilityService
import java.io.File

/**
 * TouchInjector — 多通道触摸注入器 (根治"触摸是摆设")
 *
 * 方法优先级:
 *   1. Root input tap (最快, 99% 设备可用)
 *   2. Root sendevent 直写 /dev/input (input 命令被 SELinux 拦截时用)
 *   3. AccessibilityService dispatchGesture (最后兜底)
 *
 * 设计参考:
 *   - Open-AutoGLM: ADB input tap (归一化坐标 0-1000)
 *   - KernelSU root: 可直接写 /dev/input/event* (绕过 SELinux)
 *
 * 关键: 每步执行后验证, 返回真实成功/失败
 */
object TouchInjector {

    private const val TAG = "MBclaw-Touch"
    @Volatile private var touchDevice: String? = null
    @Volatile private var maxX = 0
    @Volatile private var maxY = 0

    /** 初始化: 探测触摸设备 + 屏幕尺寸 (只跑一次) */
    fun init(tier: PermissionTier) {
        if (touchDevice != null) return
        if (!tier.hasRoot) return

        // 探测触摸输入设备
        val getevent = tier.shellRoot("getevent -p 2>/dev/null | grep -A5 'touch|fts|gt9xx|synaptics' | head -20")
        if (getevent != null) {
            // 提取设备路径
            val devMatch = Regex("""(/dev/input/event\d+)""").find(getevent)
            if (devMatch != null) {
                touchDevice = devMatch.groupValues[1]
                // 提取最大坐标
                val maxXMatch = Regex("""max\s+(\d+)""").findAll(getevent).toList()
                if (maxXMatch.size >= 2) {
                    maxX = maxXMatch[0].groupValues[1].toIntOrNull() ?: 0
                    maxY = maxXMatch[1].groupValues[1].toIntOrNull() ?: 0
                }
                Log.i(TAG, "触摸设备: $touchDevice maxX=$maxX maxY=$maxY")
            }
        }

        // 备用: 直接 ls /dev/input/
        if (touchDevice == null) {
            val ls = tier.shellRoot("ls /dev/input/event* 2>/dev/null")
            if (ls != null) {
                for (dev in listOf("/dev/input/event2", "/dev/input/event1", "/dev/input/event0")) {
                    if (ls.contains(dev)) {
                        touchDevice = dev
                        break
                    }
                }
            }
        }

        // 获取屏幕物理分辨率
        if (maxX == 0 || maxY == 0) {
            val wm = tier.shellRoot("wm size 2>/dev/null")
            if (wm != null) {
                val physMatch = Regex("""(\d+)x(\d+)""").find(wm)
                if (physMatch != null) {
                    maxX = physMatch.groupValues[1].toIntOrNull() ?: 0
                    maxY = physMatch.groupValues[2].toIntOrNull() ?: 0
                }
            }
        }
    }

    /** 核心: 点击物理坐标 (x, y) — 返回是否成功 */
    fun tap(ctx: Context, x: Int, y: Int): Boolean {
        val tier = PermissionTier.get(ctx)

        // 方法1: Root input tap (最快)
        if (tier.hasRoot) {
            // 直接 input tap — 大部分设备有效
            val r1 = tier.shellRoot("input tap $x $y && echo OK", timeoutMs = 5000)
            if (r1 != null && r1.contains("OK")) {
                Log.d(TAG, "✅ tap($x,$y) via input")
                return true
            }
            Log.w(TAG, "input tap 失败: $r1, 尝试 sendevent")

            // 方法2: sendevent 直写 (绕过 SELinux)
            if (touchDevice != null) {
                val r2 = injectViaSendevent(tier, x, y)
                if (r2) {
                    Log.d(TAG, "✅ tap($x,$y) via sendevent")
                    return true
                }
            }
        }

        // 方法3: 无障碍手势 (最后兜底)
        val svc = MBclawAccessibilityService.instance
        if (svc != null) {
            val r3 = svc.clickAt(x.toFloat(), y.toFloat())
            if (r3) {
                Log.d(TAG, "✅ tap($x,$y) via Accessibility")
                return true
            }
        }

        Log.e(TAG, "❌ tap($x,$y) 全部方法失败")
        return false
    }

    /** 长按 */
    fun longPress(ctx: Context, x: Int, y: Int, durationMs: Long = 800): Boolean {
        val tier = PermissionTier.get(ctx)
        if (tier.hasRoot) {
            val r = tier.shellRoot("input swipe $x $y $x $y $durationMs && echo OK", timeoutMs = 5000)
            if (r != null && r.contains("OK")) return true
        }
        val svc = MBclawAccessibilityService.instance
        return svc?.longClickAt(x.toFloat(), y.toFloat(), durationMs) == true
    }

    /** 滑动 */
    fun swipe(ctx: Context, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val tier = PermissionTier.get(ctx)
        if (tier.hasRoot) {
            val r = tier.shellRoot("input swipe $x1 $y1 $x2 $y2 $durationMs && echo OK", timeoutMs = 5000)
            if (r != null && r.contains("OK")) return true
        }
        val svc = MBclawAccessibilityService.instance
        return svc?.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs) == true
    }

    /** 输入文本 (root 直写, 不需要 ADB Keyboard) */
    fun inputText(ctx: Context, text: String): Boolean {
        val tier = PermissionTier.get(ctx)
        val escaped = text.replace("'", "'\\''").replace("\"", "\\\"")
        if (tier.hasRoot) {
            val r = tier.shellRoot("input text '$escaped' && echo OK", timeoutMs = 5000)
            if (r != null && r.contains("OK")) return true
        }
        val svc = MBclawAccessibilityService.instance
        return svc?.inputText(text) == true
    }

    /** 按键 */
    fun keyEvent(ctx: Context, keyCode: Int): Boolean {
        val tier = PermissionTier.get(ctx)
        if (tier.hasRoot) {
            val r = tier.shellRoot("input keyevent $keyCode && echo OK", timeoutMs = 5000)
            if (r != null && r.contains("OK")) return true
        }
        val svc = MBclawAccessibilityService.instance
        val globalAction = when (keyCode) {
            4 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            3 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            187 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> -1
        }
        return if (globalAction >= 0) svc?.performGlobalAction(globalAction) == true else false
    }

    // ── 内部: sendevent 注入 ──

    /**
     * 通过 sendevent 直接写入触摸事件到 /dev/input/eventX
     *
     * 为什么: input tap 命令在某些 SELinux 策略下会被拦截,
     * 但 root 可以直接写 /dev/input 设备节点
     *
     * 参考: Linux input subsystem — ABS_MT_POSITION_X/Y + BTN_TOUCH
     */
    private fun injectViaSendevent(tier: PermissionTier, x: Int, y: Int): Boolean {
        val dev = touchDevice ?: return false

        // 将屏幕坐标映射到触摸设备坐标
        val tx = if (maxX > 0) (x.toLong() * maxX / (maxX.coerceAtLeast(1))).toInt() else x
        val ty = if (maxY > 0) (y.toLong() * maxY / (maxY.coerceAtLeast(1))).toInt() else y

        // ABS_MT_TRACKING_ID = 57, ABS_MT_POSITION_X = 53, ABS_MT_POSITION_Y = 54
        // SYN_REPORT = 0 0, BTN_TOUCH = 330
        val script = buildString {
            // 按下
            append("sendevent $dev 3 57 0 && ")           // ABS_MT_TRACKING_ID = 0
            append("sendevent $dev 3 53 $tx && ")          // ABS_MT_POSITION_X
            append("sendevent $dev 3 54 $ty && ")          // ABS_MT_POSITION_Y
            append("sendevent $dev 1 330 1 && ")           // BTN_TOUCH = DOWN
            append("sendevent $dev 0 0 0 && ")             // SYN_REPORT
            // 释放
            append("sendevent $dev 3 57 -1 && ")           // ABS_MT_TRACKING_ID = -1
            append("sendevent $dev 1 330 0 && ")           // BTN_TOUCH = UP
            append("sendevent $dev 0 0 0 && ")             // SYN_REPORT
            append("echo OK")
        }

        val result = tier.shellRoot(script, timeoutMs = 3000)
        return result != null && result.contains("OK")
    }

    /** 检测触摸是否真的可用 (连通性测试) */
    fun selfTest(ctx: Context): String {
        val tier = PermissionTier.get(ctx)
        init(tier)

        val sb = StringBuilder("触摸注入自检:\n")
        sb.append("  Root: ${if (tier.hasRoot) "✅" else "❌"}\n")
        sb.append("  触摸设备: ${touchDevice ?: "❌ 未探测到"}\n")
        sb.append("  屏幕分辨率: ${maxX}x${maxY}\n")

        if (tier.hasRoot) {
            // 测试 input 命令
            val inputTest = tier.shellRoot("which input && input --help 2>&1 | head -2")
            sb.append("  input 命令: ${if (inputTest != null && inputTest.contains("Usage")) "✅" else "⚠️ ${inputTest?.take(60)}"}\n")

            // 测试 sendevent
            val seTest = tier.shellRoot("which sendevent")
            sb.append("  sendevent: ${if (seTest != null) "✅" else "❌"}\n")

            // 测试无障碍
            val svc = MBclawAccessibilityService.instance
            sb.append("  无障碍服务: ${if (svc != null) "✅" else "❌"}\n")
        }

        // 尝试一次真实的点击测试（屏幕中心，安全位置）
        if (tier.hasRoot && maxX > 0 && maxY > 0) {
            val centerX = maxX / 2
            val centerY = maxY / 2
            val testTap = tap(ctx, centerX, centerY)
            sb.append("  实测点击($centerX,$centerY): ${if (testTap) "✅ 成功" else "❌ 失败"}\n")
        }

        return sb.toString()
    }
}
