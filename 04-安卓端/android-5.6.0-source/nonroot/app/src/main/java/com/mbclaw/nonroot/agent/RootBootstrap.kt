package com.mbclaw.nonroot.agent
import android.content.Context
object RootBootstrap {
    val DANGEROUS = listOf("android.permission.ACCESSIBILITY", "android.permission.INTERNET")
    fun setupAsync(context: Context) {}  // 非Root版: 无操作
    fun resetAndRerun(context: Context) {}
    fun status(context: Context): Pair<Int, Int> = 0 to 0
}
// 权限列表(供UI显示, 非Root版仅用于展示)
val DANGEROUS = listOf<String>()
