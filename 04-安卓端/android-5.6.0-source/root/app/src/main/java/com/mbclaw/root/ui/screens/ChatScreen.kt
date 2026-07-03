package com.mbclaw.root.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream

data class ChatMsg(val role: String, val content: String, val isError: Boolean = false, val attachment: String = "")

/**
 * 聊天屏 — 仿 MiClaw 风格
 *  • 用户气泡: 浅蓝 (secondaryContainer)
 *  • AI 气泡: 浅灰 (surfaceVariant) + 时间戳 + 复制/分享按钮
 *  • 输入栏: + ... 🎤
 */
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { vm.initIfNeeded() }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // 文件选择器
    var pendingAttachment by remember { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // 复制到缓存目录
            val cacheDir = ctx.cacheDir
            val fileName = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
            val cacheFile = File(cacheDir, fileName)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
                pendingAttachment = cacheFile.absolutePath
                // 把文件路径加入消息发送
                val attachPath = pendingAttachment
                vm.inputText.value = "[文件: $attachPath] ${vm.inputText.value}"
                pendingAttachment = ""
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "文件读取失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部固定状态条
        if (vm.isThinking.value) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(vm.agentStatus.value.ifBlank { "思考中…" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.weight(1f))
                    if (vm.tokenStats.value.lastTurnIn > 0) {
                        Text("↑${vm.tokenStats.value.lastTurnIn} ↓${vm.tokenStats.value.lastTurnOut} tok",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            state = listState,
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            items(vm.messages.reversed()) { msg -> ChatBubble(msg) }
        }

        // 底部输入栏 — ChatGPT 风格紧凑
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.imePadding().navigationBarsPadding(),
        ) {
            Surface(
                color = if (MaterialTheme.colorScheme.background == Color(0xFFFFFFFF)) Color(0xFFF3F4F6) else Color(0xFF1F1F1F),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // 左 + 上传
                    IconButton(onClick = { filePicker.launch(arrayOf("image/*","application/*","video/*","audio/*","text/*","*/*")) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.AttachFile, "上传", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
                    }
                    // 中 输入
                    BasicInputField(
                        value = vm.inputText.value, onValueChange = { vm.inputText.value = it },
                        enabled = !vm.isThinking.value,
                        placeholder = if (vm.isThinking.value) "Agent 运行中" else "发消息",
                        onSend = { vm.send() }, modifier = Modifier.weight(1f),
                    )
                    // 右
                    when {
                        vm.isThinking.value -> IconButton(onClick = { vm.cancel() }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Stop, "终止", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        }
                        vm.inputText.value.isNotBlank() -> {
                            FilledIconButton(
                                onClick = { vm.send() },
                                modifier = Modifier.size(40.dp).padding(2.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                            ) {
                                Icon(Icons.Filled.ArrowUpward, "发送",
                                     tint = androidx.compose.ui.graphics.Color.White,
                                     modifier = Modifier.size(20.dp))
                            }
                        }
                        else -> {
                            IconButton(
                                onClick = {
                                    // TODO: 语音
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(Icons.Filled.Mic, "语音",
                                     tint = MaterialTheme.colorScheme.onSurface,
                                     modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BasicInputField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        maxLines = 4,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder,
                     style = MaterialTheme.typography.bodyLarge,
                     color = MaterialTheme.colorScheme.outline)
            }
            inner()
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(msg: ChatMsg) {
    val isUser = msg.role == "user"
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun copy() {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("MBclaw", msg.content))
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun share() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, msg.content)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(send, "分享"))
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { copy() },
                ),
            color = when {
                msg.isError -> MaterialTheme.colorScheme.errorContainer
                isUser -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
        ) {
            Column(Modifier.padding(12.dp, 8.dp)) {
                val parts = msg.content.split("```")
                parts.forEachIndexed { i, part ->
                    if (i % 2 == 1) {
                        // 代码块
                        val code = part.replaceAfter("\n", "").let { if (it.isBlank()) part else part.substringAfter("\n") }
                        val lang = part.substringBefore("\n").trim().ifBlank { null }
                        Surface(
                            color = if (MaterialTheme.colorScheme.background == Color(0xFFFFFFFF)) Color(0xFF1A1A1A) else Color(0xFF0A0A0A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column {
                                if (lang != null) Text(lang, Modifier.padding(start = 12.dp, top = 8.dp), fontSize = 11.sp, color = Color(0xFF8B949E))
                                Text(code.trim(), Modifier.padding(12.dp).fillMaxWidth(), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFE6EDF3))
                            }
                        }
                    } else if (part.isNotBlank()) {
                        Text(part, style = MaterialTheme.typography.bodyLarge,
                            color = if (msg.isError) MaterialTheme.colorScheme.onErrorContainer
                                    else if (isUser) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // AI 消息下方有操作按钮（仿 MiClaw）
        if (!isUser) {
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { copy() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ContentCopy, "复制",
                         modifier = Modifier.size(16.dp),
                         tint = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { share() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Share, "分享",
                         modifier = Modifier.size(16.dp),
                         tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
