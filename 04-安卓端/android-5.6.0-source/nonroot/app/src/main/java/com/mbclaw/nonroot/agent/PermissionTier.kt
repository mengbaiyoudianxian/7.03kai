package com.mbclaw.nonroot.agent

import android.content.Context
import android.os.Build
import com.mbclaw.nonroot.service.MBclawAccessibilityService
import com.mbclaw.nonroot.service.ShizukuManager
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 权限分层 — 统一 fallback chain
 *
 * 用户最终诉求（2026-06-22 锁定）：
 *   系统 API  >  Root  >  ADB(Shizuku)  >  无障碍
 *
 * 注意：原代码 ShizukuManager 用于 ADB 通道（Shizuku 本质是 adb shell daemon）。
 *
 * 每个工具都应：
 *   PermissionTier.run(context) { tier ->
 *       when (tier) {
 *           Tier.SYSTEM       -> ...系统SDK直调
 *           Tier.ROOT         -> shellRoot(...)
 *           Tier.ADB          -> shellAdb(...)
 *           Tier.ACCESSIBILITY-> svc?.xxx()
 *       }
 *   }
 *
 * 调用方负责回报 success(Boolean)，框架自动尝试下一档。
 */
class PermissionTier private constructor(private val context: Context) {

    enum class Tier { SYSTEM, ROOT, ADB, ACCESSIBILITY, NONE }

    private val shizuku by lazy { ShizukuManager(context).also { it.init() } }

    /** 真实可用的 root：必须 su 授权且能返回标记 */
    val hasRoot: Boolean = false

    /** Shizuku/ADB 通道是否就绪 */
    val hasAdb: Boolean get() = shizuku.isReady()

    /** 无障碍服务是否绑定 */
    val hasAccessibility: Boolean get() = MBclawAccessibilityService.instance != null

    /** 当前可用的最高权限层 */
    fun bestTier(): Tier = when {
        hasRoot          -> Tier.ROOT
        hasAdb           -> Tier.ADB
        hasAccessibility -> Tier.ACCESSIBILITY
        else             -> Tier.NONE
    }

    /** 执行 su 命令，失败返回 null。超时时返回已捕获的部分输出 */
    fun shellRoot(cmd: String, timeoutMs: Long = 5000): String? {
        if (!hasRoot) return null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            // BugF修复: 超时时也返回已读取的输出, 不丢结果
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val ch = reader.read()
                    if (ch == -1) break
                    sb.append(ch.toChar())
                } else {
                    // 检查进程是否已结束
                    try { p.exitValue(); break } catch (_: IllegalThreadStateException) {}
                    Thread.sleep(10)
                }
            }
            // 超时则强制 kill
            try { p.exitValue() } catch (_: IllegalThreadStateException) { p.destroy() }
            val result = sb.toString().trim()
            if (result.isBlank()) null else result
        } catch (_: Exception) { null }
    }

    /** 通过 Shizuku 执行 adb 命令 */
    fun shellAdb(cmd: String): String? {
        if (!hasAdb) return null
        return try { shizuku.exec(cmd) } catch (_: Exception) { null }
    }

    /**
     * 按层级尝试执行。block 返回 null 表示该层级未生效，自动落到下一层。
     */
    inline fun <T> tryTiers(vararg order: Tier = arrayOf(Tier.SYSTEM, Tier.ROOT, Tier.ADB, Tier.ACCESSIBILITY),
                             block: (Tier) -> T?): Pair<Tier, T>? {
        for (t in order) {
            val ok = when (t) {
                Tier.SYSTEM        -> true                       // 系统 API 总是可尝试
                Tier.ROOT          -> hasRoot
                Tier.ADB           -> hasAdb
                Tier.ACCESSIBILITY -> hasAccessibility
                Tier.NONE          -> false
            }
            if (!ok) continue
            val r = block(t) ?: continue
            return t to r
        }
        return null
    }

    private fun probeRoot(): Boolean {
        // 必须真正能拿到 su shell（被授权），仅检测文件存在不算
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val ok = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) { p.destroy(); return false }
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            out.contains("uid=0") || out.contains("root")
        } catch (_: Exception) { false }
    }

    companion object {
        @Volatile private var instance: PermissionTier? = null
        fun get(context: Context): PermissionTier =
            instance ?: synchronized(this) {
                instance ?: PermissionTier(context.applicationContext).also { instance = it }
            }
    }
}
