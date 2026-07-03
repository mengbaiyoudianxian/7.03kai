package com.mbclaw.root.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mbclaw.root.agent.MBclawAgent
import com.mbclaw.root.agent.PermissionTier
import com.mbclaw.root.agent.RootBootstrap
import com.mbclaw.root.agent.SafeOps
import com.mbclaw.root.agent.ToolRegistry
import com.mbclaw.root.data.AccountManager
import com.mbclaw.root.data.SecureVault
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mbclaw.root.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    agent: MBclawAgent,
    onBack: () -> Unit,
    onSetupProvider: () -> Unit,
    onOpenHand: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSessions: () -> Unit = {},
    onOpenCommunity: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val s = agent.settings
    var utopia by remember { mutableStateOf(s.utopiaEnabled) }
    var sync by remember { mutableStateOf(s.serverSyncEnabled) }
    var showTokens by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showPermissionsPage by remember { mutableStateOf(false) }
    var showAccountSheet by remember { mutableStateOf(false) }
    var showMiclawSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }
    var showSponsor by remember { mutableStateOf(false) }
    var showVisionVoice by remember { mutableStateOf(false) }
    var visionVoiceTab by remember { mutableIntStateOf(0) }
    var showVault by remember { mutableStateOf(false) }
    var showMcp by remember { mutableStateOf(false) }
    var showSkills by remember { mutableStateOf(false) }
    var showLinuxDownload by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    val tier = remember { PermissionTier.get(ctx) }
    val (granted, total) = remember { RootBootstrap.status(ctx) }
    val vaultCount = remember { SecureVault.count(ctx) }
    val backupCount = remember { SafeOps.listBackups("apps").size + SafeOps.listBackups("files").size }
    val account = remember { AccountManager.load(ctx) }
    val debugCode = remember { "mb-" + (android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)?.take(8) ?: "unknown") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Spacer(Modifier.height(8.dp))

            // ── 账号 ──
            SectionLabel("账号")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingRow(Icons.Outlined.AccountCircle, "我的账号", account.qqId.ifBlank { "未登录" }, onClick = { showAccountSheet = true })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.ChatBubbleOutline, "当前会话", "${agent.db.getAllMemoryKeys().size} 段历史 · 点击查看全部", onClick = onOpenSessions)
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.Security, "权限状态", "$granted/$total 已授予", onClick = { showPermissionsPage = true },
                    trailing = { if (granted >= 25) Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(18.dp)) else Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp)) })
            }

            Spacer(Modifier.height(24.dp))

            // ── 外观 ──
            SectionLabel("外观")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("主题模式", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val modes = listOf("浅色" to "light", "深色" to "dark", "跟随系统" to "system")
                        val current = com.mbclaw.root.ui.theme.ThemePreference.mode(ctx)
                        modes.forEach { (label, key) ->
                            val sel = current == key
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f).clickable { com.mbclaw.root.ui.theme.ThemePreference.setMode(ctx, key) },
                            ) {
                                Text(label, Modifier.padding(vertical = 10.dp).fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 核心模型与感知 ──
            SectionLabel("核心模型与感知")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                val modelSub = if (s.isConfigured()) s.modelName else "未配置"
                SettingRow(Icons.Outlined.Key, "模型 API 配置", modelSub, onClick = onSetupProvider,
                    trailing = { if (!s.isConfigured()) Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFFEF2F2)) { Text("未配", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color(0xFFEF4444)) } else {} })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.Visibility, "视觉识图模型", "未配 · 主模型不支持识图时使用", onClick = { visionVoiceTab = 0; showVisionVoice = true })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.Mic, "语音 TTS/ASR 模型", "未配 · 输入/输出语音时使用", onClick = { visionVoiceTab = 1; showVisionVoice = true })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.CardGiftcard, "白嫖 MiClaw 算力", "通过 NEORUAA bridge 中转", onClick = { showMiclawSheet = true })
            }

            Spacer(Modifier.height(24.dp))

            // ── 功能与扩展 ──
            SectionLabel("功能与扩展")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingRow(Icons.Outlined.Computer, "完整 Linux 环境",
                    if (com.mbclaw.root.sandbox.LocalSandbox(ctx).isInstalled) "已安装 · 706MB · /data/mbclaw/linux"
                    else "一键下载 · ~200MB · 预装 Python/bash/git/vim",
                    onClick = { showLinuxDownload = true })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.Cable, "MCP 插件市场", "Model Context Protocol · 连接外部工具", onClick = { showMcp = true })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.Apps, "工具市场", "${ToolRegistry.ALL.size} 个工具 · 添加/上传/下载", onClick = onOpenTools)
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.TouchApp, "智能手 Agent Hand", "看得见点得准 · 校准/区块识别", onClick = onOpenHand)
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.AutoAwesome, "Skill 技能", "本地/云端技能 · 扩展AI能力", onClick = { showSkills = true })
            }

            Spacer(Modifier.height(24.dp))

            // ── 共建反馈 ──
            SectionLabel("共建反馈")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingRow(Icons.Outlined.BugReport, "Bug 反馈", "反馈问题 · 投票支持", onClick = onOpenCommunity)
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.Lightbulb, "共建计划", "功能建议 · 投票支持", onClick = onOpenCommunity)
            }

            Spacer(Modifier.height(24.dp))

            // ── 乌托邦计划 ──
            SectionLabel("乌托邦计划")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                SwitchRow("启用乌托邦", "主动收集评价、分析心理学画像、优化交互", utopia, { utopia = it; s.utopiaEnabled = it })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow("连接 MBclaw 服务器", "同步数据到母体记忆系统", sync, { sync = it; s.serverSyncEnabled = it })
            }

            Spacer(Modifier.height(24.dp))

            // ── 隐私与安全 ──
            SectionLabel("隐私与安全")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingRow(Icons.Outlined.Lock, "隐私保险箱", "${vaultCount}项 · 存放重要信息，AI不会忘", onClick = { showVault = true })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.Backup, "自动备份", "${backupCount}份备份 · 删除前自动备份", onClick = { showBackup = true })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.BarChart, "Token 消耗统计", "", onClick = { showTokens = true })
            }

            Spacer(Modifier.height(24.dp))

            // ── 开发者调试 ──
            SectionLabel("开发者调试")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                val debugId = tier.runCatching { PermissionTier.get(ctx) }.getOrNull()?.let { "mb-" + android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID).take(8) } ?: "未知"
                SettingRow(Icons.Outlined.Terminal, "远程调试", "永久开启 · $debugCode · 点击复制", onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("debug", debugCode))
                })
            }

            Spacer(Modifier.height(24.dp))

            // ── 版本信息 ──
            SectionLabel("版本信息")
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingRow(Icons.Outlined.Info, "MBclaw 版本", "v${BuildConfig.VERSION_NAME}", onClick = { showAboutSheet = true },
                    trailing = { Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFFEF3C7)) { Text("最新", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color(0xFF92400E)) } })
            }

            Spacer(Modifier.height(8.dp))

            // ── 关于 ──
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingRow(Icons.Outlined.Forum, "酷安", "coolapk.com/u/26771405", onClick = {
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.coolapk.com/u/26771405"))
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i)
                })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.Person, "作者 QQ", "1973054239 · 点击复制", onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("qq", "1973054239"))
                })
                Divider(Modifier.padding(horizontal = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(Icons.Outlined.FavoriteBorder, "友情赞助", "请作者喝杯奶茶", onClick = { showSponsor = true })
            }

            Spacer(Modifier.height(32.dp))

            // ── 危险操作区（底部隔离）──
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)), modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().clickable { showClearConfirm = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DeleteForever, null, tint = Color(0xFFEF4444), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("清除历史对话", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEF4444))
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // 弹窗
    if (showClearConfirm) AlertDialog(
        onDismissRequest = { showClearConfirm = false },
        title = { Text("清除历史对话", fontWeight = FontWeight.Bold) },
        text = { Text("删除所有对话记录，不可恢复。") },
        confirmButton = {
            TextButton(onClick = { agent.db.writableDatabase.execSQL("DELETE FROM messages"); agent.db.writableDatabase.execSQL("DELETE FROM sessions"); showClearConfirm = false }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
    )

    if (showPermissionsPage) PermissionGrantScreen(ctx = ctx, onDone = { showPermissionsPage = false }, onSkip = { showPermissionsPage = false })
    if (showAccountSheet) AccountSheet(ctx = ctx, onDismiss = { showAccountSheet = false })
    if (showMiclawSheet) MiclawBridgeSheet(ctx = ctx, settings = s, onDismiss = { showMiclawSheet = false })
    if (showTokens) TokenDialog(s.modelName, onDismiss = { showTokens = false })
    if (showSponsor) SponsorDialog(onDismiss = { showSponsor = false })
    if (showVault) VaultSheet(ctx = ctx, onDismiss = { showVault = false })
    if (showMcp) McpSheet(ctx = ctx, onDismiss = { showMcp = false })
    if (showSkills) SkillSheet(ctx = ctx, onDismiss = { showSkills = false })
    if (showBackup) BackupSheet(ctx = ctx, onDismiss = { showBackup = false }, onChanged = {})
    if (showLinuxDownload) LinuxDownloadSheet(ctx = ctx, onDismiss = { showLinuxDownload = false })
    if (showVisionVoice) VisionVoiceSheet(ctx = ctx, settings = s, initialTab = visionVoiceTab, onDismiss = { showVisionVoice = false })
    if (showAboutSheet) AboutSheet(onDismiss = { showAboutSheet = false })
}

// ── 组件 ──

@Composable
fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 16.dp, bottom = 8.dp, top = 0.dp),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        letterSpacing = 0.5.sp)
}

