package com.mbclaw.nonroot.ui.screens

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.mbclaw.nonroot.agent.AgentLoop
import com.mbclaw.nonroot.agent.MBclawAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ChatViewModel — 进程级单例
 *
 * bug.5 真修：
 *  • 进程级单例，不依赖 Composable remember
 *  • 进程被杀重启 → 单例重建 → initIfNeeded() 从 DB 自动加载最后会话
 *  • 必须用 Companion.get() 获取，禁止 new
 */
class ChatViewModel private constructor(private val ctx: Context, val agent: MBclawAgent) {

    val messages = mutableStateListOf<ChatMsg>()
    val inputText = mutableStateOf("")
    val isThinking = mutableStateOf(false)
    val agentStatus = mutableStateOf("")
    val sessionId = mutableStateOf("")
    val tokenStats = mutableStateOf(TokenStats())
    val currentAssistantId = mutableStateOf(
        ctx.getSharedPreferences("mb_assistant", Context.MODE_PRIVATE).getString("id", "default") ?: "default"
    )

    fun switchAssistant(id: String) {
        currentAssistantId.value = id
        ctx.getSharedPreferences("mb_assistant", Context.MODE_PRIVATE)
            .edit().putString("id", id).apply()
        val a = com.mbclaw.nonroot.data.AssistantCatalog.byId(id)
        messages.add(ChatMsg("assistant", "🦊 已切换至「${a.name}」: ${a.systemPrompt.take(50)}"))
    }

    private val agentLoop = AgentLoop(ctx, agent.db, agent.settings)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var initialized = false

    companion object {
        @Volatile private var inst: ChatViewModel? = null
        fun get(ctx: Context, agent: MBclawAgent): ChatViewModel =
            inst ?: synchronized(this) {
                inst ?: ChatViewModel(ctx.applicationContext, agent).also { inst = it }
            }
    }

