package com.mbclaw.dev.agent

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.mbclaw.dev.service.MBclawAccessibilityService
import com.mbclaw.dev.service.ShizukuManager
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 权限分层 — 统一 fallback chain
 *
 * 用户最终诉求（2026-06-22 锁定）：
 *   系统 API  >  Root  >  ADB(Shizuku)  >  无障碍
 *
 * ★ v5.0.5: 修复云手机root误判
 *   - 对 /acct/.mci/mciu 路径单独做多步验证
 *   - 不再仅凭路径存在就判定root
 *   - 增加实际特权命令验证(pm list packages)
 */
class PermissionTier private constructor(private val context: Context) {

    enum class Tier { SYSTEM, ROOT, ADB, ACCESSIBILITY, NONE }

    private val shizuku by lazy { ShizukuManager(context).also { it.init() } }

    /** 真实可用的 root：短缓存(5秒) */
    @Volatile private var rootCache: Boolean? = null
    @Volatile private var rootCacheTime: Long = 0
    val hasRoot: Boolean get() {
        val now = System.currentTimeMillis()
        if (rootCache != null && now - rootCacheTime < 5000) return rootCache!!
        rootCache = probeRoot()
        rootCacheTime = now
        return rootCache!!
    }

    /** 强制刷新root检测缓存 */
    fun refreshRoot(): Boolean {
        rootCache = null
        return hasRoot
    }

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

    /** 执行特权命令。先试sh -c，失败再su -c。返回null=完全失败 */
    fun shellRoot(cmd: String, timeoutMs: Long = 5000): String? {
        if (!hasRoot) return null
        fun execOne(cmdArr: Array<String>): String? {
            return try {
                val pb = ProcessBuilder(*cmdArr)
                pb.redirectErrorStream(true) // ★ 合并stderr防死锁
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                try { p.destroyForcibly() } catch (_: Exception) {}
                out.trim().ifBlank { null }
            } catch (_: Exception) { null }
        }
        var result = execOne(arrayOf("sh", "-c", cmd))
        if (result != null) return result
        // 多路径su兜底
        for (suPath in listOf("/system/xbin/su", "/sbin/su", "/acct/.mci/mciu",
                             "/data/adb/magisk/su", "/data/adb/ksu/bin/su", "su")) {
            result = execOne(arrayOf(suPath, "-c", cmd))
            if (result != null) return result
        }
        return null
    }

    /** 通过 Shizuku 执行 adb 命令 */
    fun shellAdb(cmd: String): String? {
        if (!hasAdb) return null
        return try { shizuku.exec(cmd) } catch (_: Exception) { null }
    }

    inline fun <T> tryTiers(vararg order: Tier = arrayOf(Tier.SYSTEM, Tier.ROOT, Tier.ADB, Tier.ACCESSIBILITY),
                             block: (Tier) -> T?): Pair<Tier, T>? {
        for (t in order) {
            val ok = when (t) {
                Tier.SYSTEM        -> true
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

    // ──────────────────────────────────────────────
    // ★ v5.0.5: 修复云手机root误判
    // ──────────────────────────────────────────────
    private fun probeRoot(): Boolean {
        // ── 通用检测：标准su路径 ──
        val standardPaths = listOf(
            "su", "/system/xbin/su", "/sbin/su", "/system/bin/su",
            "/data/adb/magisk/su", "/data/adb/ksu/bin/su"
        )
        for (path in standardPaths) {
            if (tryRootPath(path, isCloudPhone = false)) return true
        }

        // ── 云手机专用路径 /acct/.mci/mciu: 需要更严格的验证 ──
        val cloudPath = "/acct/.mci/mciu"
        if (java.io.File(cloudPath).exists()) {
            // 云手机root需要三步验证，全部通过才算真root
            val step1: Boolean = tryRootPath(cloudPath, isCloudPhone = false)       // 读packages.list
            val step2: Boolean = tryPrivilegedCmd(cloudPath, "pm list packages 2>/dev/null | grep -c 'package:'")?.isNotBlank() == true // 执行pm
            val uidCheck: Boolean = tryPrivilegedCmd(cloudPath, "id 2>&1")?.contains("uid=0") == true // uid=0

            // ★ 至少2/3通过才判定有root（云手机的特殊情况：su通道可能有但不能做所有操作）
            val passed = listOf(step1, step2, uidCheck).count { it }
            if (passed >= 2) return true
        }

        // ── 方法3: 当前进程就是root ──
        try {
            val status = java.io.File("/proc/self/status").readText()
            if (status.lines().find { it.startsWith("Uid:") }?.contains("\t0\t") == true) {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pm list packages 2>/dev/null | head -1"))
                if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
                    if (out.contains("package:")) return true
                } else { p.destroy() }
            }
        } catch (_: Exception) {}

        return false
    }

    /** 单条su路径试探: 读取系统文件+id命令 */
    private fun tryRootPath(path: String, isCloudPhone: Boolean): Boolean {
        try {
            // 测试1: 读取只有root能读的文件
            val p = Runtime.getRuntime().exec(arrayOf(path, "-c", "head -1 /data/system/packages.list 2>/dev/null"))
            if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
                if (out.isNotBlank() && out.contains(" ")) return true
            } else { p.destroy() }
        } catch (_: Exception) {}
        // 回退: id命令
        try {
            val p = Runtime.getRuntime().exec(arrayOf(path, "-c", "id 2>&1"))
            if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
                if (out.contains("uid=0") || out.contains("root")) return true
            } else { p.destroy() }
        } catch (_: Exception) {}
        return false
    }

    /** 执行一个特权命令并返回输出，用于验证root是否真正可用 */
    private fun tryPrivilegedCmd(suPath: String, cmd: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(suPath, "-c", cmd))
            if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                val out = BufferedReader(InputStreamReader(p.inputStream)).readText().trim()
                if (out.isNotBlank()) out else null
            } else { p.destroy(); null }
        } catch (_: Exception) { null }
    }

    companion object {
        @Volatile private var instance: PermissionTier? = null
        fun get(context: Context): PermissionTier =
            instance ?: synchronized(this) {
                instance ?: PermissionTier(context.applicationContext).also { instance = it }
            }
    }
}
