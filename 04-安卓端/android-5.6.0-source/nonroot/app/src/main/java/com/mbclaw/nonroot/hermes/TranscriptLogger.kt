package com.mbclaw.nonroot.hermes

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 项目一：详细日志备份
 *
 * 需求对照:
 *   ✅ 对话内容记录 → JSONL transcript
 *   ✅ 代码变更记录 → type:"code_change" + file_path + diff
 *   ✅ AI思考过程 → thinking_content 字段
 *   ✅ 文件分片 → 每文件最大5MB
 *   ✅ 压缩策略 → 旧分片zip压缩
 */

class TranscriptLogger(private val context: Context) {

    private val transcriptDir = File(context.filesDir, "hermes/transcripts")
    private val archiveDir = File(context.filesDir, "hermes/transcripts/archive")
    private val maxFileSize = 5 * 1024 * 1024L // 5MB per shard
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class TranscriptEntry(
        val sessionId: String,
        val type: String,           // "message" | "code_change" | "thinking" | "decision" | "snapshot"
        val role: String? = null,   // "user" | "assistant" | "system"
        val content: String? = null,
        val thinking: String? = null,  // AI 思考过程
        val filePath: String? = null,   // 代码变更: 文件路径
        val diff: String? = null,       // 代码变更: diff内容
        val metadata: Map<String, String> = emptyMap(),
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class TranscriptStats(
        val totalEntries: Long,
        val totalSizeBytes: Long,
        val shardCount: Int,
        val oldestEntry: Long,
        val newestEntry: Long,
    )

    init { transcriptDir.mkdirs(); archiveDir.mkdirs() }

    // ── 写入 ──

    fun log(entry: TranscriptEntry) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("session_id", entry.sessionId)
                    put("type", entry.type)
                    entry.role?.let { put("role", it) }
                    entry.content?.let { put("content", it) }
                    entry.thinking?.let { put("thinking", it) }
                    entry.filePath?.let { put("file_path", it) }
                    entry.diff?.let { put("diff", it) }
                    if (entry.metadata.isNotEmpty()) {
                        put("metadata", JSONObject(entry.metadata))
                    }
                    put("timestamp", entry.timestamp)
                }

                val shard = getCurrentShard(entry.sessionId)
                val writer = RandomAccessFile(shard, "rw")
                writer.seek(shard.length())
                writer.writeBytes(json.toString() + "\n")
                writer.close()