    /** 强制重新从 DB 加载 — onResume 时调 (修 bug5: 划后台回来对话丢) */
    fun forceReload() {
        scope.launch(Dispatchers.IO) {
            val sessions = try { agent.db.getSessions() } catch (_: Exception) { emptyList() }
            if (sessions.isEmpty()) return@launch
            val newest = sessions.first()
            val rows = agent.db.getMessages(newest.id)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (rows.size > messages.count { it.role != "system" }) {
                    // DB 比内存多, 重新加载
                    sessionId.value = newest.id
                    messages.clear()
                    rows.forEach { messages.add(ChatMsg(it.role, it.content)) }
                    if (messages.isEmpty()) messages.add(welcomeMsg())
                    android.util.Log.i("MBclaw-VM", "forceReload: ${rows.size} msgs from DB")
                }
            }
        }
    }

    fun initIfNeeded() {
        if (initialized && messages.isNotEmpty()) return
        synchronized(this) {
            initialized = true
        }
        scope.launch(Dispatchers.IO) {
            agent.initSession()
            val sessions = try { agent.db.getSessions() } catch (_: Exception) { emptyList() }
            android.util.Log.i("MBclaw-VM", "initIfNeeded: ${sessions.size} sessions in DB")
            if (sessions.isNotEmpty()) {
                // 用 getSessions() 替代 getLastSessionId()，后者可能返回 null 即使 DB 有数据
                val newest = sessions.first()  // getSessions 按 updated_at DESC
                val rows = agent.db.getMessages(newest.id)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    sessionId.value = newest.id
                    messages.clear()
                    rows.forEach { messages.add(ChatMsg(it.role, it.content)) }
                    if (messages.isEmpty()) messages.add(welcomeMsg())
                    android.util.Log.i("MBclaw-VM", "已恢复 session=${newest.id}, ${rows.size} messages")
                }
            } else {
                val sid = agent.db.createSession("新对话")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    sessionId.value = sid
                    messages.clear()
                    messages.add(welcomeMsg())
                    android.util.Log.i("MBclaw-VM", "首次启动, 新建 session=$sid")
                }
            }
        }
    }

    /** 切到某个历史会话 */
    fun openSession(sid: String) {
        sessionId.value = sid
        scope.launch(Dispatchers.IO) {
            val rows = agent.db.getMessages(sid)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                messages.clear()
                rows.forEach { messages.add(ChatMsg(it.role, it.content)) }
            }
        }
    }

    /** 新开会话 */
    fun newSession() {
        scope.launch(Dispatchers.IO) {
            val sid = agent.db.createSession("新对话")
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                sessionId.value = sid
                messages.clear()
                messages.add(welcomeMsg())
            }
        }
    }

    /** 删除某个会话（bug.7） */
    fun deleteSession(sid: String, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            try {
                agent.db.writableDatabase.execSQL("DELETE FROM messages WHERE session_id=?", arrayOf(sid))
                agent.db.writableDatabase.execSQL("DELETE FROM sessions WHERE id=?", arrayOf(sid))
                if (sessionId.value == sid) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        sessionId.value = ""
                        messages.clear()
                        initialized = false
                        initIfNeeded()
                    }
                }
            } catch (_: Exception) {}
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** 清空当前会话的对话内容（bug.7） */
    fun clearCurrentMessages() {
        val sid = sessionId.value
        if (sid.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                agent.db.writableDatabase.execSQL("DELETE FROM messages WHERE session_id=?", arrayOf(sid))
            } catch (_: Exception) {}
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                messages.clear()
                messages.add(welcomeMsg())
            }
        }
    }

    fun send() {
        if (inputText.value.isBlank() || isThinking.value) return
        val text = inputText.value
        inputText.value = ""
        messages.add(ChatMsg("user", text))

        // ★ bug5 修: 立即存盘, 不等 agent 完成才存
        val sid = sessionId.value
        scope.launch(Dispatchers.IO) {
            try {
                agent.db.saveMessage(sid, "user", text)
                android.util.Log.i("MBclaw-VM", "user message persisted to DB: ${text.take(20)}")
            } catch (e: Exception) {
                android.util.Log.e("MBclaw-VM", "save user msg failed: ${e.message}")
            }
        }

        isThinking.value = true
        agentStatus.value = "🤖 启动中…"

        com.mbclaw.nonroot.service.AgentFloatingService.start(ctx, "运行")
        registerCancelReceiver()

        scope.launch {
            try {
                val reply = agentLoop.run(text, sessionId.value, maxTurns = 20) { status ->
                    agentStatus.value = status
                    // 更新悬浮窗 + 通知
                    com.mbclaw.nonroot.service.AgentFloatingService.update(
                        ctx,
                        status.ifBlank { "思考中" },
                        agentLoop.currentTool
                    )
                }
                messages.add(ChatMsg("assistant", reply))
                // ★ bug5 修: assistant 回复也立即存盘
                scope.launch(Dispatchers.IO) {
                    try {
                        agent.db.saveMessage(sid, "assistant", reply)
                    } catch (_: Exception) {}
                }
                val inTokens = (text.length / 1.5).toInt()
                val outTokens = (reply.length / 1.5).toInt()
                tokenStats.value = tokenStats.value.copy(
                    sessionTokensIn = tokenStats.value.sessionTokensIn + inTokens,
                    sessionTokensOut = tokenStats.value.sessionTokensOut + outTokens,
                    lastTurnIn = inTokens,
                    lastTurnOut = outTokens,
                )
            } catch (e: Exception) {
                messages.add(ChatMsg("assistant", "❌ ${e.message}", isError = true))
                scope.launch(Dispatchers.IO) {
                    try { agent.db.saveMessage(sid, "assistant", "❌ ${e.message}") } catch (_: Exception) {}
                }
            }
            isThinking.value = false
            agentStatus.value = ""
            com.mbclaw.nonroot.service.AgentFloatingService.stop(ctx)
        }
    }

    fun cancel() {
        agentLoop.cancel()
        agentStatus.value = "⏹ 终止中…"
        com.mbclaw.nonroot.service.AgentFloatingService.stop(ctx)
    }

    // 接收悬浮窗/通知的"取消"广播
    private var cancelReceiver: android.content.BroadcastReceiver? = null
    private fun registerCancelReceiver() {
        if (cancelReceiver != null) return
        cancelReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                cancel()
            }
        }
        try {
            val filter = android.content.IntentFilter("com.mbclaw.action.USER_CANCEL_AGENT")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(cancelReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(cancelReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun welcomeMsg(): ChatMsg {
        val cfg = agent.settings.isConfigured()
        return ChatMsg("assistant",
            "🌟 MBclaw Root v3.6\n" +
            "当前模型: ${agent.settings.modelName.ifBlank { "(未配置)" }}\n" +
            (if (!cfg) "⚠️ 请先在「我的」中配置 API 提供商\n" else "") +
            "\n🛠 84 个工具就绪 | 🧠 长期记忆 | ✊ Root 通道\n" +
            "试试: 「打开飞行模式」、「截图」、「列出 WiFi」")
    }
}

data class TokenStats(
    val sessionTokensIn: Long = 0,
    val sessionTokensOut: Long = 0,
    val lastTurnIn: Int = 0,
    val lastTurnOut: Int = 0,
)
