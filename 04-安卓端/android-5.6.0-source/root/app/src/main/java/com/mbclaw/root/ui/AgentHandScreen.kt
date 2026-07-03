package com.mbclaw.root.ui

import android.app.Application
import android.graphics.Point
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mbclaw.root.agent.TouchInjector
import com.mbclaw.root.hand.AgentHand
import com.mbclaw.root.hand.HandMode
import com.mbclaw.root.data.UserSettings
import kotlinx.coroutines.launch

/**
 * 智能体之手 — 控制面板
 *
 * v4.6 修复: executor 不再返回假 true, 而是真正调用 TouchInjector
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHandScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { UserSettings(context) }

    // ★ Bug1 核心修复: 真实触摸执行器
    val hand = remember {
        AgentHand(context, settings) { point ->
            // 真正执行触摸 — Root input tap > sendevent > Accessibility
            TouchInjector.tap(context, point.x, point.y)
        }
    }

    var selectedMode by remember { mutableStateOf(HandMode.BALANCE) }
    var isCalibrated by remember { mutableStateOf(hand.calibration.isCalibrated()) }
    var stats by remember { mutableStateOf(hand.getStats()) }
    var selfTestResult by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🦾 智能体之手", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 状态总览 ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("📊 系统状态", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        AssistChip(onClick = {}, label = { Text("${stats["screen_size"]}") })
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = {}, label = {
                            Text(if (isCalibrated) "✅ 已标定" else "⚠️ 未标定")
                        })
                    }
                    Spacer(Modifier.height(4.dp))
                    val rate = (stats["success_rate"] as? Float) ?: 0f
                    Text("成功率: ${"%.1f".format(rate * 100)}%")
                    LinearProgressIndicator(progress = { rate }, modifier = Modifier.fillMaxWidth())
                }
            }

            // ── 双通道自检面板 (v4.7) ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("🔧 双通道自检", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (selfTestResult.isNotEmpty()) {
                        Text(selfTestResult, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                testing = true
                                scope.launch {
                                    selfTestResult = "通道1 (触摸注入):\n" + TouchInjector.selfTest(context)
                                    testing = false
                                }
                            },
                            enabled = !testing,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (testing) "..." else "🔍 通道1:触摸") }
                        Button(
                            onClick = {
                                testing = true
                                scope.launch {
                                    val vlm = com.mbclaw.root.agent.VisionLocator.probe(context, settings)
                                    selfTestResult = "通道2 (视觉模型):\n$vlm"
                                    testing = false
                                }
                            },
                            enabled = !testing,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (testing) "..." else "👁 通道2:VLM") }
                    }
                }
            }

            // ── 模式选择 ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("⚡ 运行模式", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HandMode.entries.forEach { mode ->
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = { selectedMode = mode; hand.setMode(mode) },
                                label = {
                                    Text(when (mode) {
                                        HandMode.SPEED -> "⚡极速"
                                        HandMode.BALANCE -> "⚖均衡"
                                        HandMode.PRECISE -> "🎯高精"
                                    })
                                },
                            )
                        }
                    }
                    Text(
                        when (selectedMode) {
                            HandMode.SPEED -> "3×4网格 | 0轮精定位 | 模糊优先"
                            HandMode.BALANCE -> "4×6网格 | 1轮精定位 | 模糊辅助"
                            HandMode.PRECISE -> "6×8网格 | 2轮精定位 | 模糊关闭"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 关键词库 ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("📝 关键词库 (${stats["keywords_count"] ?: 0}个)", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("内置12类100+常用操作词，自动学习扩充",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── 方法统计 ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("📈 方法命中率", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val methodStats = stats["method_stats"] as? Map<String, Pair<Int, Int>> ?: emptyMap()
                    methodStats.forEach { (method, pair) ->
                        val (success, total) = pair
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(when (method) { "memory"->"🧠记忆"; "fuzzy"->"⚡模糊"; "fine"->"🎯精定位"; "coarse"->"📐粗筛"; else->method },
                                modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { success.toFloat() / maxOf(total, 1) },
                                modifier = Modifier.weight(1f).height(8.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("$success/$total", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // ── 测试 ──
            var testTarget by remember { mutableStateOf("发送") }
            var testResult by remember { mutableStateOf("") }
            var keyTest by remember { mutableStateOf(false) }
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("🧪 试一下", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("智能手需要截图作为输入，通常由 Agent 内部调用。\n这里仅测试关键词命中（模糊点击通道）。",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = testTarget, onValueChange = { testTarget = it },
                        label = { Text("目标 (如: 发送 / 确定 / 关闭)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            keyTest = true
                            scope.launch {
                                try {
                                    val fuzzy = com.mbclaw.root.hand.FuzzyClicker()
                                    val hit = fuzzy.matchKeyword(testTarget)
                                    testResult = if (hit != null)
                                        "✅ 关键词命中: ${hit.keyword} (置信度 ${"%.0f".format(hit.confidence * 100)}%, 方法 ${hit.method}) — 会走快速路径"
                                    else
                                        "△ 关键词未命中，将走完整粗筛+精定位流程 (需要截图)"
                                } catch (e: Exception) {
                                    testResult = "失败: ${e.message}"
                                }
                                keyTest = false
                            }
                        },
                        enabled = !keyTest,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (keyTest) "检测中..." else "🎯 检测关键词命中") }
                    if (testResult.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(testResult, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ── 标定 ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("📐 屏幕标定", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(if (isCalibrated) "✅ 已标定，可日常使用" else "⚠️ 未标定 — 推荐进行四角校准提高精度",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    hand.calibration.reset()
                                    isCalibrated = false
                                    android.widget.Toast.makeText(context, "已重置标定",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("重置") }
                        Button(
                            onClick = {
                                scope.launch {
                                    val dm = context.resources.displayMetrics
                                    hand.calibration.quickCalibrate(dm.widthPixels, dm.heightPixels)
                                    isCalibrated = true
                                    android.widget.Toast.makeText(context, "✅ 快速标定完成",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("⚡ 快速标定") }
                    }
                }
            }
        }
    }
}
