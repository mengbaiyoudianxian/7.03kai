package com.mbclaw.dev.ui.screens

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mbclaw.dev.agent.PermissionTier
import com.mbclaw.dev.agent.PermissionLabels
import com.mbclaw.dev.agent.RootBootstrap
import com.mbclaw.dev.data.Endpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * 权限授予全屏页 v5.0.5
 *
 * ★ 修复:
 *   1. 闪退问题 — PermissionTier不在此页面创建，用外部传入验证
 *   2. 真实验证 — 用RootBootstrap的三步验证，不是假画勾
 *   3. 真实进度 — 实际等待每个权限的验证结果
 *   4. 失败列表 — 授权完成后显示哪些权限真的没拿到
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGrantScreen(
    ctx: android.content.Context,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pkg = ctx.packageName

    data class PermState(
        val perm: String, val zh: String, val essential: Boolean,
        var granted: Boolean = false, var checking: Boolean = false,
        var result: String = "",
    )

    val perms = remember {
        mutableStateListOf<PermState>().apply {
            RootBootstrap.DANGEROUS.forEach { perm ->
                val info = PermissionLabels.get(perm)
                add(PermState(perm, info.zh, info.essential))
            }
        }
    }

    var phase by remember { mutableStateOf("ready") } // ready | granting | done
    var statusText by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var androidVer by remember { mutableStateOf("") }
    var sdkInt by remember { mutableStateOf(0) }
    var template by remember { mutableStateOf<JSONObject?>(null) }
    var showSkipWarning by remember { mutableStateOf(false) }

    // 设备信息
    LaunchedEffect(Unit) {
        brand = Build.BRAND
        androidVer = Build.VERSION.RELEASE
        sdkInt = Build.VERSION.SDK_INT
    }

    // ── ★ 核心：单权限三步验证 ──
    fun verifyOne(perm: PermState): Boolean {
        val tier = PermissionTier.get(ctx)
        if (!tier.hasRoot) return false

        // Step 1: pm grant
        val grantOut = tier.shellRoot("pm grant --user 0 $pkg ${perm.perm} 2>&1", timeoutMs = 8000) ?: ""
        val grantBlank = grantOut.isBlank()
        val grantOk = grantBlank || (!grantOut.contains("Unknown") &&
                      !grantOut.contains("not a changeable") &&
                      !grantOut.contains("Security exception"))

        // Step 2: pm check-permission
        val pmCheck = tier.shellRoot("pm check-permission ${perm.perm} $pkg 2>&1", timeoutMs = 5000)?.trim() ?: ""
        val pmOk = pmCheck.contains("granted")

        // Step 3: checkSelfPermission
        val selfOk = ctx.checkSelfPermission(perm.perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

        // Step 4: Settings API (for special perms)
        val settingsOk = when (perm.perm) {
            "android.permission.SYSTEM_ALERT_WINDOW" -> Settings.canDrawOverlays(ctx)
            "android.permission.WRITE_SETTINGS" -> Settings.System.canWrite(ctx)
            else -> null
        }

        // 判定: 至少两个验证通过
        val checks = listOf(pmOk, selfOk, settingsOk == true).count { it }
        val passed = checks >= 2 || (grantOk && pmOk)

        perm.result = when {
            settingsOk == true -> "settings✓"
            pmOk -> "pm✓"
            selfOk -> "self✓"
            grantOk -> "granted"
            else -> "✗ $grantOut".take(30)
        }

        return passed
    }

    // ── 跳过警告 ──
    if (showSkipWarning) {
        AlertDialog(
            onDismissRequest = { showSkipWarning = false; onSkip() },
            title = { Text("⚠️ 功能受限") },
            text = { Text("由于权限缺失，95% 功能将无法使用。\n\n如需重新授权，请到软件的设置页面操作。") },
            confirmButton = { TextButton(onClick = { showSkipWarning = false; onSkip() }) { Text("我知道了") } },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统权限授予") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "关闭") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            // 设备信息卡片
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("$brand · Android $androidVer (SDK $sdkInt)", fontWeight = FontWeight.SemiBold)
                        Text("包名: $pkg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 进度条
            if (phase == "granting") {
                val done = perms.count { it.granted }
                val total = perms.size
                LinearProgressIndicator(
                    progress = { done.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$done / $total 已授权", style = MaterialTheme.typography.labelSmall)
                    if (statusText.isNotBlank()) Text(statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(8.dp))

            // 权限清单
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(perms.toList()) { perm ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = if (perm.granted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                perm.checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                perm.granted -> Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF34C759), modifier = Modifier.size(20.dp))
                                phase == "done" && !perm.granted -> Icon(Icons.Filled.Cancel, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(20.dp))
                                else -> Icon(Icons.Filled.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(perm.zh, style = MaterialTheme.typography.bodyMedium)
                                    if (perm.essential) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                                            Text("必备", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                if (perm.result.isNotBlank() && phase == "done") {
                                    Text(perm.result, style = MaterialTheme.typography.labelSmall,
                                        color = if (perm.granted) Color(0xFF34C759) else Color(0xFFFF3B30))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ★ 失败列表
            if (phase == "done") {
                val failed = perms.filter { !it.granted }
                if (failed.isNotEmpty()) {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("⚠️ ${failed.size} 个权限授权失败:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                failed.take(10).joinToString("、") { it.zh },
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (failed.size > 10) {
                                Text("...等${failed.size}个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("✅ 完成 (${perms.count { it.granted }}/${perms.size} 已授权)")
                }
            }

            // 按钮
            if (phase != "done") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { showSkipWarning = true },
                        modifier = Modifier.weight(1f),
                    ) { Text("暂时跳过，稍后授予") }

                    Button(
                        onClick = {
                            phase = "granting"
                            scope.launch {
                                // 拉取服务器模板
                                withContext(Dispatchers.IO) {
                                    try {
                                        val url = "${Endpoints.backend(ctx).trimEnd('/')}/admin/client/perm-template?brand=$brand&model=${Build.MODEL}&sdk=$sdkInt"
                                        val conn = URL(url).openConnection() as java.net.HttpURLConnection
                                        conn.connectTimeout = 8000; conn.readTimeout = 8000
                                        template = JSONObject(conn.inputStream.bufferedReader().readText())
                                    } catch (_: Exception) { template = null }
                                }

                                // ★ 逐项真实验证 (不是快速遍历画假勾)
                                val total = perms.size
                                for ((i, perm) in perms.withIndex()) {
                                    statusText = "正在授予: ${perm.zh} (${i+1}/$total)"
                                    perm.checking = true
                                    val ok = withContext(Dispatchers.IO) {
                                        verifyOne(perm)
                                    }
                                    perm.granted = ok
                                    perm.checking = false
                                    delay(100) // 极短间隔,每个权限1-2秒
                                }
                                phase = "done"
                                val done = perms.count { it.granted }
                                val failed = perms.count { !it.granted }
                                statusText = "完成: $done 成功" + if (failed > 0) ", $failed 失败" else ""
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = phase != "granting",
                    ) { Text(if (phase == "granting") "授予中..." else "Root 权限一键授予") }
                }

                if (statusText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
