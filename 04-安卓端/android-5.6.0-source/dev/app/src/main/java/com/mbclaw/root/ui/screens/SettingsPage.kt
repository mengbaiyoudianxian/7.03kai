package com.mbclaw.dev.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.launch
import com.mbclaw.dev.BuildConfig
import com.mbclaw.dev.agent.MBclawAgent
import com.mbclaw.dev.agent.PermissionTier
import com.mbclaw.dev.agent.RootBootstrap
import com.mbclaw.dev.agent.SafeOps
import com.mbclaw.dev.data.SecureVault

/**
 * 设置页 — 仿 MiClaw 二级设置布局
 *  • 头部：渐变卡片 + Logo + 标题
 *  • 分组：每组白色卡片，组间灰色分隔
 *  • 项：左标题 / 右 Switch 或 ›
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    agent: MBclawAgent,
    onBack: () -> Unit,
    onSetupProvider: () -> Unit,
    onOpenHand: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSessions: () -> Unit = {},        // 任务 7
) {
    val ctx = LocalContext.current
    val s = agent.settings
    var utopia by remember { mutableStateOf(s.utopiaEnabled) }
    var sync by remember { mutableStateOf(s.serverSyncEnabled) }
    // url 已锁死, 不需要本地状态
    var showTokens by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showPermissionsPage by remember { mutableStateOf(false) }    // 任务 5
    var showAccountSheet by remember { mutableStateOf(false) }       // 任务 8
    var showMiclawSheet by remember { mutableStateOf(false) }        // 任务 11
    val tier = remember { PermissionTier.get(ctx) }
    val (granted, total) = remember { RootBootstrap.status(ctx) }
    val vaultCount = remember { SecureVault.count(ctx) }
    val backupCount = remember {
        SafeOps.listBackups("apps").size + SafeOps.listBackups("files").size
    }
    // 任务 8: 读 QQ/微信账号信息
    val account = remember { com.mbclaw.dev.data.AccountManager.load(ctx) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ─── 顶部渐变头卡 (使用真实 LOGO) ───
            val logoBmp = remember {
                try { ctx.assets.open("donate/logo.png").use { android.graphics.BitmapFactory.decodeStream(it) } }
                catch (_: Exception) { null }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    Modifier.background(
                        Brush.linearGradient(listOf(
                            Color(0xFFE9F1FB),
                            Color(0xFFF7FAFE),
                        ))
                    ).padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (logoBmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = logoBmp.asImageBitmap(),
                                contentDescription = "MBclaw",
                                modifier = Modifier.size(64.dp),
                            )
                        } else {
                            Surface(shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(56.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("M", fontWeight = FontWeight.Bold,
                                         color = Color.White,
                                         style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("MBclaw", fontWeight = FontWeight.SemiBold,
                             style = MaterialTheme.typography.titleMedium,
                             color = Color(0xFF1A2434))
                    }
                }
            }

            // ─── 组 1: 账号 & 设备 ───
            SettingGroup {
                // 任务 8: 我的账号 → 弹出账号面板
                SettingItemRow(
                    "我的账号",
                    subtitle = account.displayName(),
                    leading = { com.mbclaw.dev.ui.screens.AccountAvatar(account, size = 40) },
                    onClick = { showAccountSheet = true },
                )
                SettingDivider()
                // 任务 7: 当前会话点击 → 全部会话搜索浮层
                SettingItemRow(
                    "当前会话",
                    subtitle = "${try { agent.db.getSessions().size } catch (_: Exception) { 0 }} 段历史 · 点击查看全部",
                    onClick = onOpenSessions,
                )
                SettingDivider()
                // 权限状态点击 → 详细页(含Root一键授权)
                SettingItemRow(
                    "权限状态",
                    subtitle = "$granted / $total 已授予 · " +
                              (if (tier.hasRoot) "ROOT ✅" else "无 Root") +
                              " · " + (if (tier.hasAccessibility) "无障碍 ✅" else "无障碍 ❌"),
                    onClick = { showPermissionsPage = true },
                )
            }

            // ─── 外观 ───
            SectionTitle("外观")
            SettingGroup {
                val currentMode = com.mbclaw.dev.ui.theme.ThemePreference.mode(ctx)
                Column(Modifier.padding(16.dp)) {
                    Text("主题模式", fontWeight = FontWeight.SemiBold,
                         style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "light" to "☀️ 浅色",
                            "dark" to "🌙 深色",
                            "system" to "📱 跟随系统",
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = currentMode == mode,
                                onClick = { com.mbclaw.dev.ui.theme.ThemePreference.setMode(ctx, mode) },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            SectionTitle("模型与工具")
            SettingGroup {
                SettingItemRow(
                    "模型 API 配置",
                    subtitle = if (s.isConfigured())
                        if (s.providerId == "miclaw-bridge") "🎁 白嫖算力 · ${s.modelName}"
                        else "${s.providerId} · ${s.modelName}"
                    else "⚠️ 未配置",
                    onClick = onSetupProvider,
                )
                SettingDivider()
                // 视觉模型
                var showVision by remember { mutableStateOf(false) }
                var showVoice by remember { mutableStateOf(false) }
                SettingItemRow(
                    "👁 视觉识图模型",
                    subtitle = if (s.visionEnabled && s.visionApiKey.isNotBlank())
                        "已配 · ${s.visionModel}"
                    else "未配 · 主模型不支持识图时使用",
                    onClick = { showVision = true },
                )
                SettingDivider()
                SettingItemRow(
                    "🎤 语音 TTS / ASR 模型",
                    subtitle = if (s.voiceEnabled && s.voiceApiKey.isNotBlank())
                        "已配 · ${s.voiceTtsModel} / ${s.voiceAsrModel}"
                    else "未配 · 输入/输出语音时使用",
                    onClick = { showVoice = true },
                )
                SettingDivider()
                SettingItemRow(
                    "工具市场",
                    subtitle = "${com.mbclaw.dev.agent.ToolRegistry.ALL.size} 个工具 · 添加 / 上传 / 下载",
                    onClick = onOpenTools,
                )
                SettingDivider()
                SettingItemRow(
                    "🎁 白嫖 MiClaw 算力",
                    subtitle = "通过 NEORUAA bridge 中转 · 服务器隐藏 Key",
                    onClick = { showMiclawSheet = true },
                )
                SettingDivider()

                if (showVision) VisionVoiceSheet(ctx = ctx, settings = s, initialTab = 0, onDismiss = { showVision = false })
                if (showVoice) VisionVoiceSheet(ctx = ctx, settings = s, initialTab = 1, onDismiss = { showVoice = false })
                SettingItemRow(
                    "智能手 (Agent Hand)",
                    subtitle = "看得见点得准 · 校准 / 区块识别 / 模糊点击",
                    onClick = onOpenHand,
                )
                SettingDivider()
                // Linux 环境 (按需下载)
                var showLinuxDownload by remember { mutableStateOf(false) }
                SettingItemRow(
                    "🖥 完整 Linux 环境",
                    subtitle = if (com.mbclaw.dev.sandbox.LocalSandbox(ctx).isInstalled) "✅ 已安装 · 706MB · /data/mbclaw/linux"
                              else "一键下载 · 278MB · 预装 Python/JDK17/Git/GCC/CMake",
                    onClick = { showLinuxDownload = true }
                )
                if (showLinuxDownload) {
                    LinuxDownloadSheet(ctx, onDismiss = { showLinuxDownload = false })
                }
                SettingDivider()
                // MCP 插件市场 (预留接口)
                SettingItemRow(
                    "🔌 MCP 插件市场",
                    subtitle = "Model Context Protocol · 连接外部工具 (Google/GitHub/...)",
                    onClick = {
                        android.widget.Toast.makeText(ctx, "MCP 市场即将开放，敬请期待", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }

            SectionTitle("乌托邦计划")
            SettingGroup {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用乌托邦", fontWeight = FontWeight.SemiBold,
                             style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = utopia, onCheckedChange = { utopia = it; s.utopiaEnabled = it })
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("贡献你的非隐私数据改善所有人的 AI 体验。开启后你的 token 消耗可能会提高昨日的 1%-5% 左右，但性能、能力、记忆力功能都被母体提高到 100%。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline,
                         lineHeight = TextUnit(20f, TextUnitType.Sp))

                    AnimatedVisibility(visible = utopia) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("连接 MBclaw 服务器", fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(2.dp))
                                    Text("地址由 MBclaw 官方动态分配, 不对外公开",
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.outline)
                                }
                                Switch(checked = sync,
                                       onCheckedChange = { sync = it; s.serverSyncEnabled = it })
                            }
                        }
                    }
                }
            }

            SectionTitle("隐私与安全")
            SettingGroup {
                SettingItemRow(
                    "🔐 隐私保险箱",
                    subtitle = "$vaultCount 项 · AES-256-GCM · 设备指纹派生",
                ) {}
                SettingDivider()
                SettingItemRow(
                    "💾 自动备份",
                    subtitle = "$backupCount 份备份 · 删除前自动备份, 3 份循环",
                ) {}
                SettingDivider()
                SettingItemRow(
                    "📊 Token 消耗统计",
                    onClick = { showTokens = true },
                )
                SettingDivider()
                SettingItemRow(
                    "🗑️ 清除历史对话",
                    subtitle = "删除所有对话记录, 不可恢复",
                    danger = true,
                    onClick = { showClearConfirm = true },
                )
            }

            // 调试入口
            SectionTitle("开发者调试")
            var showDebug by remember { mutableStateOf(false) }
            SettingGroup {
                val cfg = remember { com.mbclaw.dev.agent.DebugRemote.load(ctx) }
                SettingItemRow(
                    "🐛 远程调试",
                    subtitle = if (cfg.enabled) "已开启 · 连接码: ${cfg.code} · 点击复制"
                              else "让作者远程查看你的设备状态 (排查 bug 专用)",
                    onClick = {
                        if (cfg.enabled) {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("debug code", cfg.code))
                            android.widget.Toast.makeText(ctx, "已复制: ${cfg.code}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        showDebug = true
                    },
                )
            }
            if (showDebug) DebugRemoteSheet(ctx, onDismiss = { showDebug = false })

            SectionTitle("版本信息")
            var showDonate by remember { mutableStateOf(false) }
            SettingGroup {
                // MBclaw - 显示当前/最新版本 + 检测更新
                MBclawVersionRowInline(ctx)
                SettingDivider()
                SettingItemRow(
                    "酷安",
                    subtitle = "coolapk.com/u/26771405 · 关注作者",
                    onClick = {
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.coolapk.com/u/26771405"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    }
                )
                SettingDivider()
                SettingItemRow(
                    "作者 QQ",
                    subtitle = "1973054239 · 点击复制",
                    onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("MBclaw QQ", "1973054239"))
                        android.widget.Toast.makeText(ctx, "已复制 QQ：1973054239",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
                SettingDivider()
                SettingItemRow(
                    "💖 友情赞助",
                    subtitle = "请作者喝杯奶茶",
                    onClick = { showDonate = true }
                )
            }
            if (showDonate) DonateImageDialog(ctx, onDismiss = { showDonate = false })

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showTokens) {
        AlertDialog(
            onDismissRequest = { showTokens = false },
            icon = { Icon(Icons.Filled.Analytics, null) },
            title = { Text("Token 消耗统计") },
            text = {
                Column {
                    val vm = ChatViewModel.get(ctx, agent)
                    val st = vm.tokenStats.value
                    val totalIn = st.sessionTokensIn
                    val totalOut = st.sessionTokensOut
                    val total = totalIn + totalOut

                    Text("📊 本次会话累计", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("• 输入 token: $totalIn", style = MaterialTheme.typography.bodyMedium)
                    Text("• 输出 token: $totalOut", style = MaterialTheme.typography.bodyMedium)
                    Text("• 上一轮: ↑${st.lastTurnIn}  ↓${st.lastTurnOut}",
                         style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(14.dp))

                    // 乌托邦状态
                    if (s.utopiaEnabled) {
                        Text("🌍 乌托邦已开启", fontWeight = FontWeight.SemiBold,
                             color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text("感谢你参与！你的非隐私数据会帮助优化所有人的 AI 体验。",
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.outline)
                    } else {
                        Text("🔒 乌托邦未开启", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("在「我的 → 乌托邦计划」开启可解锁完整能力。",
                             style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.outline)
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("详细统计请查看服务端管理面板\n(地址已隐藏, 直接在浏览器输入即可)",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = { TextButton(onClick = { showTokens = false }) { Text("好的") } }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Filled.DeleteForever, null,
                          tint = MaterialTheme.colorScheme.error) },
            title = { Text("清除所有历史对话?") },
            text = { Text("将删除全部对话记录，不可恢复。\n（隐私 Vault 与备份不受影响）") },
            confirmButton = {
                TextButton(
                    onClick = {
                        agent.db.writableDatabase.execSQL("DELETE FROM messages")
                        agent.db.writableDatabase.execSQL("DELETE FROM sessions")
                        showClearConfirm = false
                        ChatViewModel.get(ctx, agent).newSession()
                        android.widget.Toast.makeText(ctx, "已清除所有历史",
                            android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showPermissionsPage) {
        PermissionsDetailDialog(ctx = ctx, onDismiss = { showPermissionsPage = false })
    }
    if (showAccountSheet) {
        AccountSheet(ctx = ctx, onDismiss = { showAccountSheet = false })
    }
    if (showMiclawSheet) {
        MiclawBridgeSheet(ctx = ctx, settings = s, onDismiss = { showMiclawSheet = false })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        fontWeight = FontWeight.Normal,
    )
}

@Composable
private fun SettingGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingItemRow(
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = if (danger) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
        else if (onClick != null) {
            Icon(Icons.Filled.ChevronRight, null,
                 tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 工具市场页（从设置进入），带返回按钮 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsPageWithBack(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("工具市场", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad)) {
            ToolsScreen()
        }
    }
}

/** 设置页内嵌的版本行 (显示当前 + 最新, 点击检测/跳下载) */
@Composable
private fun MBclawVersionRowInline(ctx: android.content.Context) {
    var latest by remember { mutableStateOf("检测中...") }
    var hasUpdate by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf("") }
    val current = BuildConfig.VERSION_NAME
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val backend = com.mbclaw.dev.data.Endpoints.backend(ctx)
                val ts = System.currentTimeMillis()
                val u = java.net.URL("${backend.trimEnd('/')}/admin/client/version?current=$current&_t=$ts")
                val conn = u.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.setRequestProperty("Cache-Control", "no-cache, no-store")
                conn.setRequestProperty("Pragma", "no-cache")
                val j = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    latest = j.optString("latest", current)
                    hasUpdate = j.optBoolean("has_update", false)
                    downloadUrl = j.optString("download_url", "")
                }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    latest = "无法连接"
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable {
            if (hasUpdate && downloadUrl.startsWith("http")) {
                // 加时间戳防系统下载器缓存
                val noCache = if (downloadUrl.contains("?")) "$downloadUrl&_t=${System.currentTimeMillis()}"
                              else "$downloadUrl?_t=${System.currentTimeMillis()}"
                val i = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(noCache))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MBclaw", style = MaterialTheme.typography.bodyLarge)
                if (hasUpdate) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.errorContainer) {
                        Text("有更新",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("v$current → 最新 $latest",
                style = MaterialTheme.typography.labelSmall,
                color = if (hasUpdate) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline)
        }
        if (hasUpdate) {
            Icon(Icons.Filled.Download, "下载",
                tint = MaterialTheme.colorScheme.primary)
        } else {
            Icon(Icons.Filled.CheckCircle, "最新",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp))
        }
    }
}

