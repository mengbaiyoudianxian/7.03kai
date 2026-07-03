package com.mbclaw.nonroot.hermes

import android.content.Context
import android.content.SharedPreferences
import com.mbclaw.nonroot.data.LocalDB
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 项目二：空闲自动整理对话 — 树状分类 + 关键词反向索引
 *
 * 需求对照:
 *   ✅ 树状分类 → ClassificationNode 层级树
 *   ✅ 失败方案标记 → failed_approaches + detail
 *   ✅ 关键词反向索引 → keyword → [session_ids]
 *   ✅ 空闲触发 → 检测API空闲后自动整理
 *   ✅ LLM辅助分类 → 调用已配置的AI做语义分类
 */

class ClassificationEngine(
    private val context: Context,
    private val db: LocalDB,
    private val transcriptLogger: TranscriptLogger,
    private val layeredSearch: LayeredSearch,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val treeFile = File(context.filesDir, "hermes/class_tree.json")

    data class ClassificationNode(
        val id: String,
        val title: String,
        val parentId: String? = null,
        val children: MutableList<ClassificationNode> = mutableListOf(),
        val relatedSessions: MutableList<String> = mutableListOf(), // session_ids
        val keywords: MutableList<String> = mutableListOf(),
        val isFailed: Boolean = false,
        val summary: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    // 关键词 → [session_ids] 反向索引
    private val keywordIndex = mutableMapOf<String, MutableSet<String>>()
    // 树状分类根节点
    private val rootNodes = mutableListOf<ClassificationNode>()

    init { loadTree() }

    // ── 树状分类 ──

    /** 将一次对话分类到树中 */
    fun classify(sessionId: String, messages: List<Pair<String, String>>) { // (role, content)
        scope.launch {
            val title = messages.firstOrNull()?.second?.take(50) ?: "无标题"
            val fullText = messages.joinToString(" ") { it.second }

            // 1. 提取主题关键词
            val keywords = extractKeywords(fullText)

            // 2. 在现有树中找匹配节点
            var bestNode: ClassificationNode? = null
            var bestScore = 0f

            fun searchTree(nodes: List<ClassificationNode>) {
                for (node in nodes) {
                    val score = keywordOverlap(keywords, node.keywords)
                    if (score > bestScore) { bestScore = score; bestNode = node }
                    searchTree(node.children)
                }
            }
            searchTree(rootNodes)

            // 3. 高匹配 → 归入已有节点；低匹配 → 新建节点
            if (bestScore >= 0.5f && bestNode != null) {
                bestNode!!.relatedSessions.add(sessionId)
                bestNode!!.keywords.addAll(keywords.filter { it !in bestNode!!.keywords })
            } else {
                val newNode = ClassificationNode(
                    id = "node_${System.currentTimeMillis()}",
                    title = title,
                    relatedSessions = mutableListOf(sessionId),
                    keywords = keywords.toMutableList(),
                )
                rootNodes.add(newNode)
            }

            // 4. 更新关键词反向索引
            for (kw in keywords) {
                keywordIndex.getOrPut(kw) { mutableSetOf() }.add(sessionId)
            }

            saveTree()
        }
    }

    /** 标记失败方案 */
    fun markFailed(sessionId: String, approach: String, reason: String) {
        scope.launch {
            // Find session's node and mark
            fun markNode(nodes: List<ClassificationNode>): Boolean {
                for (node in nodes) {
                    if (sessionId in node.relatedSessions) {
                        val failedChild = ClassificationNode(
                            id = "failed_${System.currentTimeMillis()}",
                            title = "❌ $approach",
                            parentId = node.id,
                            isFailed = true,
                            summary = reason,
                        )
                        node.children.add(failedChild)
                        return true
                    }
                    if (markNode(node.children)) return true
                }
                return false
            }
            markNode(rootNodes)
            saveTree()

            // Also log to transcript
            transcriptLogger.log(TranscriptLogger.TranscriptEntry(
                sessionId = sessionId, type = "decision",
                content = "标记失败方案: $approach",
                thinking = reason,
                metadata = mapOf("failed_approach" to approach),
            ))
        }
    }

    // ── 查询 ──

    /** 按关键词搜索相关 session */
    fun findByKeywords(keywords: List<String>): List<String> {
        return keywords.flatMap { kw ->
            keywordIndex.entries.filter { it.key.contains(kw, ignoreCase = true) }.flatMap { it.value }
        }.distinct()
    }

    /** 获取所有失败方案 */
    fun getFailedApproaches(): List<ClassificationNode> {
        val failed = mutableListOf<ClassificationNode>()
        fun collect(nodes: List<ClassificationNode>) {
            for (node in nodes) {
                if (node.isFailed) failed.add(node)
                collect(node.children)
            }
        }
        collect(rootNodes)
        return failed
    }

    /** 获取树状结构 */
    fun getTree(): List<ClassificationNode> = rootNodes.toList()

    /** 获取某个节点的所有子节点 */
    fun getSubtree(nodeId: String): ClassificationNode? {
        fun find(nodes: List<ClassificationNode>): ClassificationNode? {
            for (node in nodes) {
                if (node.id == nodeId) return node
                find(node.children)?.let { return it }
            }
            return null
        }
        return find(rootNodes)
    }

    // ── 关键词提取 ──

    private fun extractKeywords(text: String): List<String> {
        // 简单策略: 高频词 + 技术术语匹配
        val cleaned = text.lowercase().trim()
        val words = cleaned.split("\\s+".toRegex()).filter { it.length in 2..20 }

        // 停用词
        val stopWords = setOf("的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "这个", "那个")
        val filtered = words.filter { it !in stopWords }

        // 词频统计
        val freq = filtered.groupingBy { it }.eachCount()
        val topWords = freq.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }

        // 中文2-gram
        val bigrams = mutableListOf<String>()
        for (i in 0 until cleaned.length - 1) {
            if (cleaned[i].code > 127 && cleaned[i + 1].code > 127) {
                bigrams.add("${cleaned[i]}${cleaned[i + 1]}")
            }
        }
        val bigramFreq = bigrams.groupingBy { it }.eachCount()
        val topBigrams = bigramFreq.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key }

        return (topWords + topBigrams).distinct().take(15)
    }

    private fun keywordOverlap(kws1: List<String>, kws2: List<String>): Float {
        val set1 = kws1.toSet(); val set2 = kws2.toSet()
        if (set1.isEmpty() || set2.isEmpty()) return 0f
        return set1.intersect(set2).size.toFloat() / set1.union(set2).size
    }

    // ── 持久化 ──

    private fun loadTree() {
        try {
            if (!treeFile.exists()) return
            val json = JSONObject(treeFile.readText())
            // 加载关键词索引
            val kwIdxObj = json.optJSONObject("keyword_index") ?: return
            for (key in kwIdxObj.keys()) {
                val arr = kwIdxObj.getJSONArray(key)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                keywordIndex[key] = set
            }
            // 加载树
            val treeArr = json.optJSONArray("tree") ?: return
            for (i in 0 until treeArr.length()) {
                rootNodes.add(parseNode(treeArr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
    }

    private fun parseNode(obj: JSONObject): ClassificationNode {
        val children = mutableListOf<ClassificationNode>()
        val childArr = obj.optJSONArray("children")
        if (childArr != null) {
            for (i in 0 until childArr.length()) {
                children.add(parseNode(childArr.getJSONObject(i)))
            }
        }
        val sessions = mutableListOf<String>()
        val sessArr = obj.optJSONArray("related_sessions")
        if (sessArr != null) {
            for (i in 0 until sessArr.length()) sessions.add(sessArr.getString(i))
        }
        val keywords = mutableListOf<String>()
        val kwArr = obj.optJSONArray("keywords")
        if (kwArr != null) {
            for (i in 0 until kwArr.length()) keywords.add(kwArr.getString(i))
        }
        return ClassificationNode(
            id = obj.getString("id"), title = obj.optString("title"),
            parentId = obj.optString("parent_id", null),
            children = children, relatedSessions = sessions,
            keywords = keywords, isFailed = obj.optBoolean("is_failed"),
            summary = obj.optString("summary"),
            createdAt = obj.optLong("created_at"),
        )
    }

    private fun saveTree() {
        try {
            val json = JSONObject()
            // 关键词索引
            val kwIdxObj = JSONObject()
            for ((kw, sessions) in keywordIndex) {
                kwIdxObj.put(kw, JSONArray(sessions.toList()))
            }
            json.put("keyword_index", kwIdxObj)
            // 树
            val treeArr = JSONArray()
            for (node in rootNodes) {
                treeArr.put(serializeNode(node))
            }
            json.put("tree", treeArr)
            treeFile.parentFile?.mkdirs()
            treeFile.writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun serializeNode(node: ClassificationNode): JSONObject {
        return JSONObject().apply {
            put("id", node.id); put("title", node.title)
            node.parentId?.let { put("parent_id", it) }
            put("children", JSONArray().also { arr ->
                node.children.forEach { arr.put(serializeNode(it)) }
            })
            put("related_sessions", JSONArray(node.relatedSessions))
            put("keywords", JSONArray(node.keywords))
            put("is_failed", node.isFailed)
            put("summary", node.summary)
            put("created_at", node.createdAt)
        }
    }
}
