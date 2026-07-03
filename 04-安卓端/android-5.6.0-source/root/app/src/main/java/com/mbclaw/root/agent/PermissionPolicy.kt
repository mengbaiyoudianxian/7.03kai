package com.mbclaw.root.agent

import android.content.Context

/**
 * 权限策略 — 每个权限有三种用户偏好：
 *   ALLOW          打开 (默认自动 grant)
 *   DENY_FOREVER   以后全部禁止 (启动时跳过, 不再请求)
 *   ASK_EACH_TIME  每次启动默认打开 (其实就是 ALLOW, 显式标记)
 *
 * 存在 SharedPreferences: mbclaw_perm_policy
 *   key = 权限完整名 (android.permission.XXX)
 *   value = ALLOW / DENY_FOREVER / ASK_EACH_TIME
 */
object PermissionPolicy {

    enum class Policy { ALLOW, DENY_FOREVER, ASK_EACH_TIME }

    private const val PREF = "mbclaw_perm_policy"

    fun get(ctx: Context, perm: String): Policy {
        val v = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(perm, null) ?: return Policy.ALLOW
        return runCatching { Policy.valueOf(v) }.getOrDefault(Policy.ALLOW)
    }

    fun set(ctx: Context, perm: String, policy: Policy) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(perm, policy.name).apply()
    }

    /** 启动时调用：返回除了被 DENY_FOREVER 之外，应该尝试授予的权限 */
    fun filterGrantable(ctx: Context, all: List<String>): List<String> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return all.filter {
            val v = sp.getString(it, null)
            v != Policy.DENY_FOREVER.name
        }
    }

    fun summary(ctx: Context, all: List<String>): Triple<Int, Int, Int> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        var allow = 0; var deny = 0; var ask = 0
        all.forEach {
            when (sp.getString(it, Policy.ALLOW.name)) {
                Policy.DENY_FOREVER.name -> deny++
                Policy.ASK_EACH_TIME.name -> ask++
                else -> allow++
            }
        }
        return Triple(allow, deny, ask)
    }
}
