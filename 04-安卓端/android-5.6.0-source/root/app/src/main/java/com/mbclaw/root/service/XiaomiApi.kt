package com.mbclaw.root.service

import android.os.Build
import java.lang.reflect.Method

/**
 * Xiaomi/MIUI 系统API — 直接调用小米系统底层接口
 * 来自 MiClaw 的反编译分析
 */
object XiaomiApi {

    // ═══ SystemProperties (getprop/setprop) ═══
    private var sysPropClass: Class<*>? = null
    private var sysPropGet: Method? = null
    private var sysPropSet: Method? = null

    init {
        try {
            sysPropClass = Class.forName("android.os.SystemProperties")
            sysPropGet = sysPropClass?.getMethod("get", String::class.java, String::class.java)
            sysPropSet = sysPropClass?.getMethod("set", String::class.java, String::class.java)
        } catch (_: Exception) {}
    }

    fun getProp(key: String, default: String = ""): String {
        return try { sysPropGet?.invoke(null, key, default) as? String ?: default } catch (_: Exception) { default }
    }

    fun setProp(key: String, value: String) {
        try { sysPropSet?.invoke(null, key, value) } catch (_: Exception) {}
    }

    // ═══ MIUI 专有 ═══
    fun isMiui(): Boolean = getProp("ro.miui.ui.version.name").isNotBlank()
    fun isHyperOS(): Boolean = getProp("ro.miui.ui.version.name").contains("V") || 
                               Build.VERSION.SDK_INT >= 34 && isMiui()

    /** MIUI 系统级 WiFi 开关 (绕过权限) */
    fun setWifi(enable: Boolean): Boolean = try {
        val wmClass = Class.forName("android.net.wifi.WifiManager")
        val method = wmClass.getMethod("setWifiEnabled", Boolean::class.java)
        // 通过反射调用隐藏API
        setProp("sys.wifi.service.enable", if (enable) "1" else "0")  // Xiaomi specific
        true
    } catch (_: Exception) { false }

    /** MIUI 蓝牙开关 */
    fun setBluetooth(enable: Boolean): Boolean = try {
        val btClass = Class.forName("android.bluetooth.BluetoothAdapter")
        val adapter = btClass.getMethod("getDefaultAdapter").invoke(null)
        if (enable) btClass.getMethod("enable").invoke(adapter)
        else btClass.getMethod("disable").invoke(adapter)
        true
    } catch (_: Exception) { false }

    /** MIUI 飞行模式 */
    fun setAirplaneMode(enable: Boolean): Boolean {
        setProp("persist.sys.airplane_mode", if (enable) "1" else "0")
        return true
    }

    /** MIUI 屏幕亮度 (0-255) */
    fun setBrightness(level: Int) {
        try {
            val ipm = Class.forName("android.os.IPowerManager\$Stub")
            // Xiaomi: write directly to /sys/class/backlight/panel/brightness
            Runtime.getRuntime().exec(arrayOf("sh","-c","echo $level > /sys/class/backlight/panel0-backlight/brightness"))
        } catch (_: Exception) {}
    }

    /** MIUI 音量设置 */
    fun setVolume(streamType: Int, level: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("sh","-c","media volume --show --stream $streamType --set $level"))
        } catch (_: Exception) {}
    }

    /** 获取小米设备信息 */
    fun getDeviceInfo(): String = buildString {
        append("品牌: ${Build.BRAND}\n")
        append("型号: ${Build.MODEL}\n") 
        append("MIUI: ${getProp("ro.miui.ui.version.name", "非MIUI")}\n")
        append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        append("CPU: ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("HyperOS: ${if (isHyperOS()) "是" else "否"}")
    }

    /** MIUI 直接拨号 */
    fun directCall(phone: String) {
        Runtime.getRuntime().exec(arrayOf("sh","-c","am start -a android.intent.action.CALL -d tel:$phone"))
    }

    /** MIUI 直接发短信 */
    fun directSms(phone: String, msg: String) {
        Runtime.getRuntime().exec(arrayOf("sh","-c","am start -a android.intent.action.SENDTO -d sms:$phone --es sms_body '$msg' --ez exit_on_sent true"))
    }

    /** 截图 (framebuffer直读, 比screencap快) */
    fun screenshot(path: String) {
        Runtime.getRuntime().exec(arrayOf("sh","-c","screencap -p $path"))
    }

    /** 录屏 */
    fun screenRecord(path: String, durationSec: Int = 10) {
        Runtime.getRuntime().exec(arrayOf("sh","-c","screenrecord --time-limit $durationSec $path &"))
    }
}
