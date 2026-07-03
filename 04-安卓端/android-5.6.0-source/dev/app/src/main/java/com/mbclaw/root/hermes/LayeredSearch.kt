package com.mbclaw.dev.hermes

import android.content.Context
import com.mbclaw.dev.data.LocalDB
import kotlinx.coroutines.*
import kotlin.math.ln
import kotlin.math.sqrt
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 项目六：分层记忆搜索 L1 → L2 → L3
 *
 * 每轮对话前自动预搜索，结果注入上下文
 *
 * L1: 关键词反向索引 (SQLite) — <10ms
 * L2: TF-IDF 语义排序 — <100ms
 * L3: 向量语义搜索 (Embedding API) — <500ms (可选)
 *
 * 成本控制:
 *   L3 需要调用 Embedding API，有额外费用
 *   默认只走 L1+L2，用户可在设置中开启 L3
 */

class LayeredSearch(
    private val db: LocalDB,
    private val transcriptLogger: TranscriptLogger,
) {

    data class SearchResult(
        val key: String,
        val value: String,
        val score: Float,
        val layer: String,     // "L1" | "L2" | "L3"
        val source: String? = null,
        val snippet: String = "",  // 高亮片段
    )

    data class SearchContext(
        val query: String,
        val maxResults: Int = 5,
        val enableL3: Boolean = false,
        val embeddingApiBaseUrl: String = "",
        val embeddingApiKey: String = "",
        val embeddingModel: String = "text-embedding-3-small",
    )

    // ── L1: 关键词反向索引 (<10ms) ──

    fun layer1KeywordSearch(query: String, limit: Int = 20): List<SearchResult> {
        val startTime = System.nanoTime()
        val results = db.searchMemory(query, limit)
        val elapsed = (System.nanoTime() - startTime) / 1_000_000

        return results.map { row ->
            SearchResult(
                key = row.key, value = row.value,
                score = row.accessCount.toFloat() / maxOf(db.getAllMemoryKeys().size, 1),
                layer = "L1(${elapsed}ms)", source = row.source,
            )
        }
    }

    // ── L2: TF-IDF 语义排序 (<100ms) ──

    fun layer2TfIdf(query: String, candidates: List<SearchResult>, limit: Int = 10): List<SearchResult> {
        val startTime = System.nanoTime()

        // 计算所有候选文档的 TF-IDF
        val allTexts = candidates.map { "${it.key} ${it.value}" }
        val allMemories = db.getAllMemoryKeys().mapNotNull { key ->
            val rows = db.searchMemory(key, 1)
            if (rows.isNotEmpty()) "${rows[0].key} ${rows[0].value}" else null
        }

        val scored = candidates.mapIndexed { index, candidate ->
            val docText = allTexts[index]
            val tfidfScore = computeTfIdf(query, docText, allMemories + allTexts)
            candidate.copy(
                score = (candidate.score * 0.3f + tfidfScore * 0.7f),
                layer = "L2(${(System.nanoTime() - startTime) / 1_000_000}ms)@${candidate.layer}",
            )
        }.sortedByDescending { it.score }.take(limit)

        return scored
    }

    // ── L3: 向量语义搜索 (需要 Embedding API) ──

    suspend fun layer3VectorSearch(
        query: String,
        candidates: List<SearchResult>,
        baseUrl: String,
        apiKey: String,
        model: String = "text-embedding-3-small",
        limit: Int = 5,
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank()) return@withContext candidates

        try {
            val startTime = System.nanoTime()

            // 调用 Embedding API 获取 query 向量
            val queryEmbedding = getEmbedding(query, baseUrl, apiKey, model) ?: return@withContext candidates

            // 对每个候选文档计算余弦相似度
            val scored = candidates.map { candidate ->
                val docEmbedding = getEmbedding("${candidate.key}: ${candidate.value.take(500)}", baseUrl, apiKey, model)
                if (docEmbedding != null) {
                    val similarity = cosineSimilarity(queryEmbedding, docEmbedding)
                    candidate.copy(
                        score = candidate.score * 0.2f + similarity * 0.8f,
                        layer = "L3(${(System.nanoTime() - startTime) / 1_000_000}ms)@${candidate.layer}",
                    )
                } else candidate
            }.sortedByDescending { it.score }.take(limit)

            return@withContext scored
        } catch (_: Exception) {
            return@withContext candidates // L3 不可用时降级到 L2 结果
        }
    }

    // ── 统一搜索入口 ──

    suspend fun search(context: SearchContext): List<SearchResult> {
        // L1: 快速关键词
        val l1Results = layer1KeywordSearch(context.query, context.maxResults * 4)

        if (l1Results.isEmpty()) return emptyList()

        // L2: TF-IDF 重排序
        val l2Results = layer2TfIdf(context.query, l1Results, context.maxResults * 2)

        // L3: 向量语义 (可选)
        return if (context.enableL3 && context.embeddingApiKey.isNotBlank()) {
            layer3VectorSearch(context.query, l2Results, context.embeddingApiBaseUrl, context.embeddingApiKey, context.embeddingModel, context.maxResults)
        } else {
            l2Results.take(context.maxResults)
        }
    }

    /** 跨维度搜索 (蓝图07-lessons: 搜索应同时返回项目/会话/消息/总结/关键词) */
    fun crossDimensionalSearch(query: String, db: LocalDB, limit: Int = 20): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // 消息维度
        db.readableDatabase.rawQuery("SELECT content FROM messages WHERE content LIKE ? LIMIT 5", arrayOf("%$query%")).use { c -> while (c.moveToNext()) results.add(SearchResult("消息", c.getString(0).take(100), 0.5f, "L1")) }
        // 总结维度
        db.readableDatabase.rawQuery("SELECT topic, conclusions FROM summaries WHERE topic LIKE ? OR conclusions LIKE ? LIMIT 5", arrayOf("%$query%", "%$query%")).use { c -> while (c.moveToNext()) results.add(SearchResult("总结:${c.getString(0)}", c.getString(1)?.take(100) ?: "", 0.6f, "L1")) }
        // 关键词维度
        db.readableDatabase.rawQuery("SELECT keyword FROM keywords WHERE keyword LIKE ? LIMIT 5", arrayOf("%$query%")).use { c -> while (c.moveToNext()) results.add(SearchResult("关键词", c.getString(0), 0.7f, "L1")) }
        // 话题维度
        db.readableDatabase.rawQuery("SELECT name, summary FROM topic_tree WHERE name LIKE ? OR summary LIKE ? LIMIT 5", arrayOf("%$query%", "%$query%")).use { c -> while (c.moveToNext()) results.add(SearchResult("话题:${c.getString(0)}", c.getString(1)?.take(100) ?: "", 0.5f, "L1")) }
        return results.distinctBy { it.key + it.value }.take(limit)
    }

    /** 将搜索结果格式化为索引引用 — 不注入原话，只给指针 */
    fun formatForInjection(results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        return buildString {
            appendLine("[记忆索引 — 需要在回复中引用时调 search_memory 获取原文]")
            results.forEachIndexed { i, r ->
                val key = r.key.take(30)
                val layer = r.layer.take(8)
                val score = "%.0f".format(r.score * 100)
                appendLine("  [MEM#${i+1}] $key (${layer}, ${score}%)")
            }
            appendLine("[/记忆索引]")
        }
    }

    // ── TF-IDF 实现 ──

    private fun computeTfIdf(query: String, document: String, corpus: List<String>): Float {
        val queryTerms = tokenize(query)
        val docTerms = tokenize(document)
        val totalDocs = corpus.size + 1  // +1 for the document itself

        var score = 0f
        for (term in queryTerms) {
            val tf = docTerms.count { it == term }.toFloat() / maxOf(docTerms.size, 1)
            val df = corpus.count { tokenize(it).contains(term) } + 1
            val idf = ln(totalDocs.toFloat() / df).toFloat()
            score += tf * idf
        }
        return score / maxOf(queryTerms.size, 1)
    }

    private fun tokenize(text: String): List<String> {
        // 简单分词: Unicode + 2-gram
        val cleaned = text.lowercase().trim()
        val chars = cleaned.filter { it.isLetterOrDigit() || it.isWhitespace() }
        val words = chars.split("\\s+".toRegex()).filter { it.length >= 2 }
        // 中文2-gram
        val bigrams = mutableListOf<String>()
        for (i in 0 until cleaned.length - 1) {
            val c1 = cleaned[i]; val c2 = cleaned[i + 1]
            if (c1.code > 127 && c2.code > 127) { // CJK characters
                bigrams.add("$c1$c2")
            }
        }
        return words + bigrams
    }

    // ── Embedding API ──

    private suspend fun getEmbedding(text: String, baseUrl: String, apiKey: String, model: String): FloatArray? {
        return try {
            val url = "${baseUrl.trimEnd('/')}/embeddings"
            val json = org.json.JSONObject().apply {
                put("model", model)
                put("input", text.take(8000)) // 8K token limit
            }

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val responseObj = org.json.JSONObject(body)
            val embeddingArray = responseObj.getJSONArray("data")
                .getJSONObject(0)
                .getJSONArray("embedding")

            FloatArray(embeddingArray.length()) { embeddingArray.getDouble(it).toFloat() }
        } catch (_: Exception) { null }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]
        }
        return if (normA > 0 && normB > 0) dot / (sqrt(normA) * sqrt(normB)) else 0f
    }
}
