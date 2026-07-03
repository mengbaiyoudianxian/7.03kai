package com.mbclaw.root.ui.screens

import android.content.Context
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ── 数据模型 ──

data class ReportItem(
    val id: String, val title: String, val content: String,
    val votes: Int, val ip: String, val ts: Long, val status: String,
    val voted: Boolean = false
)

// ── 网络工具 ──

private fun apiGet(ctx: Context, path: String): String {
    val backend = com.mbclaw.root.data.Endpoints.backend(ctx)
    val url = URL("${backend.trimEnd('/')}$path")
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = 10000; conn.readTimeout = 10000
    return conn.inputStream.bufferedReader().readText()
}

private fun apiPost(ctx: Context, path: String, body: String): String {
    val backend = com.mbclaw.root.data.Endpoints.backend(ctx)
    val url = URL("${backend.trimEnd('/')}$path")
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = 10000; conn.readTimeout = 10000
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.doOutput = true
    OutputStreamWriter(conn.outputStream).use { it.write(body) }
    return conn.inputStream.bufferedReader().readText()
}

private fun fetchNotices(ctx: Context): List<NoticeItem> {
    return try {
        val raw = apiGet(ctx, "/admin/client/notices")
        val arr = org.json.JSONObject(raw).optJSONArray("unread") ?: org.json.JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NoticeItem(o.getString("id"), o.getString("title"), o.getString("content"), o.getLong("ts"))
        }
    } catch (e: Exception) { emptyList() }
}

data class NoticeItem(val id: String, val title: String, val content: String, val ts: Long)

// ── 公告弹窗 ──

@Composable
fun NoticeDialog(ctx: Context, onDismiss: () -> Unit) {
    var notices by remember { mutableStateOf<List<NoticeItem>>(emptyList()) }
    var current by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { notices = withContext(Dispatchers.IO) { fetchNotices(ctx) } }
    if (notices.isEmpty()) { onDismiss(); return }

    val n = notices[current]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📢 ${n.title}", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(n.content, style = MaterialTheme.typography.bodyMedium, lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp))
                Spacer(Modifier.height(8.dp))
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(n.ts * 1000)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                if (notices.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text("${current + 1}/${notices.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        confirmButton = {
            if (current < notices.size - 1) {
                TextButton(onClick = { current++ }) { Text("下一条 ›") }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = if (current > 0) {
            { TextButton(onClick = { current-- }) { Text("‹ 上一条") } }
        } else null
    )
}

// ── 社区主屏幕 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var bugs by remember { mutableStateOf<List<ReportItem>>(emptyList()) }
    var features by remember { mutableStateOf<List<ReportItem>>(emptyList()) }
    var showNewBug by remember { mutableStateOf(false) }
    var showNewFeature by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) {
                try {
                    val b = org.json.JSONObject(apiGet(ctx, "/admin/api/bugs")).optJSONArray("bugs") ?: org.json.JSONArray()
                    bugs = (0 until b.length()).map { i -> val o=b.getJSONObject(i); ReportItem(o.getString("id"),o.getString("title"),o.getString("content"),o.getInt("votes"),o.optString("ip",""),o.getLong("ts"),o.optString("status","open")) }
                    val f = org.json.JSONObject(apiGet(ctx, "/admin/api/features")).optJSONArray("features") ?: org.json.JSONArray()
                    features = (0 until f.length()).map { i -> val o=f.getJSONObject(i); ReportItem(o.getString("id"),o.getString("title"),o.getString("content"),o.getInt("votes"),o.optString("ip",""),o.getLong("ts"),o.optString("status","pending")) }
                } catch (_: Exception) {}
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("社区") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { if (tab == 0) showNewBug = true else showNewFeature = true }) {
                        Icon(Icons.Filled.Add, "新建")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 标签栏
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("🐛 Bug反馈 (${bugs.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("💡 共建计划 (${features.size})") })
            }

            if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else {
                val list = if (tab == 0) bugs else features
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list) { item -> ReportCard(item, tab == 0) { reload() } }
                }
            }
        }
    }

    if (showNewBug) NewReportDialog("Bug反馈", "bug") { showNewBug = false; reload() }
    if (showNewFeature) NewReportDialog("共建计划", "feature") { showNewFeature = false; reload() }
}

@Composable
fun ReportCard(item: ReportItem, isBug: Boolean, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                // 投票按钮
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (item.voted) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                    border = if (item.voted) ButtonDefaults.outlinedButtonBorder else null
                ) {
                    Row(Modifier.clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try { apiPost(ctx, "/admin/api/${if (isBug) "bugs" else "features"}/${item.id}/vote", "{}") } catch (_: Exception) {}
                            }
                            onRefresh()
                        }
                    }.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("👍", fontSize = 14.sp)
                        Spacer(Modifier.width(4.dp))
                        Text("${item.votes}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp))
            Spacer(Modifier.height(8.dp))
            Row {
                Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.ts * 1000)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(12.dp))
                Surface(shape = RoundedCornerShape(4.dp), color = when(item.status) { "open" -> Color(0xFF238636) else -> Color(0xFF8B949E) }.copy(alpha = 0.15f)) {
                    Text(item.status, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = if (item.status == "open") Color(0xFF3FB950) else Color(0xFF8B949E))
                }
            }
        }
    }
}

@Composable
fun NewReportDialog(typeLabel: String, apiType: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("新建$typeLabel", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("标题 (30字以内)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(value = title, onValueChange = { if (it.length <= 30) title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("一句话描述") })
                Spacer(Modifier.height(8.dp))
                Text("正文 (500字以内, ${content.length}/500)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(value = content, onValueChange = { if (it.length <= 500) content = it }, modifier = Modifier.fillMaxWidth().height(120.dp), placeholder = { Text("详细描述...") })
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isBlank() || content.isBlank()) { error = "标题和正文不能为空"; return@Button }
                submitting = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val resp = apiPost(ctx, "/admin/api/${apiType}s", """{"title":"${title.replace("\"","\\\"")}","content":"${content.replace("\"","\\\"")}"}""")
                            if (resp.contains("429")) error = "20分钟内只能提交一次"
                            else onDone()
                        } catch (e: Exception) { error = "提交失败: ${e.message}" }
                    }
                    submitting = false
                }
            }, enabled = !submitting) { Text(if (submitting) "提交中..." else "提交") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("取消") } }
    )
}

