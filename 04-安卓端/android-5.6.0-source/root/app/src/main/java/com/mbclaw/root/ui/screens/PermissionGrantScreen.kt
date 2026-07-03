package com.mbclaw.root.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mbclaw.root.agent.PermissionLabels
import com.mbclaw.root.agent.PermissionTier
import com.mbclaw.root.agent.RootBootstrap
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionGrantScreen(
    ctx: android.content.Context,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pkg = ctx.packageName
    val brand = Build.BRAND
    val androidVer = Build.VERSION.RELEASE
    val sdkInt = Build.VERSION.SDK_INT

    val results = remember { mutableStateListOf<RootBootstrap.PermStatus>() }
    var phase by remember { mutableStateOf("ready") }
    var statusText by remember { mutableStateOf("") }
    var showSkipWarning by remember { mutableStateOf(false) }
    var grantList by remember { mutableStateOf<List<String>>(emptyList()) }
    var skipList by remember { mutableStateOf<List<String>>(emptyList()) }

    // 加载已有结果或服务器模板
    LaunchedEffect(Unit) {
        val existing = RootBootstrap.permResults(ctx)
        if (existing.isNotEmpty()) {
            results.addAll(existing)
            phase = "done"
            val done = results.count { it.granted }
            statusText = "$done 成功, ${existing.size - done} 未授予"
        }
        // 拉取服务器模板
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("http://47.83.2.188:80/admin/client/perm-template?brand=${Build.BRAND}&model=${Build.MODEL}&sdk=${Build.VERSION.SDK_INT}")
                val json = org.json.JSONObject(url.openConnection().getInputStream().bufferedReader().readText())
                val gArr = json.optJSONArray("grant") ?: org.json.JSONArray()
                val sArr = json.optJSONArray("skip") ?: org.json.JSONArray()
                grantList = (0 until gArr.length()).map { gArr.getString(it) }
                skipList = (0 until sArr.length()).map { sArr.getString(it) }
            } catch (_: Exception) {}
        }
    }

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
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "关闭") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
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

            if (phase == "granting") {
                val done = results.count { it.granted }
                val total = results.size.coerceAtLeast(1)
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("$done / $total 已授权", style = MaterialTheme.typography.labelSmall)
                if (statusText.isNotBlank()) Text(statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(8.dp))

            // 权限清单
            var showEssentialOnly by remember { mutableStateOf(false) }
            val essentialPerms = setOf(
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.WRITE_SETTINGS",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
            )
            val displayList = if (showEssentialOnly) results.filter { it.perm in essentialPerms } else results.toList()
            val essentialGranted = results.count { it.granted && it.perm in essentialPerms }
            val essentialTotal = results.count { it.perm in essentialPerms }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showEssentialOnly) "仅必要权限 ($essentialGranted/${essentialTotal})" else "全部权限 (${results.count { it.granted }}/${results.size})",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text(if (showEssentialOnly) "显示全部" else "仅必要", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { showEssentialOnly = !showEssentialOnly })
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(displayList) { perm ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = if (perm.granted) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            // ★ 修复颜色: 成功绿色✓，失败红色✗
                            when {
                                perm.granted -> Icon(Icons.Filled.CheckCircle, null,
                                    tint = Color(0xFF34C759), modifier = Modifier.size(20.dp))
                                phase == "done" && !perm.granted -> Icon(Icons.Filled.Cancel, null,
                                    tint = Color(0xFFFF3B30), modifier = Modifier.size(20.dp))
                                else -> Icon(Icons.Filled.RadioButtonUnchecked, null,
                                    tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(perm.name.ifBlank { perm.perm.substringAfterLast(".") }, style = MaterialTheme.typography.bodyMedium)
                                if (perm.granted && perm.verifyMethod.isNotBlank()) {
                                    Text(perm.verifyMethod, style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF34C759))
                                } else if (!perm.granted && perm.failReason.isNotBlank()) {
                                    Text(perm.failReason, style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFFF3B30), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (phase == "done") {
                val failed = results.filter { !it.granted }
                if (failed.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3F3))) {
                        Column(Modifier.padding(12.dp)) {
                            Text("⚠️ ${failed.size} 个权限授权失败:", fontWeight = FontWeight.SemiBold, color = Color(0xFFFF3B30))
                            Spacer(Modifier.height(4.dp))
                            Text(failed.take(10).joinToString("、") { it.name }, style = MaterialTheme.typography.labelSmall)
                            if (failed.size > 10) Text("...等${failed.size}个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ★ 重试按钮
                if (failed.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        RootBootstrap.resetAndRerun(ctx)
                        phase = "granting"
                        // 轮询等待完成
                        scope.launch {
                            var waited = 0
                            while (waited < 60) {  // 最多等60秒
                                kotlinx.coroutines.delay(2000); waited += 2
                                val fresh = RootBootstrap.permResults(ctx)
                                if (fresh.isNotEmpty() && ctx.getSharedPreferences("mbclaw_root_setup", 0).getBoolean("setup_done_v5", false)) {
                                    results.clear(); results.addAll(fresh)
                                    val d = results.count { it.granted }
                                    statusText = "$d 成功, ${fresh.size - d} 未授予"
                                    phase = "done"
                                    break
                                }
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("🔄 重新授权") }
                    Spacer(Modifier.height(8.dp))
                }

                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("✅ 完成 (${results.count { it.granted }}/${results.size} 已授权)")
                }
            }

            if (phase != "done") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showSkipWarning = true }, modifier = Modifier.weight(1f)) { Text("跳过") }
                    Button(onClick = {
                        phase = "granting"
                        RootBootstrap.resetAndRerun(ctx)
                        scope.launch {
                            statusText = "正在授予权限..."
                            // ★ 使用服务器模板：只尝试 grant 列表中的权限
                            val tier = PermissionTier.get(ctx)
                            if (tier.hasRoot && grantList.isNotEmpty()) {
                                var granted = 0
                                val total = grantList.size
                                grantList.forEachIndexed { i, perm ->
                                    statusText = "正在授予: ${i+1}/$total"
                                    kotlinx.coroutines.delay(200)
                                    val ok = tier.shellRoot("pm grant --user 0 $pkg $perm 2>&1 && pm check-permission $perm $pkg 2>&1 | grep -q granted && echo OK || echo FAIL", timeoutMs = 8000)
                                    if (ok?.contains("OK") == true) granted++
                                }
                                // 保存结果
                                val allPerms = grantList.map { perm ->
                                    val name = PermissionLabels.get(perm).zh
                                    val result = tier.shellRoot("pm check-permission $perm $pkg 2>&1", timeoutMs = 5000)
                                    RootBootstrap.PermStatus(perm, name, result?.contains("granted") == true, if (result?.contains("granted") == true) "✓" else "✗")
                                }
                                // 写入SharedPreferences
                                val json = org.json.JSONArray()
                                allPerms.forEach { p ->
                                    json.put(org.json.JSONObject().apply {
                                        put("perm", p.perm); put("name", p.name)
                                        put("granted", p.granted); put("method", p.verifyMethod)
                                        put("fail", if (p.granted) "" else "未授权")
                                    })
                                }
                                ctx.getSharedPreferences("mbclaw_root_setup", 0).edit()
                                    .putString("perm_results", json.toString())
                                    .putBoolean("setup_done_v5", true)
                                    .apply()
                                results.clear(); results.addAll(allPerms)
                                statusText = "$granted 成功, ${total - granted} 未授予"
                            } else {
                                // 没有服务器模板，用RootBootstrap默认方法
                                RootBootstrap.setupAsync(ctx)
                                var rounds = 0
                                while (rounds < 60) {
                                    kotlinx.coroutines.delay(1000); rounds++
                                    val fresh = RootBootstrap.permResults(ctx)
                                    if (fresh.isNotEmpty()) {
                                        results.clear(); results.addAll(fresh)
                                        val d = results.count { it.granted }
                                        if (d > 0) { statusText = "$d/${fresh.size} 已授权" }
                                    }
                                    if (ctx.getSharedPreferences("mbclaw_root_setup", 0).getBoolean("setup_done_v5", false)) break
                                }
                            }
                            phase = "done"
                            val d = results.count { it.granted }
                            statusText = "$d 成功, ${results.size - d} 未授予"
                        }
                    }, modifier = Modifier.weight(1f), enabled = phase != "granting") {
                        Text(if (phase == "granting") "授予中..." else "Root 权限一键授予")
                    }
                }
                if (statusText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(statusText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
