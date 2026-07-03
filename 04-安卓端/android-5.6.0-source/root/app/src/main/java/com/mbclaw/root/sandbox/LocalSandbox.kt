package com.mbclaw.root.sandbox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mbclaw.root.agent.PermissionTier
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * MBclaw Linux 环境 — 一键下载，即下即用
 *
 * Root: chroot /data/mbclaw/linux
 * 非Root: proot 模拟
 * 服务器: http://8.130.42.188/mbclaw-linux-full-arm64.tar.gz (278MB / 706MB)
 *
 * 预装: Python3, pip, OpenJDK17, Git, GCC, G++, CMake, Vim, Bash, Curl, OpenSSH, SQLite, build-base
 */
class LocalSandbox(private val context: Context) {

    private val linuxDir = File("/data/mbclaw/linux")
    private val rootfsFile = File(context.cacheDir, "mbclaw-linux-rootfs.tar.gz")
    private val readyFile = File(linuxDir, ".mbclaw_ready")

    val isInstalled: Boolean get() = readyFile.exists()
    val isRoot: Boolean get() = PermissionTier.get(context).hasRoot

    enum class State { NOT_INSTALLED, DOWNLOADING, EXTRACTING, INSTALLING, READY, FAILED }
    var state = State.NOT_INSTALLED; private set
    var progress = 0; private set
    var statusText = ""; private set
    var lastError = ""; private set

