package com.mbclaw.nonroot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mbclaw.nonroot.agent.AgentService

/** 开机自启 — 启动 AgentService */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, AgentService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

/** 主动建议广播处理 */
class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra("message") ?: return
        val action = intent.getStringExtra("action") ?: return
        // 通过通知栏告知用户
        // 如果 AgentService 在运行，它会处理这个建议
    }
}
