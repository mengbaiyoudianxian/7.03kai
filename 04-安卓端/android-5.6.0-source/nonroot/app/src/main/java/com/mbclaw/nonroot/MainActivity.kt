package com.mbclaw.nonroot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mbclaw.nonroot.ui.MBclawMainScreen
import com.mbclaw.nonroot.ui.theme.MBclawTheme

/**
 * MBclaw Lite — 非Root版本
 * 此版本作者投入精力 0.01%，基本没啥可以玩的
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            com.mbclaw.nonroot.ui.theme.ThemePreference.ensureInit(this)
            val mode by com.mbclaw.nonroot.ui.theme.ThemePreference.currentMode!!
            val isDark = when (mode) {
                "dark" -> true
                "light" -> false
                else -> {
                    val ui = resources.configuration.uiMode and
                             android.content.res.Configuration.UI_MODE_NIGHT_MASK
                    ui == android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
            }

            // 启动弹窗: 此版本作者投入精力0.01%
            val prefs = getSharedPreferences("mbclaw_lite", MODE_PRIVATE)
            var showDisclaimer by remember { mutableStateOf(!prefs.getBoolean("disclaimer_accepted", false)) }

            MBclawTheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MBclawMainScreen()
                }

                if (showDisclaimer) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("MBclaw Lite") },
                        text = { Text("此版本作者投入精力 0.01%，基本没啥可以玩的。\n\n建议下载完整 Root 版本体验全部功能。") },
                        confirmButton = {
                            TextButton(onClick = {
                                prefs.edit().putBoolean("disclaimer_accepted", true).apply()
                                showDisclaimer = false
                            }) {
                                Text("我知道了")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ★ bug5 修: 从后台返回时, 强制重新加载会话 (防止系统回收单例)
        try {
            val agent = com.mbclaw.nonroot.agent.MBclawAgent(application)
            val vm = com.mbclaw.nonroot.ui.screens.ChatViewModel.get(applicationContext, agent)
            vm.forceReload()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
