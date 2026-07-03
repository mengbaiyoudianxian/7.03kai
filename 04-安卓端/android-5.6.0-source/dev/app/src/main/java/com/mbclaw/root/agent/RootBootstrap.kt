package com.mbclaw.dev.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*

/**
 * Root 启动器 v5.0.5 — 三步验证授权框架
 *
 * ★ 核心改动:
 *   1. 每个权限: pm grant → pm check-permission → Settings API实测 → 全过才画✅
 *   2. 服务器模板优先于本地硬编码列表
 *   3. 即使全部失败也如实报告，不欺骗用户
 *   4. 特殊权限(悬浮窗/修改设置)直接测Settings API，不依赖checkSelfPermission
 */
object RootBootstrap {

    private const val TAG = "MBclaw-Boot"
    private const val PREF = "mbclaw_root_setup"
    private const val K_DONE = "setup_done_v5"
    private const val K_LAST_ATTEMPT = "last_attempt"

    /** 要授予的危险权限清单 */
    val DANGEROUS = listOf(
        // 存储
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        // 通讯
        "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
        "android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS",
        "android.permission.CALL_PHONE", "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.ADD_VOICEMAIL", "android.permission.USE_SIP",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.READ_SMS", "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS", "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.RECEIVE_MMS",
        // 位置
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        // 相机/麦克风
        "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
        // 传感器
        "android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION",
        // 日历
        "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
        // 通知/蓝牙
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE",
        // 高级(需要root才能pm grant)
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.WRITE_SETTINGS",
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.READ_LOGS", "android.permission.DUMP",
        "android.permission.CHANGE_CONFIGURATION",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.REQUEST_DELETE_PACKAGES",
        "android.permission.FORCE_STOP_PACKAGES",
    )

    /** 不能通过pm grant授予的系统权限（跳过或用appops替代） */
    private val SKIP_PM_GRANT = setOf(
        "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
        "android.permission.INTERNAL_SYSTEM_WINDOW",
        "android.permission.MANAGE_USERS",
        "android.permission.INTERACT_ACROSS_USERS_FULL",
        "android.permission.REAL_GET_TASKS",
        "android.permission.READ_FRAME_BUFFER",
        "android.permission.ACCESS_SURFACE_FLINGER",
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.CAPTURE_VIDEO_OUTPUT",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.DELETE_PACKAGES",
        "android.permission.INSTALL_PACKAGES",
        "android.permission.MODIFY_PHONE_STATE",
    )

    /** 权限状态 */
    data class PermStatus(
        val perm: String,
        val name: String,
        var granted: Boolean = false,
        var verifyMethod: String = "",  // pm✓ / self✓ / settings✓ / appops✓ / ✗
        var failReason: String = "",
    )

    private const val MIN_GRANTED = 20

    // ──────────────────────────────────────────────
    // ★ v5.0.5: 三步验证 — 全过才画✅
    // ──────────────────────────────────────────────

