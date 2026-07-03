package com.mbclaw.nonroot.agent

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * SafeOps — 危险操作前自动备份，保留最近 3 份循环
 *
 * 设计：
 *  • 备份目录:
 *      /sdcard/MBclaw/backup/apps/    — 应用 APK + data
 *      /sdcard/MBclaw/backup/files/   — 文件
 *      /sdcard/MBclaw/backup/configs/ — 系统配置文件（settings/）
 *  • 规则：
 *      默认保留最近 3 份（FIFO 循环）
 *      单份 > 1G → 拒绝备份
 *      剩余空间 < 10G → 退化为只保留 1 份
 *  • 危险等级：
 *      LOW    - 普通应用、用户文件 → 静默备份 + 静默执行
 *      MEDIUM - 系统应用、重要配置 → 弹窗确认（agent confirm 字段）
 *      HIGH   - /system /data/system → 必须 confirm=true
 */
object SafeOps {

    enum class Risk { LOW, MEDIUM, HIGH }

    private const val BACKUP_ROOT = "/sdcard/MBclaw/backup"
    private const val MAX_BACKUPS = 3
    private const val MAX_SINGLE_BYTES = 1024L * 1024 * 1024       // 1G
    private const val MIN_FREE_BYTES  = 10L * 1024 * 1024 * 1024   // 10G

    private val tier = lazy<PermissionTier?> { null }
    private fun T(ctx: Context) = PermissionTier.get(ctx)

    /** 判断应用是否系统应用（高风险） */
    fun appRisk(ctx: Context, pkg: String): Risk = try {
        val info = ctx.packageManager.getApplicationInfo(pkg, 0)
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) Risk.HIGH else Risk.LOW
    } catch (_: Exception) { Risk.LOW }

    /** 判断文件路径风险 */
    fun pathRisk(path: String): Risk = when {
        path.startsWith("/system") || path.startsWith("/vendor") ||
        path.startsWith("/data/system") || path.startsWith("/data/adb") ||
        path.startsWith("/proc") || path.startsWith("/sys") -> Risk.HIGH
        path.startsWith("/data/data") || path.startsWith("/data/local") ||
        path.endsWith(".conf") || path.endsWith(".xml") -> Risk.MEDIUM
        else -> Risk.LOW
    }

    /** 检查空间：返回应保留的最大份数（10G 以下 → 1，否则 3） */
    private fun maxBackups(): Int {
        val free = File("/sdcard").usableSpace
        return if (free < MIN_FREE_BYTES) 1 else MAX_BACKUPS
    }

    /** 单个 backup 子目录的循环清理 */
    private fun rotate(dir: File) {
        val list = (dir.listFiles() ?: return)
            .filter { it.isDirectory || it.name.endsWith(".bak") || it.name.endsWith(".apk") || it.name.endsWith(".tar.gz") }
            .sortedByDescending { it.lastModified() }
        val keep = maxBackups()
        list.drop(keep).forEach { it.deleteRecursively() }
    }

    /** 备份应用（APK + data 目录） */
    fun backupApp(ctx: Context, pkg: String): Pair<Boolean, String> {
        val t = T(ctx)
        if (!t.hasRoot) return false to "需 Root"
        val dir = File("$BACKUP_ROOT/apps").also { it.mkdirs() }
        val ts = System.currentTimeMillis()
        val sub = File(dir, "${pkg}_$ts").also { it.mkdirs() }

        // 1. 找 APK 路径
        val apkPath = t.shellRoot("pm path $pkg | head -1 | sed 's/package://'")?.trim()
        if (apkPath.isNullOrBlank()) return false to "未找到 APK 路径"

        // 2. 检查 APK 大小
        val sizeStr = t.shellRoot("stat -c%s '$apkPath' 2>/dev/null") ?: "0"
        val size = sizeStr.trim().toLongOrNull() ?: 0
        if (size > MAX_SINGLE_BYTES) return false to "APK > 1G，已跳过备份"

        // 3. 拷贝
        t.shellRoot("cp -p '$apkPath' '${sub.absolutePath}/base.apk' && chmod 644 '${sub.absolutePath}/base.apk'")
        // 4. data 目录 tar.gz（可能很大，单独限制）
        val dataSize = t.shellRoot("du -sb /data/data/$pkg 2>/dev/null | cut -f1")?.trim()?.toLongOrNull() ?: 0
        if (dataSize in 1..MAX_SINGLE_BYTES) {
            t.shellRoot("cd /data/data && tar czf '${sub.absolutePath}/data.tar.gz' '$pkg' 2>/dev/null")
        }
        rotate(dir)
        return true to "已备份 → ${sub.name}"
    }

    /** 备份文件 */
    fun backupFile(ctx: Context, path: String): Pair<Boolean, String> {
        val t = T(ctx)
        val f = File(path)
        val size = if (t.hasRoot) {
            t.shellRoot("stat -c%s '$path' 2>/dev/null")?.trim()?.toLongOrNull() ?: -1
        } else f.length()
        if (size < 0) return false to "文件不存在"
        if (size > MAX_SINGLE_BYTES) return false to "文件 > 1G，已跳过备份"

        val dir = File("$BACKUP_ROOT/files").also { it.mkdirs() }
        val ts = System.currentTimeMillis()
        val safeName = f.name.replace('/', '_').replace(' ', '_')
        val dst = File(dir, "${ts}_${safeName}")
        return if (t.hasRoot) {
            val ok = t.shellRoot("cp -p '$path' '${dst.absolutePath}' && echo OK") ?: ""
            if (ok.contains("OK")) {
                rotate(dir)
                true to "备份成功 → ${dst.name}"
            } else false to "拷贝失败"
        } else {
            try { f.copyTo(dst, true); rotate(dir); true to "备份成功" }
            catch (e: Exception) { false to "拷贝失败: ${e.message}" }
        }
    }

    /** 备份配置文件（如 settings put 前的 dump） */
    fun backupConfig(ctx: Context, label: String, dump: String): String {
        val dir = File("$BACKUP_ROOT/configs").also { it.mkdirs() }
        val ts = System.currentTimeMillis()
        val safe = label.replace('/', '_').replace(' ', '_').take(40)
        val f = File(dir, "${ts}_${safe}.bak")
        try { f.writeText(dump) } catch (_: Exception) {}
        rotate(dir)
        return f.absolutePath
    }

    /** 列出备份 */
    fun listBackups(kind: String): List<File> {
        val dir = File("$BACKUP_ROOT/$kind")
        return (dir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }
    }
}
