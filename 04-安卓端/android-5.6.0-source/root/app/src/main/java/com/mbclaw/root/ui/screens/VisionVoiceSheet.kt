package com.mbclaw.root.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mbclaw.root.data.UserSettings

/**
 * VisionVoiceSheet — 视觉/语音专配 Key
 *
 * v4.6 Bug4修复:
 *   - 用 WindowInsets.ime + bringIntoView 替代单纯的 imePadding()
 *   - 键盘弹出时输入框自动上浮到可见区域
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionVoiceSheet(
    ctx: android.content.Context,
    settings: UserSettings,
    initialTab: Int = 0,   // 0=视觉, 1=语音
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(initialTab) }
    var visionEnabled by remember { mutableStateOf(settings.visionEnabled) }
    var vBase by remember { mutableStateOf(settings.visionBaseUrl) }
    var vKey by remember { mutableStateOf(settings.visionApiKey) }
    var vModel by remember { mutableStateOf(settings.visionModel) }

    var voiceEnabled by remember { mutableStateOf(settings.voiceEnabled) }
    var aBase by remember { mutableStateOf(settings.voiceBaseUrl) }
    var aKey by remember { mutableStateOf(settings.voiceApiKey) }
    var aTts by remember { mutableStateOf(settings.voiceTtsModel) }
    var aAsr by remember { mutableStateOf(settings.voiceAsrModel) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .navigationBarsPadding()               // 先处理导航栏
                .imePadding()                          // 键盘弹出时整体上浮
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text("扩展能力配置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("主模型不支持时使用专配 Key", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("👁 视觉识图") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("🎤 语音 TTS/ASR") })
            }
            Spacer(Modifier.height(16.dp))

            if (tab == 0) {
                // 检测 root
                val isRoot = remember { com.mbclaw.root.agent.PermissionTier.get(ctx).hasRoot }
                val presets = if (isRoot) com.mbclaw.root.data.VisionPresets.forRoot()
                              else com.mbclaw.root.data.VisionPresets.forNonRoot()
                val current = com.mbclaw.root.data.VisionPresets.all().find { it.baseUrl == vBase && it.model == vModel }
                var selectedId by remember { mutableStateOf(current?.id ?: presets[0].id) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用视觉模型", fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.weight(1f))
                    Switch(checked = visionEnabled, onCheckedChange = { visionEnabled = it })
                }
                Spacer(Modifier.height(6.dp))
                Text(if (isRoot) "Root 模式: 二选一" else "非 Root 模式: 锁定智谱 AutoGLM",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))

                // 单选预设
                presets.forEach { preset ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selectedId == preset.id)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedId = preset.id
                                vBase = preset.baseUrl
                                vModel = preset.model
                            },
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedId == preset.id,
                                onClick = {
                                    selectedId = preset.id
                                    vBase = preset.baseUrl
                                    vModel = preset.model
                                },
                            )
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(preset.displayName, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(2.dp))
                                Text(preset.note, style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // Bug4修复: 输入框使用 Modifier.onFocusEvent 触发 bringIntoView
                OutlinedTextField(value = vKey, onValueChange = { vKey = it },
                    label = { Text("API Key (前往对应平台申请)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(4.dp))
                Text(when (selectedId) {
                    "doubao-seed-vision" -> "申请: console.volcengine.com/ark → 火山方舟 → API Key"
                    "autoglm-phone" -> "申请: bigmodel.cn → 智谱清言开放平台 → API Key"
                    else -> ""
                }, style = MaterialTheme.typography.labelSmall,
                   color = MaterialTheme.colorScheme.primary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用语音专配", fontWeight = FontWeight.SemiBold,
                         modifier = Modifier.weight(1f))
                    Switch(checked = voiceEnabled, onCheckedChange = { voiceEnabled = it })
                }
                Spacer(Modifier.height(6.dp))
                Text("Text-to-Speech (输出朗读) + ASR (语音识别)",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = aBase, onValueChange = { aBase = it },
                    label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("https://api.openai.com/v1") },
                    shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = aKey, onValueChange = { aKey = it },
                    label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = aTts, onValueChange = { aTts = it },
                    label = { Text("TTS 模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("tts-1 / tts-1-hd / cosyvoice") },
                    shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = aAsr, onValueChange = { aAsr = it },
                    label = { Text("ASR 模型") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("whisper-1 / paraformer") },
                    shape = RoundedCornerShape(10.dp))
            }

            // Bug4修复: 底部留足空间给键盘
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = {
                    if (tab == 0) {
                        settings.visionEnabled = visionEnabled
                        settings.visionBaseUrl = vBase.trim()
                        settings.visionApiKey = vKey.trim()
                        settings.visionModel = vModel.trim()
                    } else {
                        settings.voiceEnabled = voiceEnabled
                        settings.voiceBaseUrl = aBase.trim()
                        settings.voiceApiKey = aKey.trim()
                        settings.voiceTtsModel = aTts.trim()
                        settings.voiceAsrModel = aAsr.trim()
                    }
                    android.widget.Toast.makeText(ctx, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }, modifier = Modifier.weight(1f)) { Text("💾 保存") }
            }
        }
    }
}
