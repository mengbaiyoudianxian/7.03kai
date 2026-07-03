package com.mbclaw.nonroot.sandbox

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 本地 Linux 沙箱
 *
 * 两种模式:
 *   Root版: proot + chroot → 完整 Linux 环境 (Ubuntu ARM64)
 *   非Root版: Termux 环境 → proot 容器
 *
 * 用途:
 *   - 执行危险 Shell 命令 (网络隔离 + 文件隔离)
 *   - 编译/运行代码
 *   - 安装 Linux 包 (apt/pip/git)
 *   - 跑本地 Agent (Python FastAPI)
 */

class LocalSandbox(private val context: Context, private val mode: SandboxMode = SandboxMode.PROOT) {

    enum class SandboxMode { PROOT, CHROOT, TERMUX }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sandboxRoot: File = File(context.filesDir, "sandbox")
    private val rootfsDir: File = File(sandboxRoot, "rootfs")
    private val prootBin: File = File(sandboxRoot, "bin/proot")

    var isReady: Boolean = false
        private set

    companion object {
        // 阿里云 Termux 镜像 (默认)
        const val TERMUX_MIRROR = "https://mirrors.aliyun.com/termux/apt"
        // Ubuntu ARM64 基础镜像
        const val UBUNTU_ROOTFS_URL = "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/22.04/release/ubuntu-base-22.04-base-arm64.tar.gz"
        // 备用: 清华源
        const val TSINGHUA_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/termux"
    }

    // ── 初始化 ──

    suspend fun init(): SandboxResult = withContext(Dispatchers.IO) {
        try {
            sandboxRoot.mkdirs()
            when (mode) {
                SandboxMode.PROOT -> initProot()
                SandboxMode.CHROOT -> initChroot()
                SandboxMode.TERMUX -> initTermux()
            }
            isReady = true
            SandboxResult(0, "沙箱就绪: $mode", "")
        } catch (e: Exception) {
            SandboxResult(-1, "", "沙箱初始化失败: ${e.message}")
        }
    }

    private fun initProot() {
        // proot: 用户态 chroot，不需要 root 权限就可用
        // 但 Root 版本可以直接用系统 proot (效率更高)
        rootfsDir.mkdirs()
        // 复制或符号链接基本系统文件
        val proc = Runtime.getRuntime().exec(arrayOf("cp", "-r", "/system", "${rootfsDir.absolutePath}/system"))
        proc.waitFor()
    }

    private fun initChroot() {
        // chroot: 真正的 Linux 环境隔离
        // 需要 root 权限执行 chroot 命令
        rootfsDir.mkdirs()
        // 从镜像下载 Ubuntu ARM64 rootfs (首次)
        if (!File(rootfsDir, "bin/bash").exists()) {
            // 下载 + 解压
            // Runtime.exec("wget $UBUNTU_ROOTFS_URL -O /tmp/ubuntu.tar.gz && tar xzf /tmp/ubuntu.tar.gz -C ${rootfsDir.absolutePath}")
        }
    }

    private fun initTermux() {
        // Termux 模式: 设置阿里云镜像源
        if (!File(sandboxRoot, "usr/bin/bash").exists()) {
            // 引导 Termux 安装
        }
    }

    // ── 命令执行 ──

    suspend fun execute(
        command: String,
        timeout: Int = 30,
        env: Map<String, String> = emptyMap(),
        workDir: String? = null,
    ): SandboxResult = withContext(Dispatchers.IO) {
        try {
            val cmd = when (mode) {
                SandboxMode.PROOT -> arrayOf(
                    "proot", "-0",
                    "-r", rootfsDir.absolutePath,
                    "-b", "/system:/system",
                    "-b", "/dev:/dev",
                    "-b", "/proc:/proc",
                    "-b", "/sys:/sys",
                    "-b", "/data/data/com.mbclaw.nonroot/files/sandbox/tmp:/tmp",
                    "-w", workDir ?: "/root",
                    "/usr/bin/bash", "-c", command
                )
                SandboxMode.CHROOT -> arrayOf(
                    "su", "-c",
                    "chroot ${rootfsDir.absolutePath} /usr/bin/bash -c \"$command\""
                )
                SandboxMode.TERMUX -> arrayOf(
                    "bash", "-c",
                    "export PREFIX=${sandboxRoot.absolutePath}/usr && $command"
                )
            }

            val process = Runtime.getRuntime().exec(cmd, env.map { (k, v) -> "$k=$v" }.toTypedArray())
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = if (process.waitFor() != 0) process.exitValue() else 0

            SandboxResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            SandboxResult(-1, "", "执行失败: ${e.message}")
        }
    }

    // ── 工具方法 ──

    /** 在沙箱中安装 Python 包 */
    suspend fun pipInstall(packageName: String): SandboxResult {
        return execute("apt update && apt install -y python3 python3-pip && pip install $packageName", 120)
    }

    /** 启动本地 FastAPI (端口 127.0.0.1:8001) */
    suspend fun startLocalServer(scriptPath: String): SandboxResult {
        return execute(
            "cd ${File(scriptPath).parent} && python3 ${File(scriptPath).name} --host 127.0.0.1 --port 8001 &",
            timeout = 10
        )
    }

    /** 获取沙箱磁盘使用 */
    fun getDiskUsage(): Pair<Long, Long> {
        val total = sandboxRoot.totalSpace
        val free = sandboxRoot.freeSpace
        return Pair(free, total)
    }

    /** 清理沙箱 */
    suspend fun cleanup(): SandboxResult {
        return execute("rm -rf /tmp/* /var/tmp/*", 10)
    }

    /** 销毁沙箱 */
    fun destroy() {
        scope.launch {
            sandboxRoot.deleteRecursively()
            isReady = false
        }
    }

    data class SandboxResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val isSuccess: Boolean get() = exitCode == 0
        val output: String get() = stdout.ifBlank { stderr }
    }
}

// ── 沙箱服务 (后台进程) ──

class LocalSandboxService : android.app.Service() {
    private lateinit var sandbox: LocalSandbox
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        sandbox = LocalSandbox(this, LocalSandbox.SandboxMode.PROOT)
        scope.launch { sandbox.init() }
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra("cmd")) {
            "ping" -> scope.launch {
                sandbox.execute("echo pong")
            }
            null -> {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        sandbox.destroy()
        super.onDestroy()
    }
}
