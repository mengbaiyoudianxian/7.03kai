package com.mbclaw.root.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SmsManager
import com.mbclaw.root.data.LocalDB
import com.mbclaw.root.data.UserSettings
import com.mbclaw.root.hermes.RealEngine
import com.mbclaw.root.service.MBclawAccessibilityService
import com.mbclaw.root.service.ShizukuManager
import com.mbclaw.root.service.XiaomiApi
import org.json.JSONObject
import kotlinx.coroutines.*

/**
 * NonRoot 工具执行器 — 系统API优先
 *
 * 四层: 系统SDK → 语音助手 → 无障碍 → Shizuku(可选)
 * 90%的操作不需要Shizuku
 */
class ToolExecutor(
    private val context: Context,
    private val db: LocalDB,
    private val settings: UserSettings,
    private val realEngine: RealEngine,
) {
    private val shizuku = ShizukuManager(context)
    // 统一权限分层 (2026-06-22 锁定优先级): 系统 API > Root > ADB(Shizuku) > 无障碍
    private val tier = PermissionTier.get(context)
    private val hasRoot: Boolean get() = tier.hasRoot
    private fun execRoot(cmd: String): String? = tier.shellRoot(cmd)
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    private val cameraManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager else null

    init { shizuku.init() }

    /** 通过系统API执行 am start (无需Shizuku) */
    private fun systemAmStart(action: String, data: String? = null, extras: Map<String, String> = emptyMap()): Boolean {
        return try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data?.let { setData(Uri.parse(it)) }
                extras.forEach { (k, v) -> putExtra(k, v) }
            }
            context.startActivity(intent); true
        } catch (_: Exception) { false }
    }

    /** 调用手机自带语音助手执行命令 */
    private fun viaVoiceAssistant(command: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_TEXT, command)
                // 小爱同学 / Google Assistant / Bixby 都会响应这个Intent
            }
            context.startActivity(intent); true
        } catch (_: Exception) { false }
    }

    suspend fun execute(toolName: String, args: JSONObject): String {
        val result = withContext(Dispatchers.IO) {
            try {
                when (toolName) {
                // ═══ 设备控制 — 系统SDK直调 ═══
                "toggle_wifi" -> {
                    val enable = args.optBoolean("enable")
                    if (hasRoot) { XiaomiApi.setWifi(enable); execRoot("svc wifi ${if (enable) "enable" else "disable"}"); "WiFi已${if (enable) "打开" else "关闭"} (Root)" }
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { systemAmStart(Settings.Panel.ACTION_WIFI); "WiFi面板已打开" }
                    else { try { wifiManager?.isWifiEnabled = enable; "WiFi已${if (enable) "打开" else "关闭"}" } catch (_:Exception) { "需Root" } }
                }
                "toggle_bluetooth" -> {
                    val enable = args.optBoolean("enable")
                    if (hasRoot) { XiaomiApi.setBluetooth(enable); execRoot("service call bluetooth_manager ${if (enable) "6" else "8"}"); "蓝牙已${if (enable) "打开" else "关闭"} (Root)" }
                    else if (enable) bluetoothAdapter?.enable() else bluetoothAdapter?.disable()
                    "蓝牙 ${if (enable) "正在打开" else "正在关闭"}"
                }
                "toggle_flashlight" -> {
                    // CameraManager API — 无需Shizuku! (Android 6+)
                    val enable = args.optBoolean("enable")
                    val manager = cameraManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && manager != null) {
                        val cameraId = manager.cameraIdList.firstOrNull()
                        if (cameraId != null) {
                            manager.setTorchMode(cameraId, enable)
                            "手电筒 已${if (enable) "打开" else "关闭"} (CameraManager)"
                        } else "无后置摄像头"
                    } else "需要 Android 6.0+"
                }
                "toggle_airplane_mode" -> {
                    // 优先级: Root > Shizuku > 系统广播(Android 9-) > 无障碍跳转
                    val enable = args.optBoolean("enable")
                    val v = if (enable) 1 else 0
                    when {
                        // ★ Root 首选 (修复: 之前漏了)
                        tier.hasRoot -> {
                            execRoot("settings put global airplane_mode_on $v && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${enable}")
                            "✈️ 飞行模式 已${if (enable) "打开" else "关闭"} (Root)"
                        }
                        tier.hasAdb -> {
                            shizuku.exec("settings put global airplane_mode_on $v")
                            shizuku.exec("am broadcast -a android.intent.action.AIRPLANE_MODE")
                            "✈️ 飞行模式 已${if (enable) "打开" else "关闭"} (Shizuku)"
                        }
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> {
                            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply { putExtra("state", enable) }
                            context.sendBroadcast(intent)
                            "✈️ 飞行模式广播已发送 (Android 9-)"
                        }
                        else -> {
                            // 跳到系统设置页让用户手动开
                            systemAmStart(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                            "无 Root/Shizuku, 已打开系统飞行模式设置"
                        }
                    }
                }
                "set_brightness" -> {
                    val level = args.optInt("level", 128)
                    when {
                        tier.hasRoot -> {
                            execRoot("settings put system screen_brightness $level")
                            "💡 亮度已设为 $level/255 (Root)"
                        }
                        Settings.System.canWrite(context) -> {
                            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                            "💡 亮度已设为 $level/255"
                        }
                        tier.hasAdb -> {
                            shizuku.exec("settings put system screen_brightness $level")
                            "💡 亮度已设为 $level/255 (Shizuku)"
                        }
                        else -> {
                            systemAmStart(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            "需要 WRITE_SETTINGS 权限, 已打开授权页面"
                        }
                    }
                }
                "set_volume" -> {
                    val type = when (args.optString("type", "media")) {
                        "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM
                        else -> AudioManager.STREAM_MUSIC
                    }
                    audioManager?.setStreamVolume(type, args.optInt("level", 7), 0)
                    "音量已设"
                }
                "get_battery" -> {
                    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = intent?.getIntExtra("level", -1) ?: -1
                    val scale = intent?.getIntExtra("scale", 100) ?: 100
                    "电池: ${level * 100 / scale}%"
                }

                // ═══ 通信 — 系统Intent ═══
                "send_sms" -> {
                    val phone = args.optString("phone")
                    val message = args.optString("message")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        // 检查SMS权限
                        if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            val smsManager = SmsManager.getDefault()
                            smsManager.sendTextMessage(phone, null, message, null, null)
                            "短信已发送到 $phone"
                        }
                    }
                    // 兜底: 打开短信界面
                    systemAmStart(Intent.ACTION_SENDTO, "sms:$phone", mapOf("sms_body" to message))
                    "短信界面已打开，消息已预填"
                }
                "read_sms" -> "读取短信需READ_SMS权限，请在设置中授权"
                "make_call" -> {
                    if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        systemAmStart(Intent.ACTION_CALL, "tel:${args.optString("phone")}")
                        "正在拨打 ${args.optString("phone")}"
                    } else {
                        systemAmStart(Intent.ACTION_DIAL, "tel:${args.optString("phone")}")
                        "拨号界面已打开"
                    }
                }

                // ═══ 屏幕 — 无障碍优先 (系统级) ═══
                "take_screenshot" -> {
                    if (hasRoot) {
                        execRoot("screencap -p /sdcard/mbclaw_screenshot_${System.currentTimeMillis()}.png")
                        "截图已保存 (Root)"
                    } else if (shizuku.isReady()) {
                        shizuku.screenshot("/sdcard/mbclaw_screenshot_${System.currentTimeMillis()}.png")
                        "截图已保存 (Shizuku)"
                    } else {
                        "截图需要Root或Shizuku"
                    }
                }
                "screen_record" -> {
                    if (hasRoot) {
                        execRoot("screenrecord --time-limit ${args.optInt("duration", 10)} /sdcard/mbclaw_record_${System.currentTimeMillis()}.mp4")
                        "录屏中... (Root)"
                    } else if (shizuku.isReady()) {
                        shizuku.screenRecord("/sdcard/mbclaw_record_${System.currentTimeMillis()}.mp4", args.optInt("duration", 10))
                        "录屏中... (Shizuku)"
                    } else "录屏需要Root或Shizuku"
                }
                "click_at" -> {
                    val x = args.optInt("x"); val y = args.optInt("y")
                    // ★ v4.6: 优先使用 TouchInjector (多通道: input tap → sendevent → 无障碍)
                    val ok = TouchInjector.tap(context, x, y)
                    if (ok) "👆 点击 ($x,$y) [ROOT/TouchInjector]"
                    else {
                        // 兜底: CapabilityRouter
                        val r = CapabilityRouter.exec(context,
                            onRoot = { t -> t.shellRoot("input tap $x $y && echo OK")?.let { "👆 点击 ($x,$y) [ROOT]" } },
                            onAdb = { t -> t.shellAdb("input tap $x $y")?.let { "👆 点击 ($x,$y) [ADB]" } },
                            onUI = {
                                val svc = MBclawAccessibilityService.instance
                                if (svc?.clickAt(x.toFloat(), y.toFloat()) == true) "👆 点击 ($x,$y) [UI]" else null
                            },
                            failHint = "❌ click_at 全部失败"
                        )
                        r.output
                    }
                }
                "long_press_at" -> {
                    val x = args.optInt("x"); val y = args.optInt("y")
                    val dur = args.optLong("duration_ms", 800)
                    val ok = TouchInjector.longPress(context, x, y, dur)
                    if (ok) "👇 长按 ($x,$y) ${dur}ms [ROOT/TouchInjector]"
                    else CapabilityRouter.exec(context,
                        onRoot = { t -> t.shellRoot("input swipe $x $y $x $y $dur && echo OK")?.let { "👇 长按 [ROOT]" } },
                        onAdb = { t -> t.shellAdb("input swipe $x $y $x $y $dur")?.let { "👇 长按 [ADB]" } },
                        onUI = {
                            val svc = MBclawAccessibilityService.instance
                            if (svc?.longClickAt(x.toFloat(), y.toFloat(), dur) == true) "👇 长按 [UI]" else null
                        },
                        failHint = "❌ long_press 失败"
                    ).output
                }
                "swipe" -> {
                    val x1 = args.optInt("x1"); val y1 = args.optInt("y1")
                    val x2 = args.optInt("x2"); val y2 = args.optInt("y2")
                    val dur = args.optLong("duration_ms", 300)
                    val ok = TouchInjector.swipe(context, x1, y1, x2, y2, dur)
                    if (ok) "🌊 滑动 ($x1,$y1)→($x2,$y2) [ROOT/TouchInjector]"
                    else CapabilityRouter.exec(context,
                        onRoot = { t -> t.shellRoot("input swipe $x1 $y1 $x2 $y2 $dur && echo OK")?.let { "🌊 滑动 [ROOT]" } },
                        onAdb = { t -> t.shellAdb("input swipe $x1 $y1 $x2 $y2 $dur")?.let { "🌊 滑动 [ADB]" } },
                        onUI = {
                            val svc = MBclawAccessibilityService.instance
                            if (svc?.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), dur) == true) "🌊 滑动 [UI]" else null
                        },
                        failHint = "❌ swipe 失败"
                    ).output
                }
                "input_text" -> {
                    val text = args.optString("text")
                    val ok = TouchInjector.inputText(context, text)
                    if (ok) "⌨️ 输入 [ROOT/TouchInjector]"
                    else CapabilityRouter.exec(context,
                        onRoot = { t -> t.shellRoot("input text '${text.replace("'", "'\\''")}' && echo OK")?.let { "⌨️ 输入 [ROOT]" } },
                        onAdb = { t -> t.shellAdb("input text '${text.replace("'", "'\\''")}'")?.let { "⌨️ 输入 [ADB]" } },
                        onUI = {
                            val svc = MBclawAccessibilityService.instance
                            if (svc?.inputText(text) == true) "⌨️ 输入 [UI]" else null
                        },
                        failHint = "❌ input_text 失败"
                    ).output
                }
                "press_key" -> {
                    val keyName = args.optString("key").uppercase()
                    val keyCode = when (keyName) {
                        "BACK" -> 4; "HOME" -> 3; "RECENTS" -> 187; "ENTER" -> 66
                        "DELETE" -> 67; "VOLUME_UP" -> 24; "VOLUME_DOWN" -> 25
                        "POWER" -> 26; "MENU" -> 82; "SEARCH" -> 84
                        "CAMERA" -> 27; "TAB" -> 61; "SPACE" -> 62
                        else -> 0
                    }
                    if (keyCode <= 0) "❌ 未知按键 $keyName"
                    else {
                        val ok = TouchInjector.keyEvent(context, keyCode)
                        if (ok) "⌨️ 按下 $keyName [TouchInjector]"
                        else when {
                            tier.hasRoot -> { execRoot("input keyevent $keyCode"); "⌨️ 按下 $keyName [Root]" }
                            tier.hasAdb -> { shizuku.exec("input keyevent $keyCode"); "⌨️ 按下 $keyName [Shizuku]" }
                            tier.hasAccessibility -> {
                                val svc = MBclawAccessibilityService.instance
                                val globalAction = when (keyCode) {
                                    4 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                                    3 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                                    187 -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                                    else -> -1
                                }
                                if (globalAction >= 0 && svc?.performGlobalAction(globalAction) == true) "⌨️ 按下 $keyName [无障碍]"
                                else "❌ 无障碍只支持 BACK/HOME/RECENTS"
                            }
                            else -> "❌ 需要 Root/Shizuku/无障碍"
                        }
                    }
                }

                // ═══ App管理 ═══
                "open_app" -> {
                    val pkg = args.optString("package_name")
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "已打开 $pkg"
                    } else "未找到App: $pkg"
                }
                "list_apps" -> {
                    val filter = args.optString("filter", "")
                    context.packageManager.getInstalledApplications(0)
                        .filter { it.packageName.contains(filter, ignoreCase = true) }
                        .take(10).joinToString("\n") { "  ${it.packageName}" }
                        .let { "已安装($filter):\n$it" }
                }
                "read_file" -> {
                    val path = args.optString("path")
                    if (hasRoot) { execRoot("cat $path") ?: "文件不存在或无法读取" }
                    else { try { java.io.File(path).readText().take(3000) } catch (_:Exception) { "需Root权限读取系统文件" } }
                }
                "uninstall_app" -> {
                    // bug.2: root 静默卸载前自动备份，3 份循环；系统应用 → 高风险，需 confirm
                    val pkg = args.optString("package_name")
                    val confirm = args.optBoolean("confirm", false)
                    val risk = SafeOps.appRisk(context, pkg)
                    if (risk == SafeOps.Risk.HIGH && !confirm) {
                        "⚠️ $pkg 是系统应用，删除可能导致系统不稳定。如确认删除，请在 LLM 调用时传 confirm=true"
                    } else if (tier.hasRoot) {
                        val (ok, msg) = SafeOps.backupApp(context, pkg)
                        val backupNote = if (ok) "💾 已备份: $msg\n" else "⚠️ 备份失败 ($msg)，继续卸载\n"
                        val result = execRoot("pm uninstall --user 0 $pkg")
                        backupNote + (if (result?.contains("Success") == true) "✅ $pkg 已静默卸载" else "❌ 卸载失败: $result")
                    } else {
                        systemAmStart(Intent.ACTION_DELETE, "package:$pkg")
                        "无 Root，卸载界面已打开（无自动备份）"
                    }
                }
                "force_stop_app" -> {
                    val pkg = args.optString("package_name")
                    if (tier.hasRoot) {
                        execRoot("am force-stop $pkg")?.let { "✅ 已强停 $pkg" } ?: "❌ 强停失败"
                    } else if (shizuku.isReady()) {
                        shizuku.forceStopApp(pkg); "✅ 已强停 (Shizuku)"
                    } else {
                        systemAmStart(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg")
                        "应用详情已打开，请手动强制停止"
                    }
                }

                // ═══ 语音助手 ═══
                "trigger_voice_assistant" -> {
                    val cmd = args.optString("command", "")
                    viaVoiceAssistant(cmd)
                    if (cmd.isNotBlank()) "已唤起语音助手并发送: $cmd"
                    else "语音助手已唤起"
                }

                // ═══ 系统信息 ═══
                "get_system_info" -> XiaomiApi.getDeviceInfo()
                "get_clipboard" -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "剪贴板为空"
                }
                "set_clipboard" -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("MBclaw", args.optString("text")))
                    "已复制"
                }
                "get_notifications" -> {
                    val monitor = com.mbclaw.root.service.NotificationMonitor.instance
                    if (monitor != null) "通知监听已激活" else "通知监听未开启，请在设置→通知使用权中开启MBclaw"
                }

                // ═══ MBclaw内部 ═══
                "search_memory" -> db.searchMemory(args.optString("query"), args.optInt("limit", 5)).joinToString("\n") { "• ${it.key}: ${it.value.take(150)}" }.ifBlank { "无相关记忆" }
                "dream_memory" -> realEngine.dream(args.optString("session_id", ""))
                "classify_conversation" -> realEngine.classifyContent(args.optString("text"), emptyList()).first
                "dual_key_review" -> realEngine.dualKeyReview(args.optString("content"))
                "collision_think" -> {
                    val kws = args.optJSONArray("keywords") ?: return@withContext "无关键词"
                    realEngine.collision((0 until kws.length()).map { kws.getString(it) })
                }
                "get_capability" -> {
                    val r = if (tier.hasRoot) "✅ROOT" else "❌root"
                    val a = if (tier.hasAdb) "✅ADB" else "❌adb"
                    val ac = if (tier.hasAccessibility) "✅无障碍" else "❌无障碍"
                    val best = tier.bestTier().name
                    "📱 权限层 (系统API > Root > ADB > 无障碍)\n当前可用: $r / $a / $ac\n最高层: $best | ${if (settings.canUploadKey()) "乌托邦100%" else "本地40%"}"
                }

                // ═══════════════════════════════════════════════════════════
                // 仿 MiClaw 工具 — 大部分用 root shell 直接落地
                // root 版假设有 su，没有 root 时返回明确错误
                // ═══════════════════════════════════════════════════════════

                // ── WiFi ──
                "list_wifi_networks" -> execRoot("cmd wifi list-scan-results 2>/dev/null || dumpsys wifi | head -40") ?: "需 Root"
                "connect_wifi" -> {
                    val ssid = args.optString("ssid"); val pw = args.optString("password")
                    if (pw.isNotBlank()) execRoot("cmd wifi connect-network '$ssid' wpa2 '$pw'") ?: "连接失败"
                    else execRoot("cmd wifi connect-network '$ssid' open") ?: "需 Root 或已保存网络"
                }
                "disconnect_wifi" -> execRoot("cmd wifi disconnect") ?: "需 Root"
                "switch_wifi" -> execRoot("cmd wifi reconnect") ?: "需 Root"
                "wifi_info" -> execRoot("dumpsys wifi | grep -E 'mWifiInfo|SSID|RSSI|Frequency' | head -10") ?: "需 Root"

                // ── 蓝牙 ──
                "bluetooth_scan" -> {
                    val dur = args.optInt("scan_duration", 10)
                    if (bluetoothAdapter == null) "蓝牙不可用"
                    else { bluetoothAdapter!!.startDiscovery(); kotlinx.coroutines.delay(dur * 1000L); bluetoothAdapter!!.cancelDiscovery(); "扫描完成 (${dur}s)" }
                }
                "bluetooth_connect" -> {
                    val addr = args.optString("device_address")
                    execRoot("am broadcast -a android.bluetooth.device.action.BOND_STATE_CHANGED --es android.bluetooth.device.extra.DEVICE '$addr'") ?: "需 Root"
                }
                "bluetooth_disconnect" -> execRoot("dumpsys bluetooth_manager | grep ${args.optString("device_address")}") ?: "需 Root"
                "bluetooth_paired_devices" -> bluetoothAdapter?.bondedDevices?.joinToString("\n") { "  ${it.name} (${it.address})" } ?: "无配对设备"
                "bluetooth_status" -> {
                    val state = bluetoothAdapter?.isEnabled
                    val name = bluetoothAdapter?.name ?: "?"
                    "开关: ${if (state == true) "开" else "关"} | 本机: $name"
                }

                // ── 联系人 ──
                "manage_contacts" -> {
                    val action = args.optString("action")
                    val name = args.optString("name"); val phone = args.optString("phone")
                    when (action) {
                        "create" -> {
                            val intent = Intent(Intent.ACTION_INSERT).apply {
                                type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
                                putExtra(android.provider.ContactsContract.Intents.Insert.NAME, name)
                                putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, phone)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent); "创建联系人界面已打开"
                        }
                        else -> "暂不支持 action=$action"
                    }
                }
                "search_contacts" -> {
                    val q = args.optString("query")
                    val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(q))
                    val cur = context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME, android.provider.ContactsContract.PhoneLookup.NUMBER), null, null, null)
                    val sb = StringBuilder()
                    cur?.use { while (it.moveToNext()) sb.appendLine("  ${it.getString(0)} : ${it.getString(1)}") }
                    sb.toString().ifBlank { "未找到 $q" }
                }

                // ── 位置 / 设备 ──
                "get_location" -> {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                    if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) "需要定位权限"
                    else {
                        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        loc?.let { "经度 ${it.longitude}, 纬度 ${it.latitude}, 精度 ${it.accuracy}m" } ?: "无定位记录"
                    }
                }
                "device_status" -> XiaomiApi.getDeviceInfo() + "\n" + (execRoot("dumpsys batterystats | head -5") ?: "")
                "send_notification" -> {
                    val title = args.optString("title"); val content = args.optString("content")
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val ch = android.app.NotificationChannel("mbclaw_agent", "MBclaw Agent", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                        nm.createNotificationChannel(ch)
                    }
                    val n = androidx.core.app.NotificationCompat.Builder(context, "mbclaw_agent")
                        .setContentTitle(title).setContentText(content)
                        .setSmallIcon(android.R.drawable.ic_dialog_info).build()
                    nm.notify(System.currentTimeMillis().toInt(), n); "通知已发送"
                }
                "send_intent" -> {
                    val action = args.optString("action"); val data = args.optString("data").ifBlank { null }
                    val target = args.optString("target", "activity")
                    val intent = Intent(action).apply { data?.let { setData(Uri.parse(it)) }; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    when (target) {
                        "broadcast" -> { context.sendBroadcast(intent); "广播已发送" }
                        "service"   -> { context.startService(intent); "服务已启动" }
                        else        -> { context.startActivity(intent); "Activity 已启动" }
                    }
                }

                // ── 文件系统 (root 直接 cat/echo/cp 等) ──
                "write_file" -> {
                    // bug.2: 写入前自动备份原文件（如果存在），高风险路径需 confirm
                    val path = args.optString("path"); val content = args.optString("content")
                    val confirm = args.optBoolean("confirm", false)
                    val risk = SafeOps.pathRisk(path)
                    if (risk == SafeOps.Risk.HIGH && !confirm) {
                        "⚠️ $path 属于系统路径，写入可能导致系统不稳定。如确认，请传 confirm=true"
                    } else {
                        val existed = if (tier.hasRoot)
                            (execRoot("test -f '$path' && echo Y") ?: "").contains("Y")
                        else java.io.File(path).exists()
                        val backupNote = if (existed) {
                            val (ok, msg) = SafeOps.backupFile(context, path)
                            if (ok) "💾 已备份原文件: $msg\n" else "⚠️ 备份失败: $msg\n"
                        } else ""
                        if (tier.hasRoot) {
                            val tmp = java.io.File(context.cacheDir, "mbclaw_w_${System.currentTimeMillis()}")
                            tmp.writeText(content)
                            execRoot("mkdir -p \"$(dirname '$path')\" && cp '${tmp.absolutePath}' '$path' && chmod 644 '$path'")
                            tmp.delete()
                            backupNote + "✅ 已写入 $path"
                        } else {
                            try {
                                java.io.File(path).also { it.parentFile?.mkdirs() }.writeText(content)
                                backupNote + "✅ 已写入 $path"
                            } catch (e: Exception) { "失败: ${e.message}" }
                        }
                    }
                }
                "append_file" -> {
                    val path = args.optString("path"); val content = args.optString("content")
                    if (tier.hasRoot) execRoot("printf '%s' '${content.replace("'", "'\\''")}' >> '$path' && echo OK") ?: "失败"
                    else try { java.io.File(path).appendText(content); "已追加" } catch (e: Exception) { "失败: ${e.message}" }
                }
                "edit_file" -> {
                    // bug.2: 编辑前自动备份
                    val path = args.optString("path"); val oldT = args.optString("old_text"); val newT = args.optString("new_text")
                    val confirm = args.optBoolean("confirm", false)
                    val risk = SafeOps.pathRisk(path)
                    if (risk == SafeOps.Risk.HIGH && !confirm) "⚠️ $path 高风险，需 confirm=true"
                    else try {
                        val f = java.io.File(path)
                        val txt = if (tier.hasRoot) execRoot("cat '$path'") ?: f.readText() else f.readText()
                        if (oldT !in txt) "未找到 old_text"
                        else {
                            val (ok, msg) = SafeOps.backupFile(context, path)
                            val backupNote = if (ok) "💾 已备份: $msg\n" else ""
                            val nu = txt.replace(oldT, newT)
                            if (tier.hasRoot) {
                                val tmp = java.io.File(context.cacheDir, "mbclaw_e_${System.currentTimeMillis()}")
                                tmp.writeText(nu)
                                execRoot("cp '${tmp.absolutePath}' '$path'")
                                tmp.delete()
                            } else f.writeText(nu)
                            backupNote + "✅ 已替换"
                        }
                    } catch (e: Exception) { "失败: ${e.message}" }
                }
                "delete_file" -> {
                    // bug.2: 删除前自动备份
                    val path = args.optString("path")
                    val confirm = args.optBoolean("confirm", false)
                    val risk = SafeOps.pathRisk(path)
                    if (risk == SafeOps.Risk.HIGH && !confirm) "⚠️ $path 是系统路径，删除可能破坏系统。如确认，请传 confirm=true"
                    else {
                        val (ok, msg) = SafeOps.backupFile(context, path)
                        val backupNote = if (ok) "💾 已备份: $msg\n" else "⚠️ 备份跳过: $msg\n"
                        val r = if (tier.hasRoot) execRoot("rm -rf '$path' && echo OK")
                                else try { if (java.io.File(path).deleteRecursively()) "OK" else null } catch (_: Exception) { null }
                        backupNote + if (r?.contains("OK") == true) "✅ 已删除 $path" else "❌ 删除失败"
                    }
                }
                "copy_file" -> execRoot("cp -r '${args.optString("src")}' '${args.optString("dst")}' && echo OK") ?: try { java.io.File(args.optString("src")).copyRecursively(java.io.File(args.optString("dst")), true); "已拷贝" } catch (e: Exception) { "失败: ${e.message}" }
                "move_file" -> execRoot("mv '${args.optString("src")}' '${args.optString("dst")}' && echo OK") ?: try { java.io.File(args.optString("src")).renameTo(java.io.File(args.optString("dst"))).let { if (it) "已移动" else "失败" } } catch (e: Exception) { "失败: ${e.message}" }
                "list_files" -> execRoot("ls -la '${args.optString("path")}' 2>&1 | head -50") ?: try { java.io.File(args.optString("path")).listFiles()?.joinToString("\n") { "  ${if (it.isDirectory) "d" else "-"} ${it.name}" } ?: "目录为空" } catch (e: Exception) { "失败: ${e.message}" }
                "search_files" -> execRoot("find '${args.optString("path")}' -name '${args.optString("pattern")}' 2>/dev/null | head -50") ?: "需 Root"
                "file_grep" -> execRoot("grep -rn '${args.optString("pattern")}' '${args.optString("path")}' 2>/dev/null | head -50") ?: "需 Root"
                "file_info" -> execRoot("stat '${args.optString("path")}' 2>&1") ?: try { val f = java.io.File(args.optString("path")); if (f.exists()) "大小 ${f.length()} | 可读 ${f.canRead()} | mtime ${java.util.Date(f.lastModified())}" else "不存在" } catch (e: Exception) { "失败: ${e.message}" }

                // ── 浏览器 (用系统 Intent 唤起系统浏览器，后台 headless 需 webview) ──
                "browser_open" -> { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(args.optString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "已打开 ${args.optString("url")}" }
                "browser_extract" -> "需后台浏览器引擎（roadmap，先用 url_fetch 替代）"
                "browser_click" -> "需后台浏览器引擎（roadmap）"
                "browser_input" -> "需后台浏览器引擎（roadmap）"
                "browser_close" -> "浏览器已交还系统"

                // ── Web ──
                "url_fetch" -> {
                    try {
                        val u = java.net.URL(args.optString("url"))
                        val conn = u.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 8000; conn.readTimeout = 8000
                        val txt = conn.inputStream.bufferedReader().readText().take(3000)
                        "HTTP ${conn.responseCode}\n${txt}"
                    } catch (e: Exception) { "失败: ${e.message}" }
                }
                "web_search" -> {
                    try {
                        val q = java.net.URLEncoder.encode(args.optString("query"), "UTF-8")
                        val u = java.net.URL("https://www.bing.com/search?q=$q")
                        val conn = u.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 8000; conn.readTimeout = 8000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 MBclaw")
                        val html = conn.inputStream.bufferedReader().readText()
                        // 粗暴抽 h2 标题
                        val titles = Regex("<h2[^>]*>([^<]+)</h2>").findAll(html).map { it.groupValues[1] }.take(8).toList()
                        if (titles.isEmpty()) "无结果" else titles.joinToString("\n") { "• $it" }
                    } catch (e: Exception) { "失败: ${e.message}" }
                }

                // ── 日历 ──
                "create_calendar_event" -> {
                    val title = args.optString("title"); val start = args.optString("start_time")
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = android.provider.CalendarContract.Events.CONTENT_URI
                        putExtra(android.provider.CalendarContract.Events.TITLE, title)
                        putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent); "日历创建界面已打开: $title"
                }
                "read_calendar" -> {
                    val uri = android.provider.CalendarContract.Events.CONTENT_URI
                    val cur = context.contentResolver.query(uri, arrayOf("_id", "title", "dtstart"), null, null, "dtstart DESC LIMIT 10")
                    val sb = StringBuilder()
                    cur?.use { while (it.moveToNext()) sb.appendLine("  [${it.getString(0)}] ${it.getString(1)} @ ${java.util.Date(it.getLong(2))}") }
                    sb.toString().ifBlank { "无事件或缺权限" }
                }
                "update_calendar_event" -> "事件 ${args.optString("event_id")} — 暂走 Intent，请手动修改"
                "delete_calendar_event" -> "事件 ${args.optString("event_id")} — 暂走 Intent，请手动删除"

                // ── 通话 ──
                "call_log_list" -> {
                    val uri = android.provider.CallLog.Calls.CONTENT_URI
                    val cur = context.contentResolver.query(uri, arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.TYPE), null, null, "${android.provider.CallLog.Calls.DATE} DESC")
                    val limit = args.optInt("limit", 20); val sb = StringBuilder(); var i = 0
                    cur?.use { while (it.moveToNext() && i++ < limit) sb.appendLine("  ${it.getString(0)} @ ${java.util.Date(it.getLong(1))} type=${it.getInt(2)}") }
                    sb.toString().ifBlank { "无记录或缺权限" }
                }
                "call_log_delete" -> execRoot("content delete --uri content://call_log/calls --where '_id=${args.optString("id")}'") ?: "需 Root"
                "hangup_phone" -> execRoot("input keyevent KEYCODE_ENDCALL") ?: "需 Root"
                "dial_phone" -> { systemAmStart(Intent.ACTION_CALL, "tel:${args.optString("phone")}"); "正在拨号 ${args.optString("phone")}" }

                // ── 媒体 ──
                "camera" -> {
                    val act = args.optString("action")
                    val intent = if (act == "video") Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE) else Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent); "相机已打开 ($act)"
                }
                "control_media" -> {
                    val keycode = when (args.optString("action")) {
                        "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                        "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                        "toggle" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "forward" -> android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        "rewind" -> android.view.KeyEvent.KEYCODE_MEDIA_REWIND
                        "volume_up" -> android.view.KeyEvent.KEYCODE_VOLUME_UP
                        "volume_down" -> android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                        else -> 0
                    }
                    if (keycode > 0) { execRoot("input keyevent $keycode") ?: run { audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keycode)); audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keycode)); "媒体键 ${args.optString("action")}" } } else "未知 action"
                }
                "get_media_info" -> {
                    val mm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
                    try {
                        val sessions = mm.getActiveSessions(null)
                        if (sessions.isEmpty()) "无活动媒体会话"
                        else sessions.first().let { ctrl -> val md = ctrl.metadata; "应用 ${ctrl.packageName}\n标题 ${md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)}\n歌手 ${md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)}" }
                    } catch (e: SecurityException) { "需通知使用权" }
                }
                "list_media_images" -> {
                    val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val cur = context.contentResolver.query(uri, arrayOf("_id", "_display_name", "_size"), null, null, "date_added DESC")
                    val limit = args.optInt("limit", 20); val sb = StringBuilder(); var i = 0
                    cur?.use { while (it.moveToNext() && i++ < limit) sb.appendLine("  [${it.getString(0)}] ${it.getString(1)} (${it.getLong(2)/1024}KB)") }
                    sb.toString().ifBlank { "无图片或缺权限" }
                }
                "media_store" -> "media_store action=${args.optString("action")} — 复用 list_media_images / get_file_info"

                // ── 时间 / 通用 ──
                "get_current_time" -> {
                    val fmt = args.optString("format", "iso")
                    val now = java.util.Date()
                    when (fmt) {
                        "iso" -> java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(now)
                        "human" -> java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss").format(now)
                        "epoch" -> (now.time / 1000).toString()
                        else -> now.toString()
                    }
                }
                "check_permissions" -> {
                    val perms = listOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_SMS, android.Manifest.permission.CALL_PHONE, android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    perms.joinToString("\n") { p -> "  ${if (context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) "✅" else "❌"} ${p.substringAfterLast('.')}" }
                }
                "timer" -> {
                    val after = args.optInt("after_seconds", 60)
                    val task = args.optString("task", "提醒")
                    Handler(Looper.getMainLooper()).postDelayed({
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.createNotificationChannel(android.app.NotificationChannel("mbclaw_timer", "Timer", android.app.NotificationManager.IMPORTANCE_HIGH))
                        nm.notify(System.currentTimeMillis().toInt(), androidx.core.app.NotificationCompat.Builder(context, "mbclaw_timer").setContentTitle("MBclaw 定时").setContentText(task).setSmallIcon(android.R.drawable.ic_dialog_alert).build())
                    }, after * 1000L)
                    "已设定 ${after}s 后: $task"
                }
                "get_weather" -> {
                    val city = args.optString("city", "")
                    // 兜底：调 wttr.in（无需 key）
                    try {
                        val u = java.net.URL("https://wttr.in/${java.net.URLEncoder.encode(city.ifBlank { "auto" }, "UTF-8")}?format=3&lang=zh")
                        val conn = u.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000; conn.readTimeout = 5000
                        conn.inputStream.bufferedReader().readText()
                    } catch (e: Exception) { "天气获取失败: ${e.message}" }
                }

                // ── App ──
                "app_manager" -> {
                    val act = args.optString("action"); val pkg = args.optString("package_name")
                    when (act) {
                        "info" -> { systemAmStart(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$pkg"); "应用详情已打开" }
                        "enable" -> execRoot("pm enable $pkg") ?: "需 Root"
                        "disable" -> execRoot("pm disable $pkg") ?: "需 Root"
                        "uninstall" -> { systemAmStart(Intent.ACTION_DELETE, "package:$pkg"); "卸载界面已打开" }
                        else -> "未知 action"
                    }
                }
                "app_shortcut" -> "shortcut action=${args.optString("action")} — 需 ShortcutManager API (roadmap)"
                "app_usage" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                        val end = System.currentTimeMillis(); val start = end - args.optInt("days", 7) * 86400_000L
                        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
                        if (stats.isNullOrEmpty()) "需「使用情况访问」权限" else stats.sortedByDescending { it.totalTimeInForeground }.take(10).joinToString("\n") { "  ${it.packageName}: ${it.totalTimeInForeground/1000/60}min" }
                    } else "需 Android 5.1+"
                }

                // ── 历史 ──
                "search_history" -> {
                    val q = args.optString("query"); val limit = args.optInt("limit", 10)
                    db.searchMemory(q, limit).joinToString("\n") { "• [${it.key}] ${it.value.take(120)}" }.ifBlank { "无匹配" }
                }
                "load_message" -> "消息 ${args.optString("message_id")} — TODO 接 MessageRepo"

                // ── 本地沙箱 (root shell) ──
                // 修复 (bug.1): app UID 不能写 /data/local/tmp/，必须用 root 通道写脚本
                // 方案 A：通过 heredoc 用 root shell 直接写文件
                // 方案 B：先写 app 私有目录(app UID 有权)，然后 root 执行
                "local_sandbox_run" -> {
                    val lang = args.optString("lang"); val code = args.optString("code")
                    val timeout = args.optInt("timeout_ms", 15000)
                    if (!tier.hasRoot) "需 Root 权限"
                    else {
                        // 用 app 私有目录写脚本（app UID 有权），然后 root 执行（绕过 SELinux 的 /data/local/tmp 限制）
                        val tmpFile = java.io.File(context.cacheDir, "mbclaw_box_${System.currentTimeMillis()}.${if (lang == "python") "py" else "sh"}")
                        try {
                            tmpFile.writeText(code)
                            tmpFile.setReadable(true, false)
                            tmpFile.setExecutable(true, false)
                            val timeoutS = (timeout / 1000).coerceAtLeast(1)
                            val cmd = when (lang) {
                                "python" -> "timeout $timeoutS python3 '${tmpFile.absolutePath}' 2>&1"
                                "shell"  -> "timeout $timeoutS sh     '${tmpFile.absolutePath}' 2>&1"
                                else     -> return@withContext "未知 lang=$lang"
                            }
                            val out = execRoot(cmd) ?: "root 命令执行失败"
                            tmpFile.delete()
                            // python3 不存在的兜底
                            if (lang == "python" && out.contains("not found", ignoreCase = true)) {
                                "❌ python3 未安装。可用 'pkg install python' (Termux) 或推送静态二进制到 /data/adb/mbclaw/bin/"
                            } else out.ifBlank { "(无输出)" }
                        } catch (e: Exception) {
                            tmpFile.delete()
                            "失败: ${e.message}"
                        }
                    }
                }

                // ── 子 Agent ──
                "list_agents" -> "MBclaw / Hand / Hermes / Realtime — 共 4 个内置 agent"
                "start_agent" -> "切换至 agent=${args.optString("agent_id")} (需 UI 路由支持)"

                // ═══════════════════════════════════════════════════
                // 👁 视觉/智能手 — 给 agent 装眼睛
                // ═══════════════════════════════════════════════════
                "see_screen" -> {
                    // 主要工具: 返回屏幕所有可交互元素的索引列表
                    val elements = ScreenAnalyzer.snapshot(context)
                    ScreenAnalyzer.formatForLLM(elements)
                }
                "click_by_index" -> {
                    val idx = args.optInt("index", -1)
                    val el = ScreenAnalyzer.getCachedElement(idx)
                    if (el == null) "❌ 找不到索引 $idx, 请先调 see_screen 获取最新列表"
                    else {
                        val x = el.centerX; val y = el.centerY
                        // ★ v4.6: TouchInjector 优先
                        val ok = TouchInjector.tap(context, x, y)
                        if (ok) "👆 [$idx] ${el.text.take(20)} @($x,$y) [TouchInjector]"
                        else CapabilityRouter.exec(context,
                            onRoot = { t -> t.shellRoot("input tap $x $y && echo OK")?.let { "👆 [$idx] [ROOT]" } },
                            onAdb = { t -> t.shellAdb("input tap $x $y")?.let { "👆 [$idx] [ADB]" } },
                            onUI = {
                                val svc = MBclawAccessibilityService.instance
                                if (svc?.clickAt(x.toFloat(), y.toFloat()) == true) "👆 [$idx] [UI]" else null
                            },
                        ).output
                    }
                }
                "input_by_index" -> {
                    val idx = args.optInt("index", -1)
                    val text = args.optString("text")
                    val el = ScreenAnalyzer.getCachedElement(idx)
                    if (el == null) "❌ 找不到索引 $idx"
                    else {
                        // ★ v4.6: TouchInjector 优先
                        val tapOk = TouchInjector.tap(context, el.centerX, el.centerY)
                        if (tapOk) {
                            Thread.sleep(200) // 等焦点切换
                            val inputOk = TouchInjector.inputText(context, text)
                            if (inputOk) "⌨️ 输入到 [$idx]: $text [TouchInjector]"
                            else "⚠️ 点击成功但输入失败 [$idx]"
                        } else {
                            val r = CapabilityRouter.exec(context,
                                onRoot = { t ->
                                    t.shellRoot("input tap ${el.centerX} ${el.centerY}; sleep 0.2; input text '${text.replace("'", "'\\''")}' && echo OK")?.let {
                                        "⌨️ 输入到 [$idx]: $text [ROOT]"
                                    }
                                },
                                onAdb = { t ->
                                    t.shellAdb("input tap ${el.centerX} ${el.centerY}")
                                    t.shellAdb("input text '${text.replace("'", "'\\''")}'")?.let { "⌨️ [$idx] [ADB]" }
                                },
                                onUI = {
                                    val svc = MBclawAccessibilityService.instance
                                    svc?.clickAt(el.centerX.toFloat(), el.centerY.toFloat())
                                    if (svc?.inputText(text) == true) "⌨️ [$idx] [UI]" else null
                                },
                            )
                            r.output
                        }
                    }
                }
                "find_by_text" -> {
                    // 按文字找元素
                    val q = args.optString("text", "")
                    val elements = ScreenAnalyzer.snapshot(context)
                    val hits = elements.filter { it.text.contains(q, ignoreCase = true) }
                    if (hits.isEmpty()) "❌ 未找到包含「$q」的元素\n当前可见: " +
                        elements.filter { it.text.isNotBlank() }.take(10).joinToString(" | ") { it.text.take(15) }
                    else hits.joinToString("\n") { it.summary() }
                }
                "wait_screen" -> {
                    // 等待 N 秒后再 snapshot (用于等界面跳转完成)
                    val ms = args.optInt("ms", 1500)
                    kotlinx.coroutines.delay(ms.toLong())
                    val elements = ScreenAnalyzer.snapshot(context)
                    "⏳ 等待 ${ms}ms 后: ${ScreenAnalyzer.formatForLLM(elements, 30)}"
                }
                // ★ v4.7: VLM 视觉定位通道
                "vision_locate" -> {
                    val desc = args.optString("description", "")
                    if (desc.isBlank()) "❌ 请提供 description (如 '点击发送按钮')"
                    else {
                        val loc = VisionLocator.locate(context, settings, desc)
                        if (loc.success) {
                            when (loc.action) {
                                "Tap" -> {
                                    val ok = TouchInjector.tap(context, loc.x, loc.y)
                                    "👁 VLM定位: (${loc.x},${loc.y}) 置信度${"%.1f".format(loc.confidence*100)}%\n" +
                                    "思考: ${loc.thinking.take(100)}\n" +
                                    "执行: ${if (ok) "✅ 已点击" else "❌ 点击失败"}"
                                }
                                "Type" -> {
                                    val ok = TouchInjector.inputText(context, loc.text)
                                    "👁 VLM定位: 输入 \"${loc.text.take(30)}\"\n执行: ${if (ok) "✅" else "❌"}"
                                }
                                "Launch" -> {
                                    val pkg = when (loc.text.lowercase()) {
                                        "微信", "wechat" -> "com.tencent.mm"
                                        "qq" -> "com.tencent.mobileqq"
                                        "设置", "settings" -> "com.android.settings"
                                        else -> loc.text
                                    }
                                    if (pkg.contains(".")) {
                                        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                                        if (intent != null) {
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        }
                                    }
                                    "👁 VLM定位: 启动 ${loc.text}"
                                }
                                "Back" -> {
                                    TouchInjector.keyEvent(context, 4)
                                    "👁 VLM定位: 返回"
                                }
                                "Home" -> {
                                    TouchInjector.keyEvent(context, 3)
                                    "👁 VLM定位: 主页"
                                }
                                "Swipe" -> {
                                    val dm = context.resources.displayMetrics
                                    val ok = TouchInjector.swipe(context, loc.x, loc.y, dm.widthPixels/2, dm.heightPixels/2, 500)
                                    "👁 VLM定位: 滑动\n执行: ${if (ok) "✅" else "❌"}"
                                }
                                else -> "👁 VLM定位: ${loc.action} (未处理)"
                            }
                        } else {
                            "❌ VLM定位失败: ${loc.errorReason}"
                        }
                    }
                }

                else -> "未知工具: $toolName"
                }
            } catch (e: Exception) { "❌ ${toolName}: ${e.message}" }
        }
        return result.toString()
    }
}
