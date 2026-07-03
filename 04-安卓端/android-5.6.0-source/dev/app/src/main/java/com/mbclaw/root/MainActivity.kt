package com.mbclaw.dev

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mbclaw.dev.ui.MBclawMainScreen
import com.mbclaw.dev.ui.theme.MBclawTheme

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
            com.mbclaw.dev.ui.theme.ThemePreference.ensureInit(this)
            val mode by com.mbclaw.dev.ui.theme.ThemePreference.currentMode!!
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
        // ★ bug5 修: 从后台返回时, 强制重新加载会话 (防止系统回收单例)
        try {
            val agent = com.mbclaw.dev.agent.MBclawAgent(application)
            val vm = com.mbclaw.dev.ui.screens.ChatViewModel.get(applicationContext, agent)
            vm.forceReload()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
