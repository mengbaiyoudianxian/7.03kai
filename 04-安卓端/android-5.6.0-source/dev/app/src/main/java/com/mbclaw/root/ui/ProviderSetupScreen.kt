package com.mbclaw.dev.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mbclaw.dev.data.UserSettings
import com.mbclaw.dev.model.LLMProvider
import com.mbclaw.dev.model.ProviderCatalog

/**
 * 提供商配置界面 — 三步上手:
 *   1. 选提供商
 *   2. 填 API Key
 *   3. 选模型 → 开始用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSetupScreen(
    settings: UserSettings,
    onDone: () -> Unit,
) {
    var selectedProvider by remember { mutableStateOf(settings.providerId) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var modelName by remember { mutableStateOf(settings.modelName) }
    var customBaseUrl by remember { mutableStateOf(settings.apiBaseUrl) }
    var showKey by remember { mutableStateOf(false) }

    val provider = ProviderCatalog.find(selectedProvider)
    val isCustom = selectedProvider == "custom"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚙ 配置 AI 提供商") },
                actions = {
                    TextButton(onClick = {
                        settings.providerId = selectedProvider
                        settings.apiKey = apiKey
                        settings.modelName = modelName
                        settings.apiBaseUrl = if (isCustom) customBaseUrl else (provider?.baseUrl ?: "")
                        onDone()
                    }) { Text("完成", fontWeight = FontWeight.Bold) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 步骤1: 选提供商 ──
            Text("1️⃣ 选择大模型提供商", style = MaterialTheme.typography.titleSmall)
            Text("覆盖 90% 国内外 API，支持 OpenAI 兼容格式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 国内
            Text("🇨🇳 国内", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            ProviderCatalog.china.forEach { p ->
                ProviderCard(p, selectedProvider == p.id) { selectedProvider = p.id }
            }

            // 国际
            Text("🌍 国际", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            ProviderCatalog.international.forEach { p ->
                ProviderCard(p, selectedProvider == p.id) { selectedProvider = p.id }
            }

            // 自定义
            Text("🔧 其他", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            ProviderCard(ProviderCatalog.all.last(), selectedProvider == "custom") {
                selectedProvider = "custom"
            }

            Divider()

            // ── 步骤2: API Key ──
            Text("2️⃣ API Key", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = { Text("sk-... 或你的 Key") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (!showKey) PasswordVisualTransformation() else
                    androidx.compose.ui.text.input.VisualTransformation.None,
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                    }
                },
                singleLine = true,
            )
            if (provider?.notes?.isNotBlank() == true) {
                Text("💡 ${provider?.notes}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── 步骤3: 模型名 ──
            Text("3️⃣ 模型名称", style = MaterialTheme.typography.titleSmall)
            if (isCustom) {
                OutlinedTextField(
                    value = customBaseUrl,
                    onValueChange = { customBaseUrl = it },
                    placeholder = { Text("https://api.xxx.com/v1") },
                    label = { Text("API 地址 (OpenAI格式)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    placeholder = { Text("model-name") },
                    label = { Text("模型名") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            } else if (provider != null && provider.models.isNotEmpty()) {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("模型名称（可手改）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                Text("推荐模型:", style = MaterialTheme.typography.labelSmall)
                provider.models.forEach { m ->
                    SuggestionChip(
                        onClick = { modelName = m },
                        label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }

            Divider()

            // ── 当前配置 ──
            Card(colors = CardDefaults.cardColors(
                containerColor = if (settings.isConfigured())
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (settings.isConfigured()) "✅ 已配置" else "⚠️ 未配置",
                        fontWeight = FontWeight.Bold,
                    )
                    Text("提供商: ${provider?.name ?: "未选"}", style = MaterialTheme.typography.bodySmall)
                    Text("模型: ${modelName.ifBlank { "未填" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Key: ${if (apiKey.isNotBlank()) "已填写 (${apiKey.take(6)}...)" else "未填写"}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(provider: LLMProvider, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        border = if (selected) CardDefaults.outlinedCardBorder() else null,
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(provider.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    if (provider.free) {
                        Spacer(Modifier.width(4.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("免费", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(20.dp),
                        )
                    }
                }
                Text(provider.baseUrl, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