                // 检查是否需要分片
                if (shard.length() > maxFileSize) {
                    rotateShard(entry.sessionId)
                }
            } catch (_: Exception) {}
        }
    }

    /** 记录代码变更 */
    fun logCodeChange(sessionId: String, filePath: String, diff: String) {
        log(TranscriptEntry(
            sessionId = sessionId, type = "code_change",
            filePath = filePath, diff = diff,
        ))
    }

    /** 记录AI思考过程 */
    fun logThinking(sessionId: String, thinking: String) {
        log(TranscriptEntry(
            sessionId = sessionId, type = "thinking",
            thinking = thinking,
        ))
    }

    /** 记录决策 */
    fun logDecision(sessionId: String, decision: String, reasoning: String) {
        log(TranscriptEntry(
            sessionId = sessionId, type = "decision",
            content = decision, thinking = reasoning,
        ))
    }

    // ── 读取 ──

    fun readTranscript(sessionId: String, limit: Int = 200): List<TranscriptEntry> {
        val entries = mutableListOf<TranscriptEntry>()
        val shards = getShards(sessionId)
        var count = 0

        for (shard in shards.reversed()) {
            if (count >= limit) break
            try {
                shard.forEachLine { line ->
                    if (count >= limit) return@forEachLine
                    try {
                        val obj = JSONObject(line)
                        entries.add(TranscriptEntry(
                            sessionId = obj.optString("session_id"),
                            type = obj.optString("type"),
                            role = obj.optString("role", null),
                            content = obj.optString("content", null),
                            thinking = obj.optString("thinking", null),
                            filePath = obj.optString("file_path", null),
                            diff = obj.optString("diff", null),
                            timestamp = obj.optLong("timestamp"),
                        ))
                        count++
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return entries.reversed()
    }

    /** 搜索所有 transcript */
    fun searchTranscripts(query: String, limit: Int = 20): List<TranscriptEntry> {
        val results = mutableListOf<TranscriptEntry>()
        val allShards = transcriptDir.listFiles()?.filter { it.name.endsWith(".jsonl") } ?: emptyList()

        for (shard in allShards.take(10)) { // 限制扫描10个分片
            try {
                shard.forEachLine { line ->
                    if (line.contains(query, ignoreCase = true)) {
                        try {
                            val obj = JSONObject(line)
                            results.add(TranscriptEntry(
                                sessionId = obj.optString("session_id"),
                                type = obj.optString("type"),
                                content = obj.optString("content", null),
                                timestamp = obj.optLong("timestamp"),
                            ))
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
        return results.sortedByDescending { it.timestamp }.take(limit)
    }

    // ── 维护 ──

    fun getStats(): TranscriptStats {
        val shards = transcriptDir.listFiles()?.filter { it.name.endsWith(".jsonl") } ?: emptyList()
        var totalEntries = 0L; var totalSize = 0L
        var oldest = Long.MAX_VALUE; var newest = 0L

        for (shard in shards) {
            totalSize += shard.length()
            try {
                shard.forEachLine { line ->
                    totalEntries++
                    try {
                        val ts = JSONObject(line).optLong("timestamp", 0)
                        if (ts < oldest) oldest = ts
                        if (ts > newest) newest = ts
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        return TranscriptStats(totalEntries, totalSize, shards.size,
            if (oldest == Long.MAX_VALUE) 0 else oldest, newest)
    }

    /** 归档超过30天的 transcript */
    fun archiveOld(daysThreshold: Int = 30) {
        scope.launch {
            val threshold = System.currentTimeMillis() - daysThreshold * 24 * 3600 * 1000L
            val shards = transcriptDir.listFiles()?.filter { it.name.endsWith(".jsonl") } ?: emptyList()
            for (shard in shards) {
                if (shard.lastModified() < threshold) {
                    // 压缩并移到archive
                    val gzipFile = File(archiveDir, "${shard.name}.gz")
                    java.util.zip.GZIPOutputStream(gzipFile.outputStream()).use { gz ->
                        shard.inputStream().use { input -> input.copyTo(gz) }
                    }
                    shard.delete()
                }
            }
        }
    }

    // ── 内部 ──

    private fun getCurrentShard(sessionId: String): File {
        val prefix = sessionId.take(8)
        var shard = File(transcriptDir, "${prefix}_000.jsonl")
        if (!shard.exists()) {
            shard.createNewFile()
        }
        // Find latest shard
        val existing = transcriptDir.listFiles()?.filter {
            it.name.startsWith(prefix) && it.name.endsWith(".jsonl")
        }?.sortedByDescending { it.name } ?: emptyList()
        return if (existing.isNotEmpty() && existing.first().length() < maxFileSize) existing.first() else {
            val nextIndex = existing.size
            File(transcriptDir, "${prefix}_${"%03d".format(nextIndex)}.jsonl").also { it.createNewFile() }
        }
    }

    private fun getShards(sessionId: String): List<File> {
        val prefix = sessionId.take(8)
        return transcriptDir.listFiles()?.filter {
            it.name.startsWith(prefix) && it.name.endsWith(".jsonl")
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun rotateShard(sessionId: String) {
        val prefix = sessionId.take(8)
        val existing = transcriptDir.listFiles()?.count {
            it.name.startsWith(prefix) && it.name.endsWith(".jsonl")
        } ?: 0
        File(transcriptDir, "${prefix}_${"%03d".format(existing)}.jsonl").createNewFile()
    }
}
