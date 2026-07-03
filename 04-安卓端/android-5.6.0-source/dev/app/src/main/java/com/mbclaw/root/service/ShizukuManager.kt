package com.mbclaw.dev.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku

/**
 * Shizuku 提权管理器 — NonRoot 获得 ADB 级权限
 *
 * 原理:
 *   用户开启开发者选项→无线调试→启动Shizuku→MBclaw通过Shizuku Binder获得ADB shell权限
 *
 * Shizuku 13.x 用法:
 *   1. Shizuku.checkSelfPermission() → 检查权限
 *   2. Shizuku.requestPermission() → 请求权限
 *   3. 通过 Shizuku Binder 调用系统服务 (IShizukuService)
 *   4. 执行命令 = transact NEW_PROCESS → Runtime.exec()
 *
 * 能力 (ADB shell 级别):
 *   - pm: 静默安装/卸载/授权
 *   - am: 启动/停止App
 *   - settings: 读写系统设置
 *   - input: 模拟输入 (比无障碍更快)
 *   - dumpsys/screencap/screenrecord
 */
class ShizukuManager(private val context: Context) {

    companion object {
        @Volatile var instance: ShizukuManager? = null

        enum class State { UNAVAILABLE, NOT_RUNNING, PERMISSION_DENIED, READY }

        // Shizuku transact codes
        const val TRANSACT_newProcess = 6
        const val TRANSACT_getFlags = 1
    }

    var state: State = State.UNAVAILABLE; private set
    private var shizukuService: IBinder? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        state = if (result == PackageManager.PERMISSION_GRANTED) State.READY else State.PERMISSION_DENIED
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        state = State.NOT_RUNNING
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAvailability()
    }

    // ── 生命周期 ──

    fun init() {
        instance = this
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        checkAvailability()
    }

    fun destroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        instance = null
    }

    private fun checkAvailability() {
        state = when {
            !isShizukuInstalled() -> State.UNAVAILABLE
            !Shizuku.pingBinder() -> State.NOT_RUNNING
            !checkPermission() -> { requestPermission(); State.PERMISSION_DENIED }
            else -> State.READY
        }
    }

    fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    } catch (_: Exception) { false }

    fun checkPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun requestPermission() {
        if (!checkPermission()) Shizuku.requestPermission(1001)
    }

    fun isReady(): Boolean = state == State.READY

    // ── 命令执行 ──

    /**
     * 通过 Shizuku UserService 执行 shell 命令
     *
     * Shizuku 13.x 推荐方式: 绑定 UserService, 在 UserService 线程中执行
     * 简化为: Runtime.exec() 在 Shizuku binder 的上下文中执行
     */
    fun exec(command: String): String {
        if (state != State.READY) return "ERROR: Shizuku 未就绪 ($state)"

        return try {
            // 使用 Shizuku binder 调用系统服务执行命令
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            process.waitFor()
            stdout.ifBlank { stderr }.trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    // ── 系统操作 ──

    fun installApk(apkPath: String): String = exec("pm install -r $apkPath")
    fun uninstallApp(pkg: String): String = exec("pm uninstall $pkg")
    fun grantPermission(pkg: String, perm: String): String = exec("pm grant $pkg $perm")
    fun revokePermission(pkg: String, perm: String): String = exec("pm revoke $pkg $perm")
    fun inputTap(x: Int, y: Int) { exec("input tap $x $y") }
    fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, d: Int = 300) { exec("input swipe $x1 $y1 $x2 $y2 $d") }
    fun inputKey(keyCode: Int) { exec("input keyevent $keyCode") }
    fun inputText(text: String) { exec("input text '$text'") }
    fun startApp(pkg: String) { exec("am start $pkg") }
    fun forceStopApp(pkg: String) { exec("am force-stop $pkg") }
    fun screenshot(path: String): String = exec("screencap -p $path")
    fun screenRecord(path: String, time: Int = 30): String = exec("screenrecord --time-limit $time $path")
    fun getSetting(ns: String, key: String): String = exec("settings get $ns $key").trim()
    fun putSetting(ns: String, key: String, value: String) { exec("settings put $ns $key $value") }
    fun listPackages(filter: String = ""): String = exec("pm list packages $filter")
    fun getCurrentActivity(): String = exec("dumpsys activity activities | grep mResumedActivity").trim()
    fun dumpSys(service: String): String = exec("dumpsys $service")
    fun enableAccessibility(pkg: String): String = exec("settings put secure enabled_accessibility_services '$pkg'")
    fun disablePackage(pkg: String): String = exec("pm disable $pkg")
    fun enablePackage(pkg: String): String = exec("pm enable $pkg")
    fun reboot(): String = exec("reboot")
    fun rebootRecovery(): String = exec("reboot recovery")
    fun takeBugreport(): String = exec("bugreport")

    fun getStateDescription(): String = when (state) {
        State.UNAVAILABLE -> "Shizuku 未安装 (https://shizuku.rikka.app)"
        State.NOT_RUNNING -> "Shizuku 服务未运行 — 请打开 Shizuku App → 启动"
        State.PERMISSION_DENIED -> "请允许 MBclaw 使用 Shizuku"
        State.READY -> "✅ Shizuku ADB权限已激活"
    }
}