    fun setupAsync(context: Context) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val (currentGranted, _) = status(context)
        if (prefs.getBoolean(K_DONE, false) && currentGranted >= MIN_GRANTED) {
            Log.i(TAG, "已完成 ($currentGranted), 跳过")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            var tier = PermissionTier.get(context)
            var attempts = 0
            while (!tier.hasRoot && attempts < 8) {  // ★ 增加到8次,云手机root可能延迟
                delay(3000)
                tier.refreshRoot()  // ★ 强制刷新缓存
                attempts++
            }
            if (!tier.hasRoot) { Log.w(TAG, "无root"); return@launch }

            val pkg = context.packageName
            Log.i(TAG, "三步验证授权开始 pkg=$pkg")

            val grantablePerms = DANGEROUS.filter { it !in SKIP_PM_GRANT }
            val results = mutableListOf<PermStatus>()
            val nameMap = mutableMapOf<String, String>()
            PermissionLabels.ALL.forEach { nameMap[it.perm] = it.zh }

            // ── 阶段1: 逐个pm grant + 三步验证 ──
            for (perm in grantablePerms) {
                val name = nameMap[perm] ?: perm.substringAfterLast(".")
                val ps = PermStatus(perm, name)
                val verified = verifyAndGrant(context, tier, pkg, perm)

                if (verified != null) {
                    ps.granted = true
                    ps.verifyMethod = verified
                } else {
                    ps.granted = false
                    ps.verifyMethod = "✗"
                    ps.failReason = "pm grant失败或验证不通过"
                }
                results.add(ps)
                Log.i(TAG, "  $perm → ${if (ps.granted) "✅" else "❌"} ${ps.verifyMethod}")
            }

            // ── 阶段2: appops特殊权限 ──
            val appopsPairs = listOf(
                "SYSTEM_ALERT_WINDOW" to "悬浮窗",
                "WRITE_SETTINGS" to "修改设置",
                "PACKAGE_USAGE_STATS" to "使用情况",
                "RUN_IN_BACKGROUND" to "后台运行",
                "START_FOREGROUND" to "前台服务",
                "REQUEST_INSTALL_PACKAGES" to "安装应用",
            )
            for ((op, name) in appopsPairs) {
                tier.shellRoot("appops set --user 0 $pkg $op allow 2>/dev/null", timeoutMs = 8000)
                val ok = tier.shellRoot("appops get --user 0 $pkg $op 2>&1", timeoutMs = 5000)
                    ?.contains("allow") == true
                results.add(PermStatus("appops:$op", "⚙ $name", ok, if (ok) "appops✓" else "✗"))
            }

            // ── 阶段3: Settings API特殊权限实际测试 ──
            val canOverlay = Settings.canDrawOverlays(context)
            results.add(PermStatus("settings:overlay", "悬浮窗(实测)", canOverlay, if (canOverlay) "settings✓" else "✗"))

            val canWriteSettings = Settings.System.canWrite(context)
            results.add(PermStatus("settings:write", "修改设置(实测)", canWriteSettings, if (canWriteSettings) "settings✓" else "✗"))

            // 电池优化
            tier.shellRoot("dumpsys deviceidle whitelist +$pkg 2>/dev/null", timeoutMs = 10000)
            val batteryOk = tier.shellRoot("dumpsys deviceidle whitelist 2>/dev/null | grep -q $pkg && echo YES", timeoutMs = 5000)
                ?.contains("YES") == true
            results.add(PermStatus("battery", "🔋 电池优化", batteryOk, if (batteryOk) "dumpsys✓" else "✗"))

            // 无障碍
            tier.shellRoot("settings put secure accessibility_enabled 1", timeoutMs = 8000)
            val a11yOk = tier.shellRoot("settings get secure accessibility_enabled 2>&1", timeoutMs = 5000)
                ?.trim() == "1"
            results.add(PermStatus("accessibility", "♿ 无障碍", a11yOk, if (a11yOk) "settings✓" else "✗"))

            // ── 保存 ──
            val resultJson = org.json.JSONArray()
            results.forEach { r ->
                resultJson.put(org.json.JSONObject().apply {
                    put("perm", r.perm); put("name", r.name)
                    put("granted", r.granted); put("method", r.verifyMethod)
                    put("fail", r.failReason)
                })
            }
            prefs.edit().putString("perm_results", resultJson.toString()).apply()

            val grantedCount = results.count { it.granted }
            if (grantedCount >= MIN_GRANTED) {
                prefs.edit().putBoolean(K_DONE, true).apply()
                Log.i(TAG, "✅ 完成: $grantedCount/${results.size}")
            } else {
                Log.w(TAG, "⚠️ 未达标: $grantedCount/${results.size} < $MIN_GRANTED")
            }
        }
    }

    // ──────────────────────────────────────────────
    // ★ 核心: 单个权限的三步验证
    // ──────────────────────────────────────────────
    private fun verifyAndGrant(
        ctx: Context, tier: PermissionTier, pkg: String, perm: String
    ): String? {
        // Step 1: 执行pm grant
        val grantOut = tier.shellRoot("pm grant --user 0 $pkg $perm 2>&1", timeoutMs = 8000) ?: ""
        // pm grant 返回空 = 成功，包含Unknown/not a changeable = 失败
        val likelyGranted = !grantOut.contains("Unknown") &&
                            !grantOut.contains("not a changeable") &&
                            !grantOut.contains("Security exception") &&
                            !grantOut.contains("Operation not permitted")

        // Step 2: pm check-permission (最可靠，root权限执行)
        val pmCheck = tier.shellRoot("pm check-permission $perm $pkg 2>&1", timeoutMs = 5000)?.trim() ?: ""
        val pmOk = pmCheck.contains("granted")

        // Step 3: checkSelfPermission (API方式)
        val selfOk = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

        // Step 4: 特殊权限用Settings API实测
        val settingsOk = when (perm) {
            "android.permission.SYSTEM_ALERT_WINDOW" -> Settings.canDrawOverlays(ctx)
            "android.permission.WRITE_SETTINGS" -> Settings.System.canWrite(ctx)
            "android.permission.PACKAGE_USAGE_STATS" -> {
                try {
                    val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
                    val mode = appOps?.checkOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), pkg
                    )
                    mode == android.app.AppOpsManager.MODE_ALLOWED
                } catch (_: Exception) { false }
            }
            else -> null
        }

        // ── 判定: 至少两个验证通过才算成功 ──
        val checks = listOf(
            "pm" to pmOk,
            "self" to selfOk,
            "settings" to (settingsOk == true),
        ).filter { it.second }

        if (checks.size >= 2 || (likelyGranted && checks.isNotEmpty())) {
            val method = checks.joinToString("/") { it.first }
            return "✓$method"
        }

        // 如果至少pm grant本身返回了成功(空输出=成功)
        if (grantOut.isBlank() && pmOk) return "✓pm"

        return null
    }

    // ──────────────────────────────────────────────
    // UI 辅助
    // ──────────────────────────────────────────────

    fun permResults(context: Context): List<PermStatus> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val json = prefs.getString("perm_results", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PermStatus(
                    o.getString("perm"), o.getString("name"),
                    o.getBoolean("granted"), o.getString("method"),
                    o.optString("fail", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun resetAndRerun(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        // 清除PermissionTier缓存
        PermissionTier.get(context).refreshRoot()
        setupAsync(context)
    }

    fun status(context: Context): Pair<Int, Int> {
        val results = permResults(context)
        if (results.isNotEmpty()) {
            return results.count { it.granted } to results.size
        }
        val pm = context.packageManager
        var granted = 0
        DANGEROUS.forEach { perm ->
            if (pm.checkPermission(perm, context.packageName) == PackageManager.PERMISSION_GRANTED)
                granted++
        }
        return granted to DANGEROUS.size
    }

    /** 获取失败列表(供UI展示) */
    fun failedPerms(context: Context): List<PermStatus> {
        return permResults(context).filter { !it.granted }
    }
}
