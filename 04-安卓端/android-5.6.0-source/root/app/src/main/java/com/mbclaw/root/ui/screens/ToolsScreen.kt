package com.mbclaw.root.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbclaw.root.agent.ToolRegistry
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 工具屏幕 — 仿 MiClaw 工具市场
 * • 顶部统计条 (实时计数)
 * • 分类标签条 (横滚 chips)
 * • 卡片化工具列表
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolsScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { com.mbclaw.root.data.UserSettings(ctx) }
    var selectedCat by remember { mutableStateOf("全部") }
    // ★ v6: 绑定CapabilityRegistry flow, 工具注册后自动刷新
    val registeredTools by ToolRegistry.tools.collectAsState()
    val builtinTools = ToolRegistry.BUILTIN
    var customTools by remember { mutableStateOf(com.mbclaw.root.agent.CustomToolStore.loadAll(ctx)) }
    var showAddSheet by remember { mutableStateOf(false) }
    var actionTool by remember { mutableStateOf<Pair<String, String>?>(null) }    // (name, source)
    var refresh by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh) {
        customTools = com.mbclaw.root.agent.CustomToolStore.loadAll(ctx)
    }
    // 合并: builtin + custom (custom 在前)
    data class Item(val name: String, val description: String, val source: String, val enabled: Boolean = true)
    val allItems = remember(refresh) {
        customTools.map { Item(it.name, it.description, it.source, it.enabled) } +
        builtinTools.map { Item(it.name, it.description, "BUILTIN") }
    }
    val allTools = allItems

    val cats = remember(refresh) {
        listOf(
            "全部" to allTools,
            "自定义" to allTools.filter { it.source != "BUILTIN" },
            "系统" to allTools.filter { it.name.startsWith("toggle_") || it.name in listOf("set_brightness","set_volume","get_battery","device_status","get_system_info","check_permissions") },
            "WiFi" to allTools.filter { it.name.contains("wifi", true) },
            "蓝牙" to allTools.filter { it.name.startsWith("bluetooth_") },
            "通讯" to allTools.filter { it.name.contains("sms") || it.name.contains("call") || it.name.contains("phone") || it.name.contains("contact") },
            "文件" to allTools.filter { it.name.contains("file") },
            "屏幕" to allTools.filter { it.name in listOf("take_screenshot","screen_record","click_at","long_press_at","swipe","input_text","press_key") },
            "媒体" to allTools.filter { it.name.contains("media") || it.name == "camera" || it.name.contains("control_media") },
            "日历" to allTools.filter { it.name.contains("calendar") },
            "应用" to allTools.filter { it.name.contains("app_") || it.name in listOf("open_app","list_apps","uninstall_app","force_stop_app") },
            "Web" to allTools.filter { it.name in listOf("url_fetch","web_search","browser_open","browser_extract","browser_click","browser_input","browser_close","get_weather") },
            "记忆" to allTools.filter { it.name in listOf("search_memory","dream_memory","classify_conversation","dual_key_review","collision_think","search_history","load_message") },
            "高级" to allTools.filter { it.name in listOf("local_sandbox_run","list_agents","start_agent","timer","get_location","send_intent","send_notification","get_capability") },
        )
    }
    val current = cats.find { it.first == selectedCat }?.second ?: allTools

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ─── 顶部统计 + 添加按钮 ───
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("仿 MiClaw · 长按工具查看操作",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(" ${allTools.size} 个 ",
                             modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                             style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.onPrimaryContainer,
                             fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(6.dp))
                    FilledIconButton(
                        onClick = { showAddSheet = true },
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                    ) { Icon(Icons.Filled.Add, "添加工具",
                             modifier = Modifier.size(18.dp),
                             tint = androidx.compose.ui.graphics.Color.White) }
                }
            }
        }
        // ─── 分类 chips ───
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cats.forEach { (label, list) ->
                FilterChip(
                    selected = selectedCat == label,
                    onClick = { selectedCat = label },
                    label = { Text("$label (${list.size})", style = MaterialTheme.typography.labelMedium) },
                    shape = RoundedCornerShape(50),
                )
            }
        }
        // ─── 工具列表 ───
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(current) { tool ->
                val itemForLongPress = tool
                Card(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = {},
                        onLongClick = {
                            actionTool = itemForLongPress.name to itemForLongPress.source
                        },
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(iconFor(tool.name), null,
                                     tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tool.name, fontWeight = FontWeight.SemiBold,
                                     style = MaterialTheme.typography.titleSmall,
                                     modifier = Modifier.weight(1f, false),
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(6.dp))
                                // 任务 6: 工具来源标识
                                SourceBadge(tool.source, tool.enabled)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(tool.description,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.outline,
                                 maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    // ─── 添加工具 sheet ───
    if (showAddSheet) {
        ToolAddSheet(
            ctx = ctx,
            settings = settings,
            onDismiss = { showAddSheet = false },
            onAdded = { refresh++ },
        )
    }
    // ─── 长按操作 sheet ───
    actionTool?.let { (name, source) ->
        ToolActionSheet(
            ctx = ctx,
            settings = settings,
            toolName = name,
            source = source,
            onDismiss = { actionTool = null },
            onChanged = { refresh++ },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolAddSheet(
    ctx: android.content.Context,
    settings: com.mbclaw.root.data.UserSettings,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }   // 0: 本地  1: 云端
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var schema by remember { mutableStateOf("{\"type\":\"object\",\"properties\":{}}") }
    var loading by remember { mutableStateOf(false) }
    var cloudList by remember { mutableStateOf<List<com.mbclaw.root.agent.CustomTool>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tab) {
        if (tab == 1) {
            loading = true
            cloudList = com.mbclaw.root.agent.CustomToolStore.fetchCloudList(settings.serverUrl)
            loading = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text("添加工具", fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.surface) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("本地") })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("云端市场") })
            }
            Spacer(Modifier.height(16.dp))
            if (tab == 0) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("工具名 (如 my_search)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = desc, onValueChange = { desc = it },
                    label = { Text("描述 (LLM 用来判断是否调用)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = schema, onValueChange = { schema = it },
                    label = { Text("参数 JSON Schema") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(8.dp))
                // 导入JSON文件
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val json = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@launch
                            val arr = org.json.JSONArray(json)
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val t = ToolRegistry.ToolDef(
                                    obj.optString("name"), obj.optString("description", ""),
                                    org.json.JSONObject(obj.optString("parameters", "{}")), "import")
                                ToolRegistry.register(t)
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(ctx, "已导入 ${arr.length()} 个工具", android.widget.Toast.LENGTH_SHORT).show()
                                onAdded(); onDismiss()
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(ctx, "导入失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FileOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("导入 JSON 工具文件")
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    if (name.isBlank()) return@Button
                    com.mbclaw.root.agent.CustomToolStore.add(ctx, com.mbclaw.root.agent.CustomTool(
                        name = name, description = desc, parameters = schema, source = "LOCAL",
                    ))
                    ToolRegistry.register(ToolRegistry.ToolDef(name, desc, org.json.JSONObject(schema), "local"))
                    onAdded()
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("💾 保存到本地")
                }
            } else {
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (cloudList.isEmpty()) {
                    Text("云市场暂无工具或服务器未启用",
                         color = MaterialTheme.colorScheme.outline,
                         style = MaterialTheme.typography.bodySmall,
                         modifier = Modifier.padding(20.dp))
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
                               modifier = Modifier.heightIn(max = 360.dp)) {
                        items(cloudList) { t ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t.name, fontWeight = FontWeight.SemiBold)
                                        Text(t.description,
                                             style = MaterialTheme.typography.bodySmall,
                                             color = MaterialTheme.colorScheme.outline,
                                             maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    TextButton(onClick = {
                                        com.mbclaw.root.agent.CustomToolStore.add(ctx, t)
                                        onAdded()
                                    }) { Text("下载") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolActionSheet(
    ctx: android.content.Context,
    settings: com.mbclaw.root.data.UserSettings,
    toolName: String,
    source: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val isBuiltin = source == "BUILTIN"
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(toolName, fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleMedium)
            Text("来源: $source",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            // 上传
            ListItem(
                headlineContent = { Text("☁ 上传到云市场") },
                supportingContent = { Text("分享给其他玩家") },
                leadingContent = { Icon(Icons.Filled.CloudUpload, null) },
                modifier = Modifier.clickable {
                    scope.launch {
                        val all = com.mbclaw.root.agent.CustomToolStore.loadAll(ctx)
                        val t = all.find { it.name == toolName }
                        if (t != null) {
                            val ok = com.mbclaw.root.agent.CustomToolStore.uploadToCloud(ctx, settings.serverUrl, t)
                            android.widget.Toast.makeText(ctx,
                                if (ok) "已上传" else "上传失败 (Builtin 暂不支持)",
                                android.widget.Toast.LENGTH_SHORT).show()
                        } else android.widget.Toast.makeText(ctx,
                            "系统内置工具不支持上传", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
            )
            // 禁用/启用 (仅自定义)
            if (!isBuiltin) {
                ListItem(
                    headlineContent = { Text("⏸ 切换启用状态") },
                    leadingContent = { Icon(Icons.Filled.Block, null) },
                    modifier = Modifier.clickable {
                        val all = com.mbclaw.root.agent.CustomToolStore.loadAll(ctx)
                        val cur = all.find { it.name == toolName }?.enabled ?: true
                        com.mbclaw.root.agent.CustomToolStore.setEnabled(ctx, toolName, !cur)
                        onChanged(); onDismiss()
                    },
                )
                // 删除
                ListItem(
                    headlineContent = { Text("🗑 删除", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Filled.Delete, null,
                                            tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        com.mbclaw.root.agent.CustomToolStore.remove(ctx, toolName)
                        onChanged(); onDismiss()
                    },
                )
            }
            // 保存本地 (云端工具时)
            if (source == "CLOUD" || source == "SHARED") {
                ListItem(
                    headlineContent = { Text("📥 保存到本地永久使用") },
                    leadingContent = { Icon(Icons.Filled.SaveAlt, null) },
                    modifier = Modifier.clickable {
                        // 已经在 CustomToolStore 中, 标记为 LOCAL
                        val all = com.mbclaw.root.agent.CustomToolStore.loadAll(ctx)
                        val updated = all.map {
                            if (it.name == toolName) it.copy(source = "LOCAL") else it
                        }
                        com.mbclaw.root.agent.CustomToolStore.saveAll(ctx, updated)
                        onChanged(); onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun iconFor(name: String) = when {
    name.contains("wifi", true) -> Icons.Filled.Wifi
    name.startsWith("bluetooth") -> Icons.Filled.Bluetooth
    name.contains("sms") || name.contains("message") -> Icons.Filled.Sms
    name.contains("call") || name.contains("phone") -> Icons.Filled.Call
    name.contains("contact") -> Icons.Filled.Contacts
    name == "camera" -> Icons.Filled.CameraAlt
    name.contains("screen") -> Icons.Filled.Screenshot
    name.contains("file") || name in listOf("write_file","read_file","append_file","edit_file","delete_file","copy_file","move_file","list_files","search_files","file_grep","file_info") -> Icons.Filled.Folder
    name.contains("calendar") -> Icons.Filled.CalendarMonth
    name.contains("browser") || name == "url_fetch" || name == "web_search" -> Icons.Filled.Public
    name.contains("media") || name == "control_media" -> Icons.Filled.MusicNote
    name.contains("weather") -> Icons.Filled.Cloud
    name.contains("location") -> Icons.Filled.LocationOn
    name.contains("app_") || name in listOf("open_app","list_apps","uninstall_app","force_stop_app") -> Icons.Filled.Apps
    name.contains("memory") || name == "search_memory" || name == "search_history" -> Icons.Filled.Psychology
    name == "local_sandbox_run" -> Icons.Filled.Terminal
    name == "timer" -> Icons.Filled.Schedule
    name.contains("clipboard") -> Icons.Filled.ContentCopy
    name.contains("notification") -> Icons.Filled.Notifications
    name.contains("brightness") -> Icons.Filled.BrightnessMedium
    name.contains("volume") -> Icons.Filled.VolumeUp
    name == "get_battery" -> Icons.Filled.BatteryStd
    name.contains("flashlight") -> Icons.Filled.FlashOn
    name.contains("airplane") -> Icons.Filled.FlightTakeoff
    else -> Icons.Filled.Build
}

@Composable
private fun SourceBadge(source: String, enabled: Boolean) {
    val (label, color, icon) = when (source) {
        "BUILTIN" -> Triple("系统", MaterialTheme.colorScheme.primary, Icons.Filled.Verified)
        "CLOUD" -> Triple("云端", androidx.compose.ui.graphics.Color(0xFF4A90E2), Icons.Filled.CloudDownload)
        "LOCAL" -> Triple("本地", androidx.compose.ui.graphics.Color(0xFF34C759), Icons.Filled.PhoneAndroid)
        "mcp" -> Triple("MCP", androidx.compose.ui.graphics.Color(0xFF34C759), Icons.Filled.Extension)
        "skill" -> Triple("Skill", androidx.compose.ui.graphics.Color(0xFFAF52DE), Icons.Filled.AutoAwesome)
        "api" -> Triple("API", androidx.compose.ui.graphics.Color(0xFF4A90E2), Icons.Filled.Code)
        "GENERATED" -> Triple("自学", androidx.compose.ui.graphics.Color(0xFFFF8A3D), Icons.Filled.Psychology)
        "SHARED" -> Triple("分享", androidx.compose.ui.graphics.Color(0xFFFF8A3D), Icons.Filled.Share)
        else -> Triple(source.take(6), MaterialTheme.colorScheme.outline, Icons.Filled.Extension)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(3.dp))
            Text(label, style = MaterialTheme.typography.labelSmall,
                 color = color, fontWeight = FontWeight.SemiBold)
            if (!enabled) {
                Spacer(Modifier.width(3.dp))
                Text("·停",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