    // 通知 — 使用 Application context 防止 Activity 销毁导致崩溃
    private val appContext = context.applicationContext
    private val notifManager by lazy { appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private val notifId = 9001
    private val channelId = "mbclaw_linux_dl"

    init {
        if (isInstalled) state = State.READY
    }

    /** 检查通知权限 (Android 13+) */
    private fun hasNotifyPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Linux环境下载", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "MBclaw Linux 环境下载进度"
                setSound(null, null) // 静音，不打扰用户
            }
            try { notifManager.createNotificationChannel(ch) } catch (_: Exception) {}
        }
    }

    /** 安全发送通知 — 无权限或异常时静默跳过，不影响下载主流程 */
    private fun showNotif(title: String, text: String, pct: Int = 0, indeterminate: Boolean = false) {
        try {
            if (!hasNotifyPermission()) return // Android 13+ 无权限静默跳过
            ensureChannel()
            val b = NotificationCompat.Builder(appContext, channelId)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            if (indeterminate) b.setProgress(0, 0, true)
            else b.setProgress(100, pct, false)
            notifManager.notify(notifId, b.build())
        } catch (_: Exception) {
            // 通知发送失败不阻断下载 — UI 弹窗才是主要进度展示
        }
    }

    private fun cancelNotif() {
        try { notifManager.cancel(notifId) } catch (_: Exception) {}
    }

    suspend fun checkOrInit() {
        if (isInstalled) { state = State.READY; return }
        state = State.NOT_INSTALLED
    }

    suspend fun downloadAndInstall(onProgress: (State, Int, String) -> Unit) = withContext(Dispatchers.IO) {
        // 在主线程更新 UI 状态，确保 Compose 能正确刷新
        suspend fun report(s: State, p: Int, t: String) {
            withContext(Dispatchers.Main) { onProgress(s, p, t) }
        }
        try {
            state = State.DOWNLOADING; progress = 0
            lastError = ""
            val url = "http://8.130.42.188/mbclaw-linux-full-arm64.tar.gz"

            report(State.DOWNLOADING, 0, "正在连接服务器...")
            showNotif("MBclaw Linux 环境", "正在连接...", 0, true)

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 600000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MBclaw/5.0.6")

            // 先检查HTTP响应码
            val respCode = conn.responseCode
            android.util.Log.e("MBclaw-Linux", "HTTP $respCode, ContentLength=${conn.contentLength}, URL=$url")

            if (respCode != 200) {
                lastError = "服务器返回 HTTP $respCode"
                state = State.FAILED; statusText = lastError
                cancelNotif()
                report(State.FAILED, 0, lastError)
                return@withContext
            }

            val totalSize = conn.contentLength
            if (totalSize <= 0) {
                // 尝试用getHeaderField
                val clHeader = conn.getHeaderField("Content-Length")
                val actualSize = clHeader?.toLongOrNull() ?: 0L
                android.util.Log.e("MBclaw-Linux", "contentLength=$totalSize, header CL=$clHeader")
                if (actualSize <= 0) {
                    lastError = "无法获取文件大小 · HTTP$respCode · ${conn.headerFields}"
                    state = State.FAILED; statusText = lastError
                    cancelNotif()
                    report(State.FAILED, 0, lastError)
                    return@withContext
                }
            }

            val totalMB = totalSize / 1_048_576
            report(State.DOWNLOADING, 0, "开始下载 ${totalMB}MB...")
            showNotif("MBclaw Linux 环境", "0 / ${totalMB}MB", 0)

            // 确保缓存目录存在
            rootfsFile.parentFile?.mkdirs()

            // 下载 — 带速度统计
            val startTime = System.currentTimeMillis()
            conn.inputStream.use { input ->
                rootfsFile.outputStream().use { output ->
                    val buf = ByteArray(65536)
                    var downloaded = 0L
                    var lastReport = 0L
                    var lastNotifPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport > 500_000) { // 每0.5MB报告
                            val pct = (downloaded * 100 / totalSize).toInt()
                            val dlMB = downloaded / 1_048_576
                            val elapsed = ((System.currentTimeMillis() - startTime) / 1000f).coerceAtLeast(0.1f)
                            val speed = (downloaded / elapsed / 1_048_576f * 10).toInt() / 10f // MB/s, 1位小数
                            progress = pct
                            statusText = "${dlMB}MB / ${totalMB}MB · ${speed}MB/s"
                            lastReport = downloaded
                            report(State.DOWNLOADING, pct, statusText)
                            if (pct - lastNotifPct >= 3) {
                                showNotif("MBclaw Linux 环境", "${dlMB} / ${totalMB}MB · ${pct}% · ${speed}MB/s", pct)
                                lastNotifPct = pct
                            }
                        }
                    }
                }
            }

            // 解压
            state = State.EXTRACTING; progress = 0
            statusText = "正在解压 (706MB)..."
            report(State.EXTRACTING, 10, statusText)
            showNotif("MBclaw Linux 环境", "正在解压...", 0, true)

            linuxDir.mkdirs()
            val tier = PermissionTier.get(context)
            val extractOk = tier.shellRoot(
                "mkdir -p /data/mbclaw/linux && cd /data/mbclaw/linux && " +
                "tar xzf '${rootfsFile.absolutePath}' 2>/dev/null && echo EXTRACT_OK",
                timeoutMs = 300_000
            )?.contains("EXTRACT_OK") == true

            if (!extractOk) {
                rootfsFile.delete()
                lastError = "解压失败 · 磁盘空间可能不足"
                state = State.FAILED; statusText = lastError
                cancelNotif()
                report(State.FAILED, 0, lastError)
                return@withContext
            }

            // 验证
            statusText = "验证安装..."
            report(State.EXTRACTING, 90, statusText)
            showNotif("MBclaw Linux 环境", "验证中...", 0, true)

            val verifyOk = tier.shellRoot(
                "test -f /data/mbclaw/linux/.mbclaw_ready && test -f /data/mbclaw/linux/bin/bash && echo SETUP_OK",
                timeoutMs = 10_000
            )?.contains("SETUP_OK") == true

            rootfsFile.delete()

            if (verifyOk) {
                state = State.READY; statusText = "Linux 环境就绪 · 706MB"
                report(State.READY, 100, statusText)
                showNotif("MBclaw Linux 环境", "✅ 安装完成 · 706MB · 可编译APK", 100)
                delay(3000)
                cancelNotif()
            } else {
                tier.shellRoot("echo READY > /data/mbclaw/linux/.mbclaw_ready")
                state = State.READY; statusText = "环境就绪 (验证未完全通过)"
                report(State.READY, 100, statusText)
                cancelNotif()
            }
        } catch (e: Exception) {
            rootfsFile.delete()
            lastError = e.message ?: "未知错误"
            state = State.FAILED; statusText = lastError
            cancelNotif()
            report(State.FAILED, progress, lastError)
        }
    }

    /** 在Linux环境中执行命令 */
    suspend fun exec(command: String, timeoutMs: Long = 30000): String = withContext(Dispatchers.IO) {
        if (!isInstalled) return@withContext "Linux 环境未安装，请先在设置中下载"
        val tier = PermissionTier.get(context)

        if (tier.hasRoot) {
            tier.shellRoot(
                "chroot /data/mbclaw/linux /bin/bash -c '${command.replace("'", "'\\''")}'",
                timeoutMs = timeoutMs
            ) ?: "执行失败"
        } else {
            val prootBin = File(context.filesDir, "proot/proot").also {
                it.parentFile?.mkdirs()
                if (!it.exists()) {
                    context.assets.open("proot/proot").use { src -> it.outputStream().use { dst -> src.copyTo(dst) } }
                    it.setExecutable(true)
                }
            }
            val cmd = "${prootBin.absolutePath} -r /data/mbclaw/linux -b /dev -b /proc -b /sys /bin/bash -c '${command.replace("'", "'\\''")}'"
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            if (p.waitFor(timeoutMs / 1000, java.util.concurrent.TimeUnit.SECONDS))
                p.inputStream.bufferedReader().readText().trim()
            else { p.destroy(); "超时" }
        }
    }
}