/** 设置内的赞赏码弹窗 (复用 assets/donate 的真实图) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DonateImageDialog(ctx: android.content.Context, onDismiss: () -> Unit) {
    val wxBmp = remember { try { ctx.assets.open("donate/wechat.png").use { android.graphics.BitmapFactory.decodeStream(it) } } catch (_: Exception) { null } }
    val zfbBmp = remember { try { ctx.assets.open("donate/alipay.jpg").use { android.graphics.BitmapFactory.decodeStream(it) } } catch (_: Exception) { null } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💖 请作者喝杯奶茶", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                       modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("感谢你的支持 ✨",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        Triple("微信", wxBmp, androidx.compose.ui.graphics.Color(0xFF07C160)),
                        Triple("支付宝", zfbBmp, androidx.compose.ui.graphics.Color(0xFF1677FF)),
                    ).forEach { (label, bmp, tint) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.size(140.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (bmp != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = label,
                                            modifier = Modifier.fillMaxSize().padding(4.dp),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                        )
                                    } else {
                                        Icon(Icons.Filled.QrCode2, label,
                                            modifier = Modifier.size(64.dp), tint = tint)
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(label, style = MaterialTheme.typography.bodySmall,
                                color = tint, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 调试入口 — 让作者远程查看 / 控制设备 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugRemoteSheet(ctx: android.content.Context, onDismiss: () -> Unit) {
    var cfg by remember { mutableStateOf(com.mbclaw.dev.agent.DebugRemote.load(ctx)) }
    var enabled by remember { mutableStateOf(cfg.enabled) }
    var code by remember { mutableStateOf(cfg.code) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(20.dp).imePadding().fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text("🐛 远程调试", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("开启后, 作者可以远程查看你的设备状态 (权限/触摸/日志)\n" +
                 "用于排查你遇到的 bug, 不传你的隐私数据。",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用调试", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it.trim() },
                label = { Text("连接码 (作者给你的)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("如 mbclaw-bug-arena") },
                shape = RoundedCornerShape(12.dp),
                enabled = enabled,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = {
                    com.mbclaw.dev.agent.DebugRemote.save(ctx,
                        com.mbclaw.dev.agent.DebugRemote.Config(enabled, code))
                    android.widget.Toast.makeText(ctx,
                        if (enabled && code.isNotBlank()) "调试已开启" else "调试已关闭",
                        android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }, modifier = Modifier.weight(1f)) { Text("保存") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Linux环境下载弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinuxDownloadSheet(ctx: android.content.Context, onDismiss: () -> Unit) {
    val sandbox = remember { com.mbclaw.dev.sandbox.LocalSandbox(ctx) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(sandbox.state) }
    var progress by remember { mutableIntStateOf(sandbox.progress) }
    var status by remember { mutableStateOf(sandbox.statusText) }
    var errorDetail by remember { mutableStateOf("") }
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val isBusy = state == com.mbclaw.dev.sandbox.LocalSandbox.State.DOWNLOADING
              || state == com.mbclaw.dev.sandbox.LocalSandbox.State.EXTRACTING

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = {
            Text(if (state == com.mbclaw.dev.sandbox.LocalSandbox.State.READY) "✅ Linux 环境就绪"
            else if (state == com.mbclaw.dev.sandbox.LocalSandbox.State.FAILED) "❌ Linux 环境"
            else "🖥 完整 Linux 环境")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("预装 Python3 · OpenJDK17 · Git · GCC/G++ · CMake · Vim · pip · SQLite · OpenSSH · build-base",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                when (state) {
                    com.mbclaw.dev.sandbox.LocalSandbox.State.NOT_INSTALLED -> {
                        Text("点击下方按钮，等待完成即可使用。\n\n📦 278MB 压缩包 → 解压后 706MB\n⏱ 建议 WiFi 环境，约 2-5 分钟\n📡 下载途中可关闭此弹窗，通知栏会持续显示进度",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    com.mbclaw.dev.sandbox.LocalSandbox.State.DOWNLOADING -> {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text("$progress%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("📢 下拉通知栏可查看下载进度，关闭弹窗不影响下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    com.mbclaw.dev.sandbox.LocalSandbox.State.EXTRACTING, com.mbclaw.dev.sandbox.LocalSandbox.State.INSTALLING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text("解压 706MB 完整 Linux 环境，预计 1-2 分钟...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    com.mbclaw.dev.sandbox.LocalSandbox.State.READY -> {
                        Icon(Icons.Filled.CheckCircle, null,
                            tint = androidx.compose.ui.graphics.Color(0xFF34C759), modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("✅ Linux 环境就绪\n\n模式: ${if (sandbox.isRoot) "chroot (Root)" else "proot (免Root)"}\n路径: /data/mbclaw/linux\n预装 20+ 开发工具\n\nAgent 可通过 local_sandbox_run 直接执行命令",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    com.mbclaw.dev.sandbox.LocalSandbox.State.FAILED -> {
                        Icon(Icons.Filled.Warning, null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("❌ 下载失败", style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text(status, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        if (errorDetail.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("错误详情: $errorDetail", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                com.mbclaw.dev.sandbox.LocalSandbox.State.NOT_INSTALLED, com.mbclaw.dev.sandbox.LocalSandbox.State.FAILED -> {
                    Button(onClick = {
                        errorDetail = ""
                        android.widget.Toast.makeText(ctx, "开始下载 Linux 环境 · 278MB", android.widget.Toast.LENGTH_SHORT).show()
                        downloadJob = scope.launch {
                            sandbox.downloadAndInstall { s, p, t ->
                                state = s; progress = p; status = t
                                if (s == com.mbclaw.dev.sandbox.LocalSandbox.State.FAILED) {
                                    errorDetail = sandbox.lastError
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state == com.mbclaw.dev.sandbox.LocalSandbox.State.FAILED) "🔄 重试" else "📥 下载安装 (278MB)")
                    }
                }
                com.mbclaw.dev.sandbox.LocalSandbox.State.READY -> {
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (isBusy) {
                TextButton(onClick = {
                    android.widget.Toast.makeText(ctx, "下载继续在后台进行，下拉通知栏查看进度", android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Text("后台下载") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}
