package com.mbclaw.dev.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import com.mbclaw.dev.agent.PermissionPolicy
import com.mbclaw.dev.agent.RootBootstrap
import com.mbclaw.dev.data.Account
import com.mbclaw.dev.data.AccountManager
import com.mbclaw.dev.data.UserSettings
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────
// 1. 账号头像组件 (供 SettingsPage 调用)
// ──────────────────────────────────────────────────────
@Composable
fun AccountAvatar(account: Account, size: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var bmp by remember(account.qqId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(account.qqId) {
        val path = AccountManager.downloadAvatarIfNeeded(ctx, account)
        if (path != null) {
            bmp = android.graphics.BitmapFactory.decodeFile(path)
        }
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            val b = bmp
            if (b != null) {
                androidx.compose.foundation.Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else if (account.qqId.isNotBlank()) {
                Text("Q", fontWeight = FontWeight.Bold,
                     color = MaterialTheme.colorScheme.primary,
                     style = MaterialTheme.typography.titleLarge)
            } else if (account.weixinId.isNotBlank()) {
                Text("微", fontWeight = FontWeight.Bold,
                     color = Color(0xFF07C160),
                     style = MaterialTheme.typography.titleLarge)
            } else {
                Icon(Icons.Filled.PersonOutline, "未登录",
                     tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// asImageBitmap 已通过 import 提供

// ──────────────────────────────────────────────────────
// 2. 账号设置 Sheet
// ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(ctx: android.content.Context, onDismiss: () -> Unit) {
    var account by remember { mutableStateOf(AccountManager.load(ctx)) }
    var qq by remember { mutableStateOf(account.qqId) }
    var wx by remember { mutableStateOf(account.weixinId) }
    var nick by remember { mutableStateOf(account.nickname) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val serverUrl = remember { UserSettings(ctx).serverUrl }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text("我的账号", fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("优先 QQ，其次微信。账号同步至 $serverUrl",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))

            // 当前头像预览
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AccountAvatar(account, size = 80)
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = qq, onValueChange = { qq = it.filter { c -> c.isDigit() } },
                label = { Text("QQ 号 (头像自动读取)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = wx, onValueChange = { wx = it },
                label = { Text("微信 ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nick, onValueChange = { nick = it },
                label = { Text("昵称 (可选)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        loading = true
                        scope.launch {
                            val key = qq.ifBlank { wx }
                            if (key.isBlank()) {
                                loading = false
                                return@launch
                            }
                            val remote = AccountManager.fetchFromServer(serverUrl, key)
                            if (remote != null) {
                                qq = remote.qqId; wx = remote.weixinId; nick = remote.nickname
                                android.widget.Toast.makeText(ctx, "已从云端恢复账号",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(ctx, "云端未找到，请直接保存",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                            loading = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !loading,
                ) { Text("☁ 云端恢复") }

                Button(
                    onClick = {
                        loading = true
                        scope.launch {
                            val acc = Account(qqId = qq.trim(), weixinId = wx.trim(), nickname = nick.trim())
                            AccountManager.save(ctx, acc)
                            account = acc
                            AccountManager.syncToServer(ctx, acc, serverUrl)
                            loading = false
                            android.widget.Toast.makeText(ctx, "已保存并同步",
                                android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !loading,
                ) { Text("💾 保存") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ──────────────────────────────────────────────────────
// 3. 权限详情对话框 (任务 5)
// ──────────────────────────────────────────────────────
@Composable
fun PermissionsDetailDialog(ctx: android.content.Context, onDismiss: () -> Unit) {
    val all = remember {
        // 必备权限置顶
        RootBootstrap.DANGEROUS.sortedByDescending {
            com.mbclaw.dev.agent.PermissionLabels.get(it).essential
        }
    }
    var refresh by remember { mutableStateOf(0) }
    var picking by remember { mutableStateOf<String?>(null) }
    var showPermGrant by remember { mutableStateOf(false) }

    fun jumpToAppSettings() {
        val i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", ctx.packageName, null)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限详情 (${all.size})") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(all) { perm ->
                    key(refresh, perm) {
                        val info = com.mbclaw.dev.agent.PermissionLabels.get(perm)
                        val policy = PermissionPolicy.get(ctx, perm)
                        val granted = ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        Row(
                            Modifier.fillMaxWidth().clickable { picking = perm }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(shape = CircleShape, modifier = Modifier.size(8.dp),
                                color = when {
                                    policy == PermissionPolicy.Policy.DENY_FOREVER -> MaterialTheme.colorScheme.error
                                    granted -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.outline
                                }) {}
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(info.zh, style = MaterialTheme.typography.bodyMedium,
                                         fontWeight = if (info.essential) FontWeight.SemiBold else FontWeight.Normal)
                                    if (info.essential) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                                color = MaterialTheme.colorScheme.primaryContainer) {
                                            Text("必备", modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                if (info.desc.isNotEmpty()) {
                                    Text(info.desc, style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.outline, maxLines = 1)
                                }
                            }
                            Text(when (policy) {
                                PermissionPolicy.Policy.ALLOW -> if (granted) "✅" else "△"
                                PermissionPolicy.Policy.DENY_FOREVER -> "🚫"
                                PermissionPolicy.Policy.ASK_EACH_TIME -> "🔁"
                            }, style = MaterialTheme.typography.bodyLarge)
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        },
        confirmButton = {
            Column {
                var showEarlyBird by remember { mutableStateOf(false) }
                var showNoRootMsg by remember { mutableStateOf(false) }
                Button(
                    onClick = { showEarlyBird = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Root 一键授权") }

                if (showEarlyBird) {
                    AlertDialog(
                        onDismissRequest = { showEarlyBird = false },
                        title = { Text("🙃") },
                        text = { Text("早知如此何必当初呢") },
                        confirmButton = {
                            Button(onClick = {
                                showEarlyBird = false
                                val tier = com.mbclaw.dev.agent.PermissionTier.get(ctx)
                                if (tier.hasRoot) {
                                    showPermGrant = true
                                } else {
                                    showNoRootMsg = true
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("开始授权")
                            }
                        },
                        dismissButton = { TextButton(onClick = { showEarlyBird = false }) { Text("取消") } }
                    )
                }

                if (showNoRootMsg) {
                    AlertDialog(
                        onDismissRequest = { showNoRootMsg = false },
                        title = { Text("😤") },
                        text = { Text("没有root权限还想拥有我，去自己慢慢一个一个点吧") },
                        confirmButton = { TextButton(onClick = { showNoRootMsg = false }) { Text("好吧...") } }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = { jumpToAppSettings() }) { Text("系统设置") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        RootBootstrap.resetAndRerun(ctx)
                        refresh++
                    }) { Text("重新授予") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    if (showPermGrant) {
        PermissionGrantScreen(
            ctx = ctx,
            onDone = { showPermGrant = false },
            onSkip = { showPermGrant = false }
        )
    }

    picking?.let { perm ->
        val info = com.mbclaw.dev.agent.PermissionLabels.get(perm)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(info.zh + if (info.essential) " (必备)" else "") },
            text = {
                Column {
                    Text(info.desc.ifEmpty { perm.substringAfterLast('.') },
                         style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("选择处理方式：",
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = {
                Column {
                    listOf(
                        Triple("以后全部禁止", PermissionPolicy.Policy.DENY_FOREVER, MaterialTheme.colorScheme.error),
                        Triple("打开 (推荐)", PermissionPolicy.Policy.ALLOW, MaterialTheme.colorScheme.primary),
                        Triple("每次启动默认打开", PermissionPolicy.Policy.ASK_EACH_TIME, MaterialTheme.colorScheme.tertiary),
                    ).forEach { (label, pol, col) ->
                        TextButton(
                            onClick = {
                                PermissionPolicy.set(ctx, perm, pol)
                                picking = null
                                refresh++
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = col),
                        ) { Text(label) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(
                        onClick = {
                            jumpToAppSettings()
                            picking = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🔧 跳转系统设置手动授权") }
                }
            }
        )
    }
}

// ──────────────────────────────────────────────────────
// 4. MiClaw 算力桥接 (任务 11) - 完整版
// ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiclawBridgeSheet(
    ctx: android.content.Context,
    settings: UserSettings,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.INTRO) }
    var status by remember { mutableStateOf("点击「申请白嫖」，服务器会为你创建专属代理实例") }
    var applicationId by remember { mutableStateOf("") }
    var loginUrl by remember { mutableStateOf("") }
    val account = remember { com.mbclaw.dev.data.AccountManager.load(ctx) }
    val userId = remember { account.qqId.ifBlank { account.weixinId }.ifBlank { "anonymous_${System.currentTimeMillis() / 1000}" } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("🎁", style = MaterialTheme.typography.titleLarge) }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("白嫖 MiClaw 算力", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("作者前期赞助 · 自助登录即可使用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                // 白嫖教程入口
                TextButton(onClick = {
                    val i = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.coolapk.com/feed/72466254?s=ZTk0ZjQ5NDMxOTg3ZmNkZzZhMzg2OGE0ega1622")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                }) { Text("白嫖教程 ›", style = MaterialTheme.typography.labelMedium) }
            }
            Spacer(Modifier.height(12.dp))
            Text("点击下方按钮，服务器会为你接入 MiClaw 算力。前期作者免费给几位试试，后续可能考虑收费，因为服务器费用高、消耗大。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp))
            Spacer(Modifier.height(8.dp))
            Text("如果你需要 MiClaw 内测教程, 点上方「白嫖教程」",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (phase) {
                    Phase.READY -> androidx.compose.ui.graphics.Color(0xFFE8F5E9)
                    Phase.FAILED, Phase.BLOCKED -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (phase) {
                        Phase.APPLYING, Phase.PENDING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Phase.READY -> Icon(Icons.Filled.CheckCircle, null, tint = androidx.compose.ui.graphics.Color(0xFF34C759), modifier = Modifier.size(20.dp))
                        Phase.FAILED, Phase.BLOCKED -> Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        else -> Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            when (phase) {
                Phase.INTRO, Phase.APPLYING -> Button(
                    onClick = {
                        phase = Phase.APPLYING
                        status = "正在创建代理实例..."
                        scope.launch {
                            val r = com.mbclaw.dev.agent.MiclawBridge.apply(ctx, settings.serverUrl, userId)
                            if (r.killCommand) {
                                com.mbclaw.dev.agent.AntiTamper.writeKillFlag(ctx)
                                phase = Phase.BLOCKED
                                status = "🚫 你已被拉黑：${r.message}"
                            } else if (r.approved) {
                                applicationId = r.applicationId
                                loginUrl = r.loginUrl
                                phase = Phase.PENDING
                                status = "✅ 申请已通过！点下方按钮去登录 (ID: ${r.applicationId.take(10)})"
                            } else {
                                phase = Phase.FAILED
                                status = "❌ 申请失败：${r.message}"
                            }
                        }
                    },
                    enabled = phase != Phase.APPLYING,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (phase == Phase.APPLYING) "申请中..." else "📝 申请白嫖") }
                Phase.PENDING -> Button(
                    onClick = {
                        // 用内置浏览器打开登录页
                        val i = android.content.Intent(ctx, com.mbclaw.dev.ui.BrowserActivity::class.java).apply {
                            putExtra("url", "http://47.83.2.188:8765")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(i)
                        scope.launch {
                            status = "⏳ 等你登录...每 3s 查一次"
                            repeat(40) {
                                kotlinx.coroutines.delay(3000)
                                val s = com.mbclaw.dev.agent.MiclawBridge.status(settings.serverUrl, applicationId)
                                if (s.ready) {
                                    com.mbclaw.dev.agent.MiclawBridge.applyToSettings(settings, settings.serverUrl, s.userToken, s.model)
                                    phase = Phase.READY
                                    status = "✅ 已自动配好！${if (s.isStub) "(服务器是 stub 模式)" else ""}"
                                    return@launch
                                }
                            }
                            phase = Phase.FAILED
                            status = "⏰ 2 分钟内未检测到登录"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🚀 打开登录页") }
                Phase.READY -> Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("✅ 关闭") }
                Phase.FAILED -> Button(onClick = { phase = Phase.INTRO; status = "重新申请" }, modifier = Modifier.fillMaxWidth()) { Text("🔄 重试") }
                Phase.BLOCKED -> Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("已封禁") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private enum class Phase { INTRO, APPLYING, PENDING, READY, FAILED, BLOCKED }