@Composable
fun SettingRow(
    icon: ImageVector, title: String, subtitle: String,
    onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        trailing?.invoke() ?: Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SwitchRow(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF3B82F6)))
    }
}

@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("MBclaw", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column {
                Text("版本 v${BuildConfig.VERSION_NAME}", fontSize = 14.sp); Spacer(Modifier.height(4.dp))
                Text("作者 QQ: 1973054239", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)); Spacer(Modifier.height(2.dp))
                Text("酷安: coolapk.com/u/26771405", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun SponsorDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf("") }  // ""=选择页, "wechat"或"alipay"=大图页
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (selected.isEmpty()) "请作者喝杯奶茶 🧋" else if (selected == "wechat") "微信赞赏" else "支付宝赞赏",
                 fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (selected.isEmpty()) {
                    // 第一步：选择赞助方式
                    Text("选择赞助方式", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF07C160).copy(alpha = 0.1f),
                                modifier = Modifier.weight(1f).clickable { selected = "wechat" }.padding(16.dp)) {
                            Text("微信", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                 fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF07C160))
                        }
                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF1677FF).copy(alpha = 0.1f),
                                modifier = Modifier.weight(1f).clickable { selected = "alipay" }.padding(16.dp)) {
                            Text("支付宝", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                 fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1677FF))
                        }
                    }
                } else {
                    // 第二步：显示大图
                    TextButton(onClick = { selected = "" }) { Text("‹ 返回选择", fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                    val path = if (selected == "wechat") "donate/wechat.png" else "donate/alipay.jpg"
                    val tint = if (selected == "wechat") Color(0xFF07C160) else Color(0xFF1677FF)
                    val bmp = remember(path) {
                        try { ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it) } }
                        catch (_: Exception) { null }
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()) {
                        if (bmp != null) {
                            Image(bitmap = bmp.asImageBitmap(), contentDescription = selected,
                                  modifier = Modifier.fillMaxWidth().padding(8.dp),
                                  contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                        } else {
                            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.QrCode2, "收款码", modifier = Modifier.size(80.dp), tint = tint)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("请扫码赞赏", fontSize = 12.sp, color = tint)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSheet(ctx: android.content.Context, onDismiss: () -> Unit) {
    var labels by remember { mutableStateOf(SecureVault.listLabels(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var showView by remember { mutableStateOf<String?>(null) }
    var addLabel by remember { mutableStateOf("") }
    var addValue by remember { mutableStateOf("") }

    // 添加对话框
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加重要信息", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(value = addLabel, onValueChange = { addLabel = it },
                        label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("如：WiFi密码、银行卡号、API Key") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = addValue, onValueChange = { addValue = it },
                        label = { Text("内容") }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入要记住的内容...") }, minLines = 3)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (addLabel.isNotBlank() && addValue.isNotBlank()) {
                        SecureVault.put(ctx, addLabel, addValue)
                        labels = SecureVault.listLabels(ctx)
                        addLabel = ""; addValue = ""; showAdd = false
                        android.widget.Toast.makeText(ctx, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false; addLabel = ""; addValue = "" }) { Text("取消") } }
        )
    }

    // 查看对话框
    showView?.let { label ->
        val value = remember(label) { SecureVault.get(ctx, label) ?: "(读取失败)" }
        AlertDialog(
            onDismissRequest = { showView = null },
            title = { Text(label, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()) {
                        Text(value, Modifier.padding(12.dp), fontSize = 14.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("内容已复制到剪贴板", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            },
            dismissButton = { TextButton(onClick = { showView = null }) { Text("关闭") } }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("隐私保险箱", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                     modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("添加")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("存放重要信息，AI 和您随时查阅，不会遗忘。内容 AES-256 加密存储。",
                 style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            if (labels.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("还没有保存任何信息\n点击「添加」开始", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                         color = MaterialTheme.colorScheme.outline, fontSize = 14.sp)
                }
            } else {
                labels.forEach { label ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp).clickable { showView = label }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Label, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(label, Modifier.weight(1f), fontSize = 15.sp)
                            IconButton(onClick = {
                                SecureVault.remove(ctx, label)
                                labels = SecureVault.listLabels(ctx)
                            }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSheet(ctx: android.content.Context, onDismiss: () -> Unit) {
    val settings = remember { com.mbclaw.root.data.UserSettings(ctx) }
    var plugins by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val backend = com.mbclaw.root.data.Endpoints.backend(ctx)
    val pluginDir = remember { java.io.File("/sdcard/MBclaw/plugins").also { it.mkdirs() } }
    var localRefresh by remember { mutableStateOf(0) }

    LaunchedEffect(Unit, localRefresh) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("${backend.trimEnd('/')}/admin/client/mcp/list")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val j = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                val arr = j.optJSONArray("plugins")
                val list = mutableListOf<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val cat = p.optString("category", "通用")
                    list.add("${p.optString("name")} | ${p.optString("desc", "无描述")} | $cat | ⬇${p.optInt("installs", 0)}")
                }
                val local = pluginDir.listFiles()?.filter { it.isDirectory }?.map { d -> "${d.name} | 本地已安装 | 本地" } ?: emptyList()
                plugins = (local + (if (list.isEmpty()) listOf("暂无可用的MCP插件 | 云端市场暂未开放 | —") else list)).distinctBy { it.split(" | ")[0] }
            } catch (_: Exception) { if (plugins.isEmpty()) plugins = listOf("无法连接服务器 | 请检查网络 | —") }
            loading = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val fileName = uri.lastPathSegment ?: "import_${System.currentTimeMillis()}"
                val content = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?.take(5000) ?: ""
                // 保存原文到磁盘
                val target = java.io.File(pluginDir, fileName.replace(".mbplugin", "").replace(".zip", ""))
                target.mkdirs()
                java.io.File(target, fileName).writeText(content)
                // AI 分析标准化
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "AI 正在分析 MCP 插件...", android.widget.Toast.LENGTH_SHORT).show()
                }
                try {
                    val aiResult = com.mbclaw.root.api.DirectApiClient.chat(
                        baseUrl = settings.apiBaseUrl, apiKey = settings.apiKey, model = settings.modelName,
                        messages = listOf(
                            com.mbclaw.root.api.ChatMessage("system", "你是MBclaw标准格式检查器。分析文件判断类型(mcp/skill/api之一)，提取或补全name/description/parameters(inputSchema转parameters)。parameters必须是JSON Schema格式{\"type\":\"object\",\"properties\":{}}。只返回纯JSON，不要任何解释：{\"type\":\"mcp\",\"name\":\"...\",\"description\":\"...\",\"parameters\":{...}}"),
                            com.mbclaw.root.api.ChatMessage("user", content)
                        )
                    )
                    val j = org.json.JSONObject(aiResult.trim().removePrefix("```json").removeSuffix("```").trim())
                    val toolType = j.optString("type", "mcp")
                    ToolRegistry.register(ToolRegistry.ToolDef(
                        j.optString("name", fileName), j.optString("description", ""),
                        j.optJSONObject("parameters") ?: org.json.JSONObject("{\"type\":\"object\",\"properties\":{}}"),
                        "mcp", toolType
                    ))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "✅ 已导入并注册: ${j.optString("name", fileName)} (${toolType})", android.widget.Toast.LENGTH_SHORT).show()
                        localRefresh++
                    }
                } catch (aiErr: Exception) {
                    // AI 失败则直接按原始格式注册
                    ToolRegistry.register(ToolRegistry.ToolDef(fileName, "MCP插件: $fileName", org.json.JSONObject("{\"type\":\"object\",\"properties\":{}}"), "mcp", "mcp"))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "已导入: $fileName (AI分析跳过: ${aiErr.message?.take(50)})", android.widget.Toast.LENGTH_SHORT).show()
                        localRefresh++
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "导入失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MCP 插件市场", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(2.dp)); Text("导入")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Model Context Protocol · 连接外部工具和服务 (Google/GitHub等)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            if (loading) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else plugins.forEach { item ->
                val parts = item.split(" | ")
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(parts.getOrElse(0) { item }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(parts.getOrElse(1) { "" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        if (parts.size > 3) Text(parts[3], fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillSheet(ctx: android.content.Context, onDismiss: () -> Unit) {
    val settings = remember { com.mbclaw.root.data.UserSettings(ctx) }
    var skills by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val backend = com.mbclaw.root.data.Endpoints.backend(ctx)
    val skillDir = remember { java.io.File("/sdcard/MBclaw/skills").also { it.mkdirs() } }
    var localRefresh by remember { mutableStateOf(0) }

    LaunchedEffect(Unit, localRefresh) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("${backend.trimEnd('/')}/admin/client/skills/list")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val j = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                val arr = j.optJSONArray("skills")
                val list = mutableListOf<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val cat = s.optString("category", "通用")
                    list.add("${s.optString("name")} | ${s.optString("desc", "无描述")} | $cat | ⬇${s.optInt("downloads", 0)}")
                }
                val local = skillDir.listFiles()?.filter { it.isDirectory }?.map { d -> "${d.name} | 本地已安装 | 本地" } ?: emptyList()
                skills = (local + (if (list.isEmpty()) listOf("暂无可用的技能 | 云端市场暂未开放 | —") else list)).distinctBy { it.split(" | ")[0] }
            } catch (_: Exception) { if (skills.isEmpty()) skills = listOf("无法连接服务器 | 请检查网络 | —") }
            loading = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val fileName = uri.lastPathSegment ?: "import_${System.currentTimeMillis()}"
                val content = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?.take(5000) ?: ""
                // 保存原文到磁盘
                val target = java.io.File(skillDir, fileName.replace(".mbskill", "").replace(".md", ""))
                target.mkdirs()
                java.io.File(target, fileName).writeText(content)
                // AI 分析标准化
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "AI 正在分析技能...", android.widget.Toast.LENGTH_SHORT).show()
                }
                try {
                    val aiResult = com.mbclaw.root.api.DirectApiClient.chat(
                        baseUrl = settings.apiBaseUrl, apiKey = settings.apiKey, model = settings.modelName,
                        messages = listOf(
                            com.mbclaw.root.api.ChatMessage("system", "你是MBclaw标准格式检查器。分析文件判断类型(mcp/skill/api之一)，提取或补全name/description(YAML头或文章第一段内容)/parameters(JSON Schema)。Skill是YAML头+Markdown体，需保留prompt正文。只返回纯JSON，不要任何解释：{\"type\":\"skill\",\"name\":\"...\",\"description\":\"...\",\"parameters\":{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\",\"description\":\"技能提示词\"}}}}"),
                            com.mbclaw.root.api.ChatMessage("user", content)
                        )
                    )
                    val j = org.json.JSONObject(aiResult.trim().removePrefix("```json").removeSuffix("```").trim())
                    val toolType = j.optString("type", "skill")
                    ToolRegistry.register(ToolRegistry.ToolDef(
                        j.optString("name", fileName), j.optString("description", ""),
                        j.optJSONObject("parameters") ?: org.json.JSONObject("{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\",\"description\":\"技能提示词\"}}}"),
                        "skill", toolType
                    ))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "✅ 已导入并注册: ${j.optString("name", fileName)} (${toolType})", android.widget.Toast.LENGTH_SHORT).show()
                        localRefresh++
                    }
                } catch (aiErr: Exception) {
                    ToolRegistry.register(ToolRegistry.ToolDef(fileName, "Skill技能: $fileName", org.json.JSONObject("{\"type\":\"object\",\"properties\":{}}"), "skill", "skill"))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(ctx, "已导入: $fileName (AI分析跳过: ${aiErr.message?.take(50)})", android.widget.Toast.LENGTH_SHORT).show()
                        localRefresh++
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "导入失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Skill 技能", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(2.dp)); Text("导入")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("本地/云端技能 · 扩展AI能力边界", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            if (loading) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else skills.forEach { item ->
                val parts = item.split(" | ")
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(parts.getOrElse(0) { item }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(parts.getOrElse(1) { "" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        if (parts.size > 3) Text(parts[3], fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LinuxDownloadSheet(ctx: android.content.Context, onDismiss: () -> Unit) {
    val sandbox = remember { com.mbclaw.root.sandbox.LocalSandbox(ctx) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(sandbox.state) }
    var progress by remember { mutableIntStateOf(sandbox.progress) }
    var status by remember { mutableStateOf(sandbox.statusText) }

    AlertDialog(
        onDismissRequest = { if (state != com.mbclaw.root.sandbox.LocalSandbox.State.DOWNLOADING && state != com.mbclaw.root.sandbox.LocalSandbox.State.EXTRACTING) onDismiss() },
        title = { Text("🖥 完整 Linux 环境") },
        text = {
            Column {
                Text("预装 Python3 · bash · curl · git · vim · pip · sqlite",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                when (state) {
                    com.mbclaw.root.sandbox.LocalSandbox.State.NOT_INSTALLED -> {
                        Text("点击下载，等待完成即可使用。\n~200MB，建议 WiFi 环境。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    com.mbclaw.root.sandbox.LocalSandbox.State.DOWNLOADING -> {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("$progress% · $status", style = MaterialTheme.typography.labelSmall)
                    }
                    com.mbclaw.root.sandbox.LocalSandbox.State.EXTRACTING, com.mbclaw.root.sandbox.LocalSandbox.State.INSTALLING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(if (state == com.mbclaw.root.sandbox.LocalSandbox.State.INSTALLING) "安装工具包 (Python/git/pip...)..." else "解压中...",
                             style = MaterialTheme.typography.labelSmall)
                    }
                    com.mbclaw.root.sandbox.LocalSandbox.State.READY -> {
                        Icon(Icons.Filled.CheckCircle, null,
                            tint = Color(0xFF34C759), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("✅ Linux 环境就绪\n\n模式: ${if (sandbox.isRoot) "chroot (Root)" else "proot (免Root)"}\n路径: /data/mbclaw/linux",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    com.mbclaw.root.sandbox.LocalSandbox.State.FAILED -> {
                        Text("❌ $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                com.mbclaw.root.sandbox.LocalSandbox.State.NOT_INSTALLED, com.mbclaw.root.sandbox.LocalSandbox.State.FAILED -> {
                    Button(onClick = {
                        scope.launch {
                            sandbox.downloadAndInstall { s, p, t ->
                                state = s; progress = p; status = t
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state == com.mbclaw.root.sandbox.LocalSandbox.State.FAILED) "🔄 重试" else "📥 下载安装")
                    }
                }
                com.mbclaw.root.sandbox.LocalSandbox.State.READY -> {
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state != com.mbclaw.root.sandbox.LocalSandbox.State.DOWNLOADING && state != com.mbclaw.root.sandbox.LocalSandbox.State.EXTRACTING) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSheet(ctx: android.content.Context, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var appBackups by remember { mutableStateOf(com.mbclaw.root.agent.SafeOps.listBackups("apps")) }
    var fileBackups by remember { mutableStateOf(com.mbclaw.root.agent.SafeOps.listBackups("files")) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            Text("自动备份", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("超过1G的备份会被自动跳过 · 保留最近3份循环", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                val (ok, msg) = com.mbclaw.root.agent.SafeOps.backupApp(ctx, ctx.packageName)
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                if (ok) { appBackups = com.mbclaw.root.agent.SafeOps.listBackups("apps"); onChanged() }
            }, modifier = Modifier.fillMaxWidth()) { Text("📱 备份当前应用") }
            Spacer(Modifier.height(12.dp))
            if (appBackups.isEmpty() && fileBackups.isEmpty()) {
                Text("暂无备份", color = MaterialTheme.colorScheme.outline)
            } else {
                if (appBackups.isNotEmpty()) {
                    Text("应用备份 (${appBackups.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    appBackups.forEach { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 8.dp)) }
                }
                if (fileBackups.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("文件备份 (${fileBackups.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    fileBackups.forEach { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 8.dp)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun TokenDialog(modelName: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Token 消耗统计", fontWeight = FontWeight.Bold) },
        text = { Text("当前模型: $modelName\n暂无详细统计，后续版本支持。", fontSize = 14.sp) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
