package com.mbclaw.nonroot.service

import android.content.Context
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地 FastAPI 服务 — Termux 环境运行
 *
 * 用途:
 *   - 在本地跑 MBclaw-Lite 后端 (FastAPI)
 *   - 127.0.0.1:8001 本地回环, 无网络延迟
 *   - 全功能: 记忆/搜索/Agent/分类/双Key
 *
 * 依赖: Termux 已安装 + Python + pip + MBclaw-Lite 代码
 */
class LocalFastAPI(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val termuxHome = File("/data/data/com.termux/files/home")
    private val mbclawDir = File(termuxHome, "MBclaw-Lite")

    @Volatile var isRunning: Boolean = false; private set
    @Volatile var serverPort: Int = 8001; private set

    // ── 初始化 ──

    suspend fun setup(): SetupResult = withContext(Dispatchers.IO) {
        try {
            // 1. 检查 Termux
            val termuxInstalled = try {
                context.packageManager.getPackageInfo("com.termux", 0); true
            } catch (_: Exception) { false }

            if (!termuxInstalled) return@withContext SetupResult(false, "请先安装 Termux", "https://f-droid.org/packages/com.termux/")

            // 2. 拉取/更新 MBclaw-Lite 代码
            if (!mbclawDir.exists()) {
                val cloneResult = execTermux("cd ~ && git clone https://github.com/mengbaiyoudianxian/MBclaw-Lite.git")
                if (!cloneResult.contains("Cloning")) return@withContext SetupResult(false, "代码拉取失败", cloneResult)
            }

            // 3. 安装 Python 依赖
            val pipResult = execTermux("cd ~/MBclaw-Lite && pip install -r requirements.txt 2>&1 | tail -5")
            if (pipResult.contains("ERROR")) return@withContext SetupResult(false, "依赖安装失败", pipResult)

            SetupResult(true, "环境就绪", pipResult)
        } catch (e: Exception) {
            SetupResult(false, "初始化失败: ${e.message}", "")
        }
    }

    // ── 启动/停止 ──

    suspend fun start(port: Int = 8001): StartResult = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext StartResult(false, "已在运行", 0)
        serverPort = port

        try {
            // 启动 FastAPI
            val cmd = "cd ~/MBclaw-Lite && nohup uvicorn app.main:app --host 127.0.0.1 --port $port > /tmp/mbclaw-server.log 2>&1 & echo \$!"
            val pid = execTermux(cmd).trim()
            if (pid.isBlank() || pid == "0") return@withContext StartResult(false, "启动失败", 0)

            // 等待服务就绪
            delay(2000)
            var retries = 0
            while (retries < 10) {
                if (checkHealth(port)) {
                    isRunning = true
                    return@withContext StartResult(true, "FastAPI 已启动", pid.toIntOrNull() ?: 0)
                }
                delay(1000); retries++
            }
            StartResult(false, "服务启动超时", pid.toIntOrNull() ?: 0)
        } catch (e: Exception) {
            StartResult(false, "启动失败: ${e.message}", 0)
        }
    }

    fun stop() {
        scope.launch {
            execTermux("pkill -f 'uvicorn app.main:app' 2>/dev/null; echo stopped")
            isRunning = false
        }
    }

    // ── 健康检查 ──

    private fun checkHealth(port: Int): Boolean {
        return try {
            val url = URL("http://127.0.0.1:$port/health/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            conn.responseCode == 200
        } catch (_: Exception) { false }
    }

    fun getHealthUrl(): String = "http://127.0.0.1:$serverPort/health/health"
    fun getApiUrl(): String = "http://127.0.0.1:$serverPort"

    // ── 工具 ──

    private fun execTermux(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "/data/data/com.termux/files/usr/bin/bash", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            process.waitFor()
            stdout.ifBlank { stderr }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    data class SetupResult(val success: Boolean, val message: String, val detail: String = "")
    data class StartResult(val success: Boolean, val message: String, val pid: Int)
}
