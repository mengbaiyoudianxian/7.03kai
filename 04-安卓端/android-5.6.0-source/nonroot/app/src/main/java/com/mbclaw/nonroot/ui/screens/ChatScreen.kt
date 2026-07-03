package com.mbclaw.nonroot.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class ChatMsg(val role: String, val content: String, val isError: Boolean = false)

/**
 * 聊天屏 — 仿 MiClaw 风格
 *  • 用户气泡: 浅蓝 (secondaryContainer)
 *  • AI 气泡: 浅灰 (surfaceVariant) + 时间戳 + 复制/分享按钮
 *  • 输入栏: + ... 🎤
 */
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { vm.initIfNeeded() }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(0)
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

        // 底部输入栏 — 仿 MiClaw
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .imePadding()                       // ★ 输入法弹出时跟着上浮
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 1.dp,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // 左 +
                    IconButton(
                        onClick = {
                            // TODO: 弹出快捷工具菜单
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Filled.Add, "工具", tint = MaterialTheme.colorScheme.onSurface,
                             modifier = Modifier.size(24.dp))
                    }
                    // 中 输入框 (无边框)
                    BasicInputField(
                        value = vm.inputText.value,
                        onValueChange = { vm.inputText.value = it },
                        enabled = !vm.isThinking.value,
                        placeholder = if (vm.isThinking.value) "Agent 运行中，可点右侧停止"
                                      else "发消息或按住说话",
                        onSend = { vm.send() },
                        modifier = Modifier.weight(1f),
                    )
                    // 右 麦克风 / 发送 / 终止
                    when {
                        vm.isThinking.value -> {
                            IconButton(
                                onClick = { vm.cancel() },
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(Icons.Filled.Stop, "终止",
                                     tint = MaterialTheme.colorScheme.error,
                                     modifier = Modifier.size(24.dp))
                            }
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
            Text(
                msg.content,
                modifier = Modifier.padding(14.dp, 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (msg.isError) MaterialTheme.colorScheme.onErrorContainer
                        else if (isUser) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
