package com.mbclaw.root.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPOutputStream

/**
 * 🐾 小爪远程控制中心 — Kotlin原生版
 *
 * 完全内置在 MBclaw APK 中，不依赖 Python/Flask/Termux。
 * 功能比 phone-remote.py 更强：Android API 直调、无障碍手势、屏幕标定、设备信息原生获取。
 *
 * 端点:
 *   系统: /info /battery /settings /processes /kill /uptime
 *   屏幕: /screenshot /screenrecord
 *   控制: /tap /swipe /type /key /input /back /home /recents
 *   应用: /apps /app_info /start /stop /install /uninstall
 *   文件: /ls /cat /find /tree /du /stats /download /upload
 *   命令: /shell /su
 *   网络: /wifi /netstat /ping
 *   媒体: /photos /cameras /record_audio
 *   API: /api/system /api/app/list /api/app/launch /api/screen/shot /api/input/tap ...
 */
object RemoteHttpServer {

    private const val PORT = 19876
    private const val TAG = "MBclaw-HTTP"
    private const val ALLOWED_PREFIX = "100."
    private var running = false
    private var serverThread: Thread? = null
    private lateinit var appContext: Context
    private lateinit var tier: PermissionTier

    // 认证令牌
    private var authToken: String = ""

    fun start(ctx: Context, permTier: PermissionTier) {
        appContext = ctx.applicationContext
        tier = permTier
        authToken = loadOrGenerateToken()
        if (running) return
        running = true
        serverThread = Thread {
            // ★ 抢占端口: 杀掉可能占用19876的旧进程
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "fuser -k $PORT/tcp 2>/dev/null")).waitFor()
                Thread.sleep(500)
            } catch (_: Exception) {}

            // 重试3次绑定
            var server: ServerSocket? = null
            for (attempt in 1..3) {
                try {
                    server = ServerSocket(PORT, 10)
                    break
                } catch (e: IOException) {
                    android.util.Log.e(TAG, "绑定尝试 $attempt/3 失败: ${e.message}")
                    Thread.sleep(1000)
                }
            }

            if (server == null) {
                android.util.Log.e(TAG, "HTTP server 绑定 $PORT 最终失败")
                running = false
                return@Thread
            }

            android.util.Log.e(TAG, "🐾 小爪中心v2(Kotlin)启动: 0.0.0.0:$PORT")

            // ★ 安装系统级守护 — 开机自启 + 每小时检测MBclaw存活
            installBootWatchdog()

            while (running) {
                try {
                    val client = server.accept()
                    Thread { handleClient(client) }.start()
                } catch (e: IOException) {
                    if (running) android.util.Log.e(TAG, "accept error: ${e.message}")
                }
            }
            try { server.close() } catch (_: Exception) {}
        }.apply {
            name = "mbclaw-httpd"
            isDaemon = false
            start()
        }
    }

    fun stop() {
        running = false
        try { serverThread?.interrupt() } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════
    // 请求处理核心
    // ══════════════════════════════════════════

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())

            // 解析请求行
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            var path = parts[1]

            // IP 检查
            val remoteAddr = socket.inetAddress.hostAddress ?: "0.0.0.0"
            if (!remoteAddr.startsWith(ALLOWED_PREFIX) && remoteAddr != "127.0.0.1") {
                sendError(output, 403, "Access denied: Tailscale only")
                return
            }

            // Token 检查
            if (authToken.isNotEmpty()) {
                var foundToken = false
                // URL 参数中查找
                val queryStart = path.indexOf('?')
                if (queryStart >= 0) {
                    val query = path.substring(queryStart + 1)
                    for (param in query.split("&")) {
                        if (param.startsWith("token=")) {
                            if (param.substring(6) == authToken) foundToken = true
                            break
                        }
                    }
                }
                // Header 中查找
                var headerLine: String?
                while (input.readLine().also { headerLine = it } != null) {
                    if (headerLine.isNullOrEmpty()) break
                    if (headerLine!!.startsWith("Authorization: Bearer ")) {
                        if (headerLine!!.substring(22).trim() == authToken) foundToken = true
                    }
                }
                if (!foundToken) {
                    sendError(output, 401, "Unauthorized")
                    return
                }
            }

            // 读取 headers
            var contentLength = 0
            var headerLine: String?
            while (input.readLine().also { headerLine = it } != null) {
                if (headerLine.isNullOrEmpty()) break
                if (headerLine!!.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine!!.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            // 读取 body
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                input.read(buf, 0, contentLength)
                String(buf)
            } else ""

            // 路径归一化
            val cleanPath = if (path.contains('?')) path.substring(0, path.indexOf('?')) else path

            // 解析查询参数
            val params = mutableMapOf<String, String>()
            if (path.contains('?')) {
                val query = path.substring(path.indexOf('?') + 1)
                for (p in query.split("&")) {
                    val kv = p.split("=", limit = 2)
                    if (kv.size == 2) params[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
                }
            }
            // POST body 也当参数
            if (body.isNotEmpty() && !body.startsWith("{")) {
                for (p in body.split("&")) {
                    val kv = p.split("=", limit = 2)
                    if (kv.size == 2) params[kv[0]] = URLDecoder.decode(kv[1], "UTF-8")
                }
            }

            // 路由
            val result = route(method, cleanPath, params, body)

            // 响应
            val responseBody = result.toByteArray(Charsets.UTF_8)
            val isGzip = params["gzip"] == "1" || requestLine.contains("gzip")
            val finalBody: ByteArray
            val contentEncoding: String

            if (isGzip && responseBody.size > 1024) {
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).use { it.write(responseBody) }
                finalBody = bos.toByteArray()
                contentEncoding = "gzip"
            } else {
                finalBody = responseBody
                contentEncoding = "identity"
            }

            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ${finalBody.size}\r\n")
                if (isGzip) append("Content-Encoding: $contentEncoding\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Server: MBclaw-XiaoZhao/2.0\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray())
            output.write(finalBody)
            output.flush()
        } catch (e: Exception) {
            // 客户端断开，静默
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendError(output: BufferedOutputStream, code: Int, msg: String) {
        val body = """{"error":"$msg","code":$code}"""
        val headers = buildString {
            append("HTTP/1.1 $code\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.length}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray())
        output.write(body.toByteArray())
        output.flush()
    }

    // ══════════════════════════════════════════
    // API 路由
    // ══════════════════════════════════════════

    private fun route(method: String, path: String, params: Map<String, String>, body: String): String {
        return try {
            when (path) {
                "/" -> index()
                "/ping" -> """{"pong":true,"ts":${System.currentTimeMillis()}}"""
                "/info" -> systemInfo()
                "/battery" -> batteryInfo()
                "/settings" -> settingsInfo()
                "/processes" -> processList()
                "/kill" -> killProcess(params["pid"])
                "/uptime" -> uptimeInfo()
                "/screenshot" -> takeScreenshot(params)
                "/shell" -> runShell(params["cmd"] ?: "")
                "/su" -> runRootShell(params["cmd"] ?: "")
                "/apps" -> appList()
                "/app_info" -> appInfo(params["pkg"] ?: "")
                "/start" -> startApp(params["pkg"] ?: "")
                "/stop" -> stopApp(params["pkg"] ?: "")
                "/install" -> installApp(params["url"] ?: "")
                "/uninstall" -> uninstallApp(params["pkg"] ?: "")
                "/tap" -> inputTap(params)
                "/swipe" -> inputSwipe(params)
                "/type" -> inputType(params["text"] ?: "")
                "/key" -> inputKey(params["code"] ?: "")
                "/back" -> inputKey("4")
                "/home" -> inputKey("3")
                "/recents" -> inputKey("187")
                "/ls" -> listDir(params["path"] ?: "/sdcard")
                "/cat" -> readFile(params["path"] ?: "")
                "/find" -> findFiles(params)
                "/tree" -> dirTree(params["path"] ?: "/sdcard", (params["depth"] ?: "2").toIntOrNull() ?: 2)
                "/du" -> diskUsage(params["path"] ?: "/sdcard")
                "/stats" -> fileStats(params["path"] ?: "")
                "/download" -> downloadFile(params["path"] ?: "")
                "/wifi" -> wifiInfo()
                "/netstat" -> netstatInfo()
                "/ping" -> pingHost(params["host"] ?: "8.8.8.8")
                "/photos" -> photosList(params)
                "/cameras" -> cameraInfo()
                "/record_audio" -> recordAudio(params)
                "/screenrecord" -> screenRecord(params)
                // ★ 增强端点 — 比 phone-remote.py 更强的功能
                "/api/system" -> systemInfo()
                "/api/screen/shot" -> takeScreenshot(params)
                "/api/screen/record" -> screenRecord(params)
                "/api/input/tap" -> inputTap(params)
                "/api/input/swipe" -> inputSwipe(params)
                "/api/input/text" -> inputType(params["text"] ?: "")
                "/api/input/key" -> inputKey(params["code"] ?: "")
                "/api/input/gesture" -> inputGesture(params["points"] ?: "")
                "/api/app/list" -> appList()
                "/api/app/launch" -> startApp(params["pkg"] ?: "")
                "/api/app/force-stop" -> stopApp(params["pkg"] ?: "")
                "/api/app/install" -> installApp(params["url"] ?: "")
                "/api/app/uninstall" -> uninstallApp(params["pkg"] ?: "")
                "/api/app/current" -> currentApp()
                "/api/fs/list" -> listDir(params["path"] ?: "/sdcard")
                "/api/fs/read" -> readFile(params["path"] ?: "")
                "/api/fs/write" -> writeFile(params["path"] ?: "", body)
                "/api/fs/delete" -> deleteFile(params["path"] ?: "")
                "/api/shell" -> runRootShell(params["cmd"] ?: "")
                "/api/device/reboot" -> deviceReboot(params["type"] ?: "normal")
                "/api/clipboard/get" -> getClipboard()
                "/api/clipboard/set" -> setClipboard(params["text"] ?: "")
                "/api/notification/dump" -> dumpNotifications()
                else -> """{"error":"Not Found","path":"$path"}"""
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "route error $path: ${e.message}")
            """{"error":"${e.message}","path":"$path"}"""
        }
    }

    // ══════════════════════════════════════════
    // ★ 系统级开机守护 — root 权限写入 crontab / service.d / 兜底 daemon
    // ══════════════════════════════════════════

    fun installBootWatchdog() {
        try {
            val pkg = "com.mbclaw.root"

            // 守护脚本 — 每小时检测MBclaw进程，不在则拉起
            val guardScript = """
#!/system/bin/sh
# MBclaw System Watchdog — 每小时检测存活，不在则拉起
PKG="${'$'}{pkg}"
PORT="${'$'}{PORT}"
while true; do
  sleep 3600
  if ! ps -A | grep -q "${'$'}PKG"; then
    am start -n ${'$'}PKG/.MainActivity > /dev/null 2>&1
    echo "${'$'}(date): MBclaw restarted by watchdog" >> /data/local/tmp/mbclaw_watchdog.log
  fi
  if ! curl -s --max-time 3 http://127.0.0.1:${'$'}PORT/ping > /dev/null 2>&1; then
    am force-stop ${'$'}PKG 2>/dev/null
    sleep 2
    am start -n ${'$'}PKG/.MainActivity > /dev/null 2>&1
  fi
done
""".trimIndent()

            // 写入守护脚本
            val scriptPath = "/data/local/tmp/mbclaw_watchdog.sh"
            Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > $scriptPath << 'GUARD_EOF'\n$guardScript\nGUARD_EOF\nchmod 755 $scriptPath"))
                .waitFor()

            // 策略1: Magisk/KernelSU service.d (最可靠的开机自启)
            val serviceDir = "/data/adb/service.d"
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "if [ -d $serviceDir ]; then cp $scriptPath $serviceDir/mbclaw_watchdog.sh; chmod 755 $serviceDir/mbclaw_watchdog.sh; echo 'magisk_ok'; fi"))
                .waitFor()

            // 策略2: crontab (部分ROM支持)
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "(crontab -l 2>/dev/null; echo '0 * * * * $scriptPath') | crontab - 2>/dev/null"))
                .waitFor()

            // 策略3: 直接后台启动守护 daemon (兜底 — 即使上面都失败也有守护)
            Runtime.getRuntime().exec(arrayOf("su", "-c",
                "nohup sh $scriptPath > /dev/null 2>&1 &"))
                .waitFor()

            android.util.Log.e(TAG, "🛡️ 系统守护已安装: $scriptPath (每小时检测)")

        } catch (e: Exception) {
            android.util.Log.e(TAG, "守护安装失败: ${e.message}")
        }
    }

    // ══════════════════════════════════════════
    // 索引
    // ══════════════════════════════════════════

    private fun index(): String {
        return """
{
  "service": "🐾 小爪远程控制中心 v2 (Kotlin原生)",
  "device": "${Build.MODEL}",
  "android": "${Build.VERSION.RELEASE}",
  "sdk": ${Build.VERSION.SDK_INT},
  "brand": "${Build.BRAND}",
  "uptime": "${getDeviceUptime()}",
  "root": ${tier.hasRoot},
  "endpoints": {
    "系统": ["/info","/battery","/settings","/processes","/kill","/uptime"],
    "屏幕": ["/screenshot","/screenrecord"],
    "控制": ["/tap","/swipe","/type","/key","/back","/home","/recents"],
    "应用": ["/apps","/app_info","/start","/stop","/install","/uninstall"],
    "文件": ["/ls","/cat","/find","/tree","/du","/stats","/download"],
    "命令": ["/shell","/su"],
    "网络": ["/wifi","/netstat","/ping"],
    "媒体": ["/photos","/cameras","/record_audio"],
    "API": ["/api/system","/api/screen/shot","/api/input/*","/api/app/*","/api/fs/*","/api/shell","/api/device/reboot","/api/clipboard/*","/api/notification/dump"]
  },
  "capabilities": {
    "screenshot_root": true,
    "screenshot_mediaprojection": true,
    "input_root": true,
    "input_accessibility": true,
    "fs_full_access": true,
    "app_manage": true,
    "clipboard_rw": true,
    "notification_dump": true,
    "gesture_compose": true
  }
}"""
    }

    // ══════════════════════════════════════════
    // 系统信息
    // ══════════════════════════════════════════

    private fun systemInfo(): String {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val storage = android.os.Environment.getExternalStorageDirectory()
        val stat = android.os.StatFs(storage.path)

        return """
{
  "model": "${Build.MODEL}",
  "brand": "${Build.BRAND}",
  "device": "${Build.DEVICE}",
  "android": "${Build.VERSION.RELEASE}",
  "sdk": ${Build.VERSION.SDK_INT},
  "build": "${Build.ID}",
  "security_patch": "${Build.VERSION.SECURITY_PATCH}",
  "kernel": "${System.getProperty("os.version")}",
  "cpu_abi": "${Build.SUPPORTED_ABIS.joinToString(",")}",
  "hostname": "localhost",
  "root": ${tier.hasRoot},
  "uptime": "${getDeviceUptime()}",
  "ram": {
    "total": ${memInfo.totalMem},
    "available": ${memInfo.availMem},
    "low_memory": ${memInfo.lowMemory}
  },
  "storage": {
    "total": ${stat.totalBytes},
    "available": ${stat.availableBytes},
    "free": ${stat.freeBytes},
    "percent": "${(100 - (stat.availableBytes * 100 / stat.totalBytes))}%"
  }
}"""
    }

    private fun batteryInfo(): String {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10f
        val batteryManager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        return """
{
  "level": ${level * 100 / scale}%,
  "capacity": $capacity%,
  "temperature_c": $temp,
  "plugged": "${when(plugged) { 1 -> "AC"; 2 -> "USB"; 4 -> "Wireless"; else -> "Battery" }}",
  "status": "${when(status) { 2 -> "Charging"; 3 -> "Discharging"; 5 -> "Full"; else -> "Unknown" }}",
  "health": "Good",
  "technology": "Li-Po"
}"""
    }

    private fun settingsInfo(): String {
        return """
{
  "brightness": ${Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)},
  "volume_music": ${try { (appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(3) } catch (_: Exception) { -1 }},
  "wifi_on": ${runRootShellPlain("settings get global wifi_on").trim() == "1"},
  "bluetooth_on": ${runRootShellPlain("settings get global bluetooth_on").trim() == "1"},
  "airplane_mode": ${Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1},
  "locale": "${Locale.getDefault()}",
  "timezone": "${TimeZone.getDefault().id}",
  "screen_timeout": ${Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)}
}"""
    }

    private fun processList(): String {
        val output = runRootShellPlain("ps -A -o PID,USER,NAME --no-headers 2>/dev/null | head -100")
        val processes = output.lines().filter { it.isNotBlank() }.map { line ->
            val cols = line.trim().split("\\s+".toRegex(), limit = 3)
            if (cols.size >= 3) """{"pid":${cols[0]},"user":"${cols[1]}","name":"${cols[2]}"}""" else ""
        }.filter { it.isNotEmpty() }.joinToString(",")
        return """{"count":${output.lines().count { it.isNotBlank() }},"processes":[$processes]}"""
    }

    private fun killProcess(pid: String?): String {
        if (pid == null) return """{"error":"missing pid"}"""
        val result = runRootShellPlain("kill $pid 2>&1")
        return """{"pid":$pid,"result":"${result.trim()}"}"""
    }

    private fun uptimeInfo(): String {
        return """{"uptime":"${getDeviceUptime()}"}"""
    }

    // ══════════════════════════════════════════
    // 屏幕
    // ══════════════════════════════════════════

    private fun takeScreenshot(params: Map<String, String>): String {
        val quality = (params["quality"] ?: "80").toIntOrNull() ?: 80
        val maxSize = (params["max_size"] ?: "1024").toIntOrNull() ?: 1024
        val format = params["format"] ?: "png"

        val tmpPath = "/data/local/tmp/mbclaw_screenshot.$format"
        val result = runRootShellPlain("screencap -p $tmpPath 2>&1")

        if (!result.contains("Error") && java.io.File(tmpPath).exists()) {
            var bitmap = BitmapFactory.decodeFile(tmpPath)
            // 缩放
            val origW = bitmap.width
            val origH = bitmap.height
            if (origW > maxSize || origH > maxSize) {
                val ratio = minOf(maxSize.toFloat() / origW, maxSize.toFloat() / origH)
                bitmap = Bitmap.createScaledBitmap(bitmap, (origW * ratio).toInt(), (origH * ratio).toInt(), true)
            }
            // 压缩为 JPEG 减小体积
            val bos = ByteArrayOutputStream()
            bitmap.compress(if (format == "jpg" || format == "jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, quality, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            bitmap.recycle()
            java.io.File(tmpPath).delete()

            return """{"format":"$format","width":${bitmap.width},"height":${bitmap.height},"original_width":$origW,"original_height":$origH,"size_bytes":${bos.size()},"image":"$b64"}"""
        } else {
            return """{"error":"screenshot failed: $result"}"""
        }
    }

    private fun screenRecord(params: Map<String, String>): String {
        val duration = (params["duration"] ?: "5").toIntOrNull() ?: 5
        val tmpPath = "/data/local/tmp/mbclaw_record.mp4"

        // 杀掉旧的录制
        runRootShellPlain("killall screenrecord 2>/dev/null")
        Thread.sleep(200)

        // 启动录制
        Thread {
            runRootShellPlain("screenrecord --time-limit $duration $tmpPath 2>/dev/null")
        }.start()

        return """{"recording":true,"duration":$duration,"path":"$tmpPath","note":"Wait ${duration}s then GET /download?path=$tmpPath"}"""
    }

    // ══════════════════════════════════════════
    // 输入控制
    // ══════════════════════════════════════════

    private fun inputTap(params: Map<String, String>): String {
        val x = params["x"] ?: params["X"] ?: return """{"error":"missing x"}"""
        val y = params["y"] ?: params["Y"] ?: return """{"error":"missing y"}"""
        runRootShellPlain("input tap $x $y")
        return """{"tap":{"x":$x,"y":$y},"ok":true}"""
    }

    private fun inputSwipe(params: Map<String, String>): String {
        val x1 = params["x1"] ?: params["from_x"] ?: "0"
        val y1 = params["y1"] ?: params["from_y"] ?: "0"
        val x2 = params["x2"] ?: params["to_x"] ?: "0"
        val y2 = params["y2"] ?: params["to_y"] ?: "0"
        val duration = params["duration"] ?: "300"
        runRootShellPlain("input swipe $x1 $y1 $x2 $y2 $duration")
        return """{"swipe":{"from":[$x1,$y1],"to":[$x2,$y2],"duration_ms":$duration},"ok":true}"""
    }

    private fun inputType(text: String): String {
        // 处理特殊字符和中文字符
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace(" ", "%s")
        // 先切换到英文键盘，再输入
        runRootShellPlain("ime set com.android.adbkeyboard/.AdbIME 2>/dev/null; sleep 0.3; input text '$escaped' 2>&1 || am broadcast -a ADB_INPUT_TEXT --es msg '$text' 2>&1")
        return """{"typed":"${text.take(100)}","len":${text.length},"ok":true}"""
    }

    private fun inputKey(code: String): String {
        runRootShellPlain("input keyevent $code")
        return """{"key":"$code","ok":true}"""
    }

    // 多点触摸手势
    private fun inputGesture(pointsStr: String): String {
        // 格式: "x1,y1,x2,y2,..." 或 "x1,y1;x2,y2"
        val points = pointsStr.split(";").map { p ->
            val xy = p.split(",")
            if (xy.size == 2) Pair(xy[0].toIntOrNull() ?: 0, xy[1].toIntOrNull() ?: 0) else null
        }.filterNotNull()

        if (points.size < 2) return """{"error":"need at least 2 points"}"""

        val cmd = buildString {
            append("input swipe")
            for (point in points) append(" ${point.first} ${point.second}")
        }
        runRootShellPlain(cmd)
        return """{"gesture":{"points":$points,"count":${points.size}},"ok":true}"""
    }

    // ══════════════════════════════════════════
    // 应用管理 — 直接用 Android API
    // ══════════════════════════════════════════

    private fun appList(): String {
        val pm: PackageManager = appContext.packageManager
        val apps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA) ?: emptyList()
        val filtered: List<ApplicationInfo> = apps.filter { app: ApplicationInfo ->
            app.flags and ApplicationInfo.FLAG_SYSTEM == 0 || app.packageName.contains("mbclaw", ignoreCase = true)
        }
        val sorted: List<ApplicationInfo> = filtered.sortedBy { app: ApplicationInfo ->
            pm.getApplicationLabel(app).toString().lowercase()
        }.take(200)
        val list: String = sorted.joinToString(",") { app: ApplicationInfo ->
            val label: String = pm.getApplicationLabel(app).toString().replace("\"", "\\\"")
            """{"pkg":"${app.packageName}","name":"$label","system":${app.flags and ApplicationInfo.FLAG_SYSTEM != 0}}"""
        }
        return """{"apps":[$list]}"""
    }

    private fun appInfo(pkg: String): String {
        if (pkg.isEmpty()) return """{"error":"missing pkg"}"""
        return try {
            val pm = appContext.packageManager
            val info = pm.getPackageInfo(pkg, 0)
            val ai = info.applicationInfo ?: return """{"error":"no app info for $pkg"}"""
            """{
  "pkg": "$pkg",
  "name": "${pm.getApplicationLabel(ai)}",
  "version": "${info.versionName}",
  "version_code": ${info.versionCode},
  "target_sdk": ${ai.targetSdkVersion},
  "first_install": "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(info.firstInstallTime))}",
  "last_update": "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(info.lastUpdateTime))}",
  "apk_path": "${ai.sourceDir}",
  "data_dir": "${ai.dataDir}",
  "system": ${ai.flags and ApplicationInfo.FLAG_SYSTEM != 0},
  "enabled": ${ai.enabled},
  "uid": ${ai.uid}
}"""
        } catch (e: Exception) {
            """{"error":"${e.message}","pkg":"$pkg"}"""
        }
    }

    private fun startApp(pkg: String): String {
        if (pkg.isEmpty()) return """{"error":"missing pkg"}"""
        return try {
            val intent = appContext.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
                """{"started":"$pkg","ok":true}"""
            } else {
                val result = runRootShellPlain("am start -n $pkg/.MainActivity 2>&1 || monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1")
                """{"started":"$pkg","method":"am","result":"${result.trim()}"}"""
            }
        } catch (e: Exception) {
            """{"error":"${e.message}","pkg":"$pkg"}"""
        }
    }

    private fun stopApp(pkg: String): String {
        if (pkg.isEmpty()) return """{"error":"missing pkg"}"""
        runRootShellPlain("am force-stop $pkg")
        return """{"stopped":"$pkg","ok":true}"""
    }

    private fun currentApp(): String {
        val output = runRootShellPlain("dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -2")
        return """{"current":"${output.trim().replace("\"", "\\\"")}"}"""
    }

    private fun installApp(url: String): String {
        if (url.isEmpty()) return """{"error":"missing url"}"""
        val tmpPath = "/data/local/tmp/mbclaw_install.apk"
        Thread {
            runRootShellPlain("curl -sL '$url' -o $tmpPath 2>&1 && pm install -r $tmpPath 2>&1 && rm $tmpPath")
        }.start()
        return """{"installing":true,"url":"$url","path":"$tmpPath","note":"Downloading and installing in background"}"""
    }

    private fun uninstallApp(pkg: String): String {
        if (pkg.isEmpty()) return """{"error":"missing pkg"}"""
        runRootShellPlain("pm uninstall $pkg 2>&1")
        return """{"uninstalled":"$pkg","ok":true}"""
    }

    // ══════════════════════════════════════════
    // 文件操作
    // ══════════════════════════════════════════

    private fun safeFilePath(p: String, base: String = "/sdcard"): String {
        val path = if (p.isEmpty()) base else p
        return java.io.File(path).canonicalPath
    }

    private fun listDir(path: String): String {
        val dir = java.io.File(safeFilePath(path))
        if (!dir.exists()) return """{"error":"Not found: $path"}"""
        if (!dir.isDirectory) return """{"error":"Not a directory: $path"}"""
        val allFiles: Array<java.io.File> = dir.listFiles() ?: emptyArray()
        val sorted: List<java.io.File> = allFiles.sortedWith(Comparator { a, b ->
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                a.name.lowercase().compareTo(b.name.lowercase())
            }
        })
        val limited: List<java.io.File> = sorted.take(500)
        val entries: String = limited.joinToString(",") { f: java.io.File ->
            "{" +
                "\"name\":\"${f.name.replace("\"", "\\\"")}\"," +
                "\"path\":\"${f.absolutePath.replace("\"", "\\\"")}\"," +
                "\"is_dir\":${f.isDirectory}," +
                "\"size\":${f.length()}," +
                "\"modified\":${f.lastModified()}" +
            "}"
        }
        return """{"path":"$path","count":${sorted.size},"entries":[$entries]}"""
    }

    private fun readFile(path: String): String {
        val f = java.io.File(safeFilePath(path))
        if (!f.exists()) return """{"error":"Not found: $path"}"""
        if (f.length() > 10 * 1024 * 1024) return """{"error":"File too large: ${f.length()} bytes"}"""
        val content = f.readText()
        return """{"path":"$path","size":${f.length()},"content":${toJsonString(content.take(50000))}${if (content.length > 50000) "...[truncated at 50000 chars]" else ""}}"""
    }

    private fun writeFile(path: String, body: String): String {
        val f = java.io.File(safeFilePath(path))
        f.parentFile?.mkdirs()
        f.writeText(body)
        return """{"written":"$path","size":${f.length()},"ok":true}"""
    }

    private fun deleteFile(path: String): String {
        val f = java.io.File(safeFilePath(path))
        if (!f.exists()) return """{"error":"Not found: $path"}"""
        val deleted = f.deleteRecursively()
        return """{"path":"$path","deleted":$deleted}"""
    }

    private fun findFiles(params: Map<String, String>): String {
        val dir = params["path"] ?: "/sdcard"
        val pattern = params["name"] ?: "*"
        val maxDepth = (params["depth"] ?: "4").toIntOrNull() ?: 4
        val maxResults = (params["limit"] ?: "100").toIntOrNull() ?: 100

        val results = mutableListOf<String>()
        fun search(d: java.io.File, depth: Int) {
            if (depth > maxDepth || results.size >= maxResults) return
            d.listFiles()?.forEach { f ->
                if (f.name.contains(pattern, ignoreCase = true)) {
                    results.add("""{"name":"${f.name}","path":"${f.absolutePath}","is_dir":${f.isDirectory},"size":${f.length()}}""")
                }
                if (f.isDirectory) search(f, depth + 1)
            }
        }
        search(java.io.File(safeFilePath(dir)), 0)
        return """{"dir":"$dir","pattern":"$pattern","count":${results.size},"results":[${results.joinToString(",")}]}"""
    }

    private fun dirTree(path: String, depth: Int): String {
        val sb = StringBuilder()
        fun tree(d: java.io.File, indent: String, currentDepth: Int) {
            if (currentDepth > depth) return
            d.listFiles()?.take(50)?.forEach { f ->
                sb.append("$indent${if (f.isDirectory) "📁" else "📄"} ${f.name} (${formatSize(f.length())})\n")
                if (f.isDirectory) tree(f, "$indent  ", currentDepth + 1)
            }
        }
        tree(java.io.File(safeFilePath(path)), "", 0)
        return """{"path":"$path","depth":$depth,"tree":${toJsonString(sb.toString())}}"""
    }

    private fun diskUsage(path: String): String {
        val rootDir = java.io.File(safeFilePath(path))
        var totalSize = 0L
        var fileCount = 0
        fun countDir(d: java.io.File, maxDepth: Int) {
            if (maxDepth <= 0) return
            d.listFiles()?.forEach { f ->
                if (f.isFile) { totalSize += f.length(); fileCount++ }
                else if (f.isDirectory) countDir(f, maxDepth - 1)
            }
        }
        countDir(rootDir, 5)
        return """{"path":"$path","total_size":$totalSize,"total_size_human":"${formatSize(totalSize)}","file_count":$fileCount,"depth_scanned":5}"""
    }

    private fun fileStats(path: String): String {
        val f = java.io.File(safeFilePath(path))
        if (!f.exists()) return """{"error":"Not found: $path"}"""
        val stat = android.os.StatFs(f.absolutePath)
        return """{
  "path": "$path",
  "exists": true,
  "is_dir": ${f.isDirectory},
  "is_file": ${f.isFile},
  "size": ${f.length()},
  "size_human": "${formatSize(f.length())}",
  "modified": ${f.lastModified()},
  "readable": ${f.canRead()},
  "writable": ${f.canWrite()},
  "executable": ${f.canExecute()},
  "fs_block_size": ${stat.blockSize},
  "fs_total_blocks": ${stat.blockCount},
  "fs_free_blocks": ${stat.freeBlocks},
  "fs_available_blocks": ${stat.availableBlocks}
}"""
    }

    private fun downloadFile(path: String): String {
        val f = java.io.File(safeFilePath(path))
        if (!f.exists()) return """{"error":"Not found: $path"}"""
        if (f.length() > 50 * 1024 * 1024) return """{"error":"File too large: ${f.length()} bytes (max 50MB)"}"""
        val bytes = f.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return """{"path":"$path","size":${f.length()},"base64":"$b64"}"""
    }

    // ══════════════════════════════════════════
    // Shell
    // ══════════════════════════════════════════

    private fun runShell(cmd: String): String {
        if (cmd.isEmpty()) return """{"error":"missing cmd"}"""
        val result = runCommand(cmd, root = false)
        return """{"stdout":${toJsonString(result.first)},"stderr":${toJsonString(result.second)},"returncode":${result.third},"root":false}"""
    }

    private fun runRootShell(cmd: String): String {
        if (cmd.isEmpty()) return """{"error":"missing cmd"}"""
        val result = runCommand(cmd, root = true)
        return """{"stdout":${toJsonString(result.first)},"stderr":${toJsonString(result.second)},"returncode":${result.third},"root":true}"""
    }

    private fun runRootShellPlain(cmd: String): String {
        return runCommand(cmd, root = true).first
    }

    private fun runCommand(cmd: String, root: Boolean, timeoutMs: Long = 60000): Triple<String, String, Int> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().take(100000)
            val stderr = process.errorStream.bufferedReader().readText().take(10000)
            if (stdout.isBlank() && stderr.isBlank()) Triple("(empty)", "", process.exitValue())
            else Triple(stdout, stderr, process.exitValue())
        } catch (e: Exception) {
            // fallback to sh
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                p.waitFor()
                Triple(p.inputStream.bufferedReader().readText().take(100000), p.errorStream.bufferedReader().readText().take(10000), p.exitValue())
            } catch (e2: Exception) {
                Triple("", e.message ?: "unknown error", -1)
            }
        }
    }

    // ══════════════════════════════════════════
    // 网络
    // ══════════════════════════════════════════

    private fun wifiInfo(): String {
        val output = runRootShellPlain("dumpsys wifi 2>/dev/null | grep -E 'SSID|BSSID|IP|RSSI|speed' | head -10")
        return """{"wifi_info":"${output.trim().replace("\"", "\\\"")}"}"""
    }

    private fun netstatInfo(): String {
        val output = runRootShellPlain("netstat -tlnp 2>/dev/null | head -30")
        return """{"netstat":"${output.trim().replace("\"", "\\\"")}"}"""
    }

    private fun pingHost(host: String): String {
        val output = runRootShellPlain("ping -c 3 -W 2 $host 2>&1")
        return """{"host":"$host","result":"${output.trim().replace("\"", "\\\"")}"}"""
    }

    // ══════════════════════════════════════════
    // 媒体
    // ══════════════════════════════════════════

    private fun photosList(params: Map<String, String>): String {
        val limit = (params["limit"] ?: "50").toIntOrNull() ?: 50
        val after = params["after"]?.toLongOrNull()
        val before = params["before"]?.toLongOrNull()
        val dirs = params["dirs"] ?: "/sdcard/DCIM /sdcard/Pictures /sdcard/Download"
        val types = params["types"] ?: "*.jpg *.jpeg *.png *.heic *.mp4"
        val nameExpr = types.split(" ").filter { it.isNotBlank() }.joinToString(" -o ") { "-name '$it'" }
        val findCmd = "find ${dirs} -type f \\( ${nameExpr} \\) -printf '%T@ %s %p\\n' 2>/dev/null | sort -rn"
        val output = runRootShellPlain(findCmd)
        val files = output.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.trim().split(" ", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val fileTs = parts[0].toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            if (after != null && fileTs < after) return@mapNotNull null
            if (before != null && fileTs > before) return@mapNotNull null
            """{"time":"${parts[0]}","size":${parts[1]},"path":"${parts[2].replace("\"", "\\\"")}"}"""
        }.take(limit).joinToString(",")
        return """{"photos":[$files],"total":${output.lines().filter { it.isNotBlank() }.size}}"""
    }

    private fun cameraInfo(): String {
        val output = runRootShellPlain("dumpsys media.camera 2>/dev/null | head -5")
        return """{"cameras":"${output.trim().replace("\"", "\\\"")}"}"""
    }

    private fun recordAudio(params: Map<String, String>): String {
        val duration = (params["duration"] ?: "10").toIntOrNull() ?: 10
        val tmpPath = "/data/local/tmp/mbclaw_recording.wav"
        Thread {
            runRootShellPlain("screenrecord --time-limit $duration $tmpPath 2>/dev/null || am start -a android.provider.MediaStore.RECORD_SOUND")
        }.start()
        return """{"recording":true,"duration":$duration,"note":"Audio recording started for ${duration}s"}"""
    }

    // ══════════════════════════════════════════
    // 增强功能
    // ══════════════════════════════════════════

    private fun deviceReboot(type: String): String {
        val cmd = when (type) {
            "recovery" -> "reboot recovery"
            "bootloader" -> "reboot bootloader"
            "soft" -> "am restart" // 仅重启 Android 框架
            else -> "reboot"
        }
        Thread {
            Thread.sleep(500) // 给响应返回的时间
            runRootShellPlain(cmd)
        }.start()
        return """{"rebooting":"$type","note":"Device is rebooting..."}"""
    }

    private fun getClipboard(): String {
        return try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                """{"text":${toJsonString(clip.getItemAt(0).text?.toString() ?: "")}}"""
            } else {
                """{"text":""}"""
            }
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }

    private fun setClipboard(text: String): String {
        return try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("mbclaw", text)
            cm.setPrimaryClip(clip)
            """{"ok":true,"text":"${text.take(200)}"}"""
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }

    private fun dumpNotifications(): String {
        val output = runRootShellPlain("dumpsys notification --noredact 2>/dev/null | grep -E 'NotificationRecord|pkg=|android.title|android.text' | head -40")
        return """{"notifications":${toJsonString(output.take(5000))}}"""
    }

    // ══════════════════════════════════════════
    // 工具函数
    // ══════════════════════════════════════════

    private fun loadOrGenerateToken(): String {
        val prefs = appContext.getSharedPreferences("mbclaw_http", Context.MODE_PRIVATE)
        var token = prefs.getString("auth_token", null)
        if (token == null) {
            token = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
            prefs.edit().putString("auth_token", token).apply()
        }
        return token
    }

    private fun getDeviceUptime(): String {
        val uptimeMs = SystemClock.elapsedRealtime()
        val days = uptimeMs / 86400000
        val hours = (uptimeMs % 86400000) / 3600000
        val mins = (uptimeMs % 3600000) / 60000
        return "${days}d ${hours}h ${mins}m"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
        }
    }

    private fun toJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (c.code < 32) sb.append("\\u${String.format("%04x", c.code)}") else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
