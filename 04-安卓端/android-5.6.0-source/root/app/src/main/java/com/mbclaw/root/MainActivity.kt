package com.mbclaw.root

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mbclaw.root.ui.MBclawMainScreen
import com.mbclaw.root.ui.theme.MBclawTheme

/**
 * MBclaw Root 主 Activity
 *
 * 保留 70% MiClaw 原有 UI 结构 + 替换 AI 后端为 MBclaw
 * 语音唤醒 → MBclaw 而非小爱同学
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // AgentService 由 BootReceiver 或用户手动启动

        setContent {
            // 读取用户主题偏好 (light/dark/system) 并响应即时切换
            val mode by com.mbclaw.root.ui.theme.ThemePreference.state(this)
            val isDark = when (mode) {
                "dark" -> true
                "light" -> false
                else -> {
                    val ui = resources.configuration.uiMode and
                             android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    ui == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            }
            MBclawTheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MBclawMainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台 → 隐藏悬浮窗
        try { com.mbclaw.root.service.AgentFloatingService.stop(this) } catch (_: Exception) {}
        // 强制重新加载会话 (防止系统回收单例)
        try {
            val agent = com.mbclaw.root.agent.MBclawAgent(application)
            val vm = com.mbclaw.root.ui.screens.ChatViewModel.get(applicationContext, agent)
            vm.forceReload()
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        // ★ v5.5.x: 切后台时强制WAL checkpoint，防止消息丢失
        try {
            com.mbclaw.root.agent.MBclawAgent(application).db.writableDatabase
                .execSQL("PRAGMA wal_checkpoint(PASSIVE)")
        } catch (_: Exception) {}
        // 切到后台且AI在运行 → 弹出悬浮窗让用户知道MBclaw还在工作
        try {
            val agent = com.mbclaw.root.agent.MBclawAgent(application)
            val vm = com.mbclaw.root.ui.screens.ChatViewModel.get(applicationContext, agent)
            if (vm.isThinking.value) {
                com.mbclaw.root.service.AgentFloatingService.start(this, "运行中")
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // ★ v5.5.x: 强制WAL checkpoint，防止杀后台后最近对话丢失
        try {
            val agent = com.mbclaw.root.agent.MBclawAgent(application)
            agent.db.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
        } catch (_: Exception) {}
    }
}
