package com.mbclaw.root.agent

import android.content.Context
import android.util.Log

/**
 * CapabilityRouter — 三层调度器 (修复架构级 bug)
 *
 * 调度规则 (硬性):
 *   if ROOT can do:        ALWAYS use ROOT
 *   elif ADB(Shizuku):     use ADB
 *   else if Accessibility: use UI
 *   else:                  fail
 *
 * 用法:
 *   CapabilityRouter.exec(ctx) { 
 *     onRoot = { exec("input tap 100 200") }       // root shell
 *     onAdb  = { shizukuExec("input tap 100 200") } // shizuku
 *     onUI   = { svc?.clickAt(100f, 200f) }         // 无障碍
 *   }
 *
 * 每个工具实现:
 *   1. 必须先尝试 onRoot
 *   2. onRoot 返回 null 才走 onAdb
 *   3. 全失败才 onUI
 *   4. 全部不可用 → ExecResult(failed=true)
 */
object CapabilityRouter {

    private const val TAG = "MBclaw-Router"

    data class ExecResult(
        val success: Boolean,
        val layer: String,        // "ROOT" / "ADB" / "UI" / "NONE"
        val output: String,
    )

    /**
     * 三层执行
     * onRoot 返回 null = 该层失败, 自动 fallback
     */
    fun exec(
        ctx: Context,
        onRoot: (PermissionTier) -> String? = { null },
        onAdb: (PermissionTier) -> String? = { null },
        onUI: (PermissionTier) -> String? = { null },
        failHint: String = "全部三层都不可用",
    ): ExecResult {
        val tier = PermissionTier.get(ctx)

        // Level 0: ROOT (永远优先)
        if (tier.hasRoot) {
            try {
                val r = onRoot(tier)
                if (r != null) {
                    Log.d(TAG, "Layer=ROOT result=${r.take(60)}")
                    return ExecResult(true, "ROOT", r)
                }
                Log.w(TAG, "ROOT layer returned null, fallback to ADB")
            } catch (e: Exception) {
                Log.w(TAG, "ROOT layer threw: ${e.message}")
            }
        }

        // Level 1: ADB (Shizuku)
        if (tier.hasAdb) {
            try {
                val r = onAdb(tier)
                if (r != null) return ExecResult(true, "ADB", r)
            } catch (_: Exception) {}
        }

        // Level 2: UI (无障碍)
        if (tier.hasAccessibility) {
            try {
                val r = onUI(tier)
                if (r != null) return ExecResult(true, "UI", r)
            } catch (_: Exception) {}
        }

        return ExecResult(false, "NONE", failHint)
    }

    /**
     * 当前最高可用层 (供 UI 显示)
     */
    fun bestLayer(ctx: Context): String {
        val tier = PermissionTier.get(ctx)
        return when {
            tier.hasRoot -> "ROOT"
            tier.hasAdb -> "ADB"
            tier.hasAccessibility -> "UI"
            else -> "NONE"
        }
    }
}
