package com.mbclaw.dev.hermes

import android.content.Context
import com.mbclaw.dev.data.LocalDB
import com.mbclaw.dev.data.UserSettings
import kotlinx.coroutines.*

/**
 * Hermes 记忆系统 — 主编排器
 *
 * 整合项目一～六全部记忆功能:
 *   P1: TranscriptLogger — 详细日志备份
 *   P2: ClassificationEngine — 树状分类+关键词索引
 *   P3: SnapshotService — 突破时备份
 *   P6: LayeredSearch + SessionBootstrap — 实时记忆预调用
 *
 * 工作流:
 *   每轮对话前 → SessionBootstrap 预搜索记忆 → 注入上下文
 *   对话结束后 → 自动分类 + 更新关键词索引
 *   闲置时 → 整理记忆 + 归档旧数据
 *   突破时 → 自动快照
 */

class HermesMemory(private val context: Context, private val db: LocalDB, private val settings: UserSettings) {

    // 子模块
    val transcriptLogger = TranscriptLogger(context)
    val layeredSearch = LayeredSearch(db, transcriptLogger)
    val classificationEngine = ClassificationEngine(context, db, transcriptLogger, layeredSearch)
    val snapshotService = SnapshotService(context)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 当前会话的滚动消息缓存 (用于结束后分类)
    private val sessionBuffer = mutableMapOf<String, MutableList<Pair<String, String>>>()

    // ── 项目六: 会话引导 (Session Bootstrap) ──

    /**
     * 每轮对话前调用 — 预搜索相关记忆并注入上下文
     *
     * @return 格式化的记忆上下文文本 (可直接注入到 system prompt)
     */
    suspend fun bootstrapSession(sessionId: String, currentMessage: String): String {
        val results = layeredSearch.search(LayeredSearch.SearchContext(
            query = currentMessage,
            maxResults = 5,
            enableL3 = settings.utopiaEnabled, // 开启乌托邦才用L3(避免额外API费用)
            embeddingApiBaseUrl = settings.apiBaseUrl,
            embeddingApiKey = settings.apiKey,
        ))
        return layeredSearch.formatForInjection(results)
    }

    /**
     * 每轮对话后调用 — 记录+分类
     */
    suspend fun afterTurn(sessionId: String, userMessage: String, assistantReply: String) {
        // 缓冲消息
        sessionBuffer.getOrPut(sessionId) { mutableListOf() }.apply {
            add("user" to userMessage)
            add("assistant" to assistantReply)
        }

        // 记录 transcript (P1)
        transcriptLogger.log(TranscriptLogger.TranscriptEntry(
            sessionId = sessionId, type = "message", role = "user", content = userMessage,
        ))
        transcriptLogger.log(TranscriptLogger.TranscriptEntry(
            sessionId = sessionId, type = "message", role = "assistant", content = assistantReply,
        ))

        // 突破检测 (P3)
        snapshotService.checkBreakthrough(sessionId, sessionBuffer[sessionId] ?: emptyList())
    }

    /**
     * 会话结束后调用 — 分类+整理
     */
    fun onSessionEnd(sessionId: String) {
        val messages = sessionBuffer.remove(sessionId) ?: return
        scope.launch {
            // 树状分类 (P2)
            classificationEngine.classify(sessionId, messages)

            // 归档旧数据
            transcriptLogger.archiveOld(30)
            snapshotService.cleanupOld(10)
        }
    }

    /**
     * 闲置时调用 — 整理记忆
     */
    fun idleMaintenance() {
        scope.launch {
            // 清理30天前的旧transcript
            transcriptLogger.archiveOld(30)
            // 清理旧快照
            snapshotService.cleanupOld(10)
        }
    }

    // ── 查询接口 ──

    /** 搜索记忆 (给UI使用) */
    suspend fun searchMemory(query: String, enableL3: Boolean = false): List<LayeredSearch.SearchResult> {
        return layeredSearch.search(LayeredSearch.SearchContext(
            query = query, maxResults = 10, enableL3 = enableL3,
            embeddingApiBaseUrl = settings.apiBaseUrl, embeddingApiKey = settings.apiKey,
        ))
    }

    /** 搜索 Transcript (给UI使用) */
    fun searchTranscripts(query: String, limit: Int = 20) =
        transcriptLogger.searchTranscripts(query, limit)

    /** 获取分类树 */
    fun getClassificationTree() = classificationEngine.getTree()

    /** 获取失败方案 */
    fun getFailedApproaches() = classificationEngine.getFailedApproaches()

    /** 获取快照列表 */
    fun getSnapshots() = snapshotService.getSnapshots()

    /** 按关键词查找 session */
    fun findSessionsByKeywords(keywords: List<String>) = classificationEngine.findByKeywords(keywords)

    // ── 统计 ──

    fun getStats(): Map<String, Any> {
        val txStats = transcriptLogger.getStats()
        return mapOf(
            "transcript_entries" to txStats.totalEntries,
            "transcript_size_mb" to "%.1f".format(txStats.totalSizeBytes / 1_000_000f),
            "snapshot_count" to snapshotService.getSnapshots().size,
            "tree_root_nodes" to classificationEngine.getTree().size,
            "failed_approaches" to classificationEngine.getFailedApproaches().size,
        )
    }
}
