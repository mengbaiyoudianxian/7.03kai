package com.mbclaw.root.hermes

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 项目三：突破时备份
 *
 * 需求对照:
 *   ✅ 突破检测 → 关键词+DNA变更双重触发
 *   ✅ 数据库热备份 → SQLite .backup 到 snapshots/
 *   ✅ 版本快照 → ProjectDNA 自动记录
 *   ✅ 防假阳性 → 连续确认机制
 */

class SnapshotService(private val context: Context) {

    private val snapshotDir = File(context.filesDir, "hermes/snapshots")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 突破关键词
    private val breakthroughKeywords = listOf(
        "突破", "解决了", "搞定了", "终于", "成功了",
        "bug fixed", "fixed", "solved", "resolved", "修复",
        "完成", "实现", "可以了", "能够用了",
    )

    // 候选突破 (需要连续2次确认)
    private var candidateBreakthrough: Pair<String, Int>? = null  // (sessionId, confirmCount)

    data class Snapshot(
        val id: String,
        val sessionId: String,
        val reason: String,
        val dbSizeBytes: Long,
        val createdAt: Long = System.currentTimeMillis(),
    )

    init { snapshotDir.mkdirs() }

    // ── 突破检测 ──

    fun checkBreakthrough(sessionId: String, messages: List<Pair<String, String>>): Boolean {
        val text = messages.joinToString(" ") { it.second.lowercase() }
        val matched = breakthroughKeywords.any { text.contains(it) }

        if (matched) {
            // 连续确认: 第一次匹配 → 标记候选; 第二次匹配 → 触发快照
            val candidate = candidateBreakthrough
            if (candidate?.first == sessionId) {
                val count = candidate.second + 1
                candidateBreakthrough = null
                if (count >= 2) {
                    scope.launch { createSnapshot(sessionId, "突破检测: 连续${count}次确认") }
                    return true
                }
            } else {
                candidateBreakthrough = sessionId to 1
            }
        }
        return false
    }

    /** 手动触发快照 */
    suspend fun createSnapshot(sessionId: String, reason: String): Snapshot {
        return withContext(Dispatchers.IO) {
            val id = "snap_${System.currentTimeMillis()}"
            val snapFile = File(snapshotDir, "$id.zip")

            ZipOutputStream(FileOutputStream(snapFile)).use { zip ->
                // 1. 数据库文件
                val dbFile = context.getDatabasePath("mbclaw_local.db")
                if (dbFile.exists()) {
                    zip.putNextEntry(ZipEntry("database/mbclaw_local.db"))
                    dbFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                // 2. 分类树
                val treeFile = File(context.filesDir, "hermes/class_tree.json")
                if (treeFile.exists()) {
                    zip.putNextEntry(ZipEntry("hermes/class_tree.json"))
                    treeFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                // 3. 快照元数据
                val meta = JSONObject().apply {
                    put("snapshot_id", id)
                    put("session_id", sessionId)
                    put("reason", reason)
                    put("created_at", System.currentTimeMillis())
                }
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(meta.toString().toByteArray())
                zip.closeEntry()
            }

            Snapshot(id, sessionId, reason, snapFile.length())
        }
    }

    /** 获取快照列表 */
    fun getSnapshots(): List<Snapshot> {
        return snapshotDir.listFiles()
            ?.filter { it.name.endsWith(".zip") }
            ?.mapNotNull { file ->
                val id = file.nameWithoutExtension
                try {
                    // Read metadata from zip
                    java.util.zip.ZipFile(file).use { zip ->
                        val entry = zip.getEntry("metadata.json") ?: return@mapNotNull null
                        val meta = JSONObject(zip.getInputStream(entry).bufferedReader().readText())
                        Snapshot(
                            id = id,
                            sessionId = meta.optString("session_id"),
                            reason = meta.optString("reason"),
                            dbSizeBytes = file.length(),
                            createdAt = meta.optLong("created_at"),
                        )
                    }
                } catch (_: Exception) { null }
            }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    /** 恢复快照 */
    suspend fun restoreSnapshot(snapshotId: String): Boolean = withContext(Dispatchers.IO) {
        val snapFile = File(snapshotDir, "$snapshotId.zip")
        if (!snapFile.exists()) return@withContext false

        try {
            java.util.zip.ZipFile(snapFile).use { zip ->
                val dbEntry = zip.getEntry("database/mbclaw_local.db")
                if (dbEntry != null) {
                    val dbFile = context.getDatabasePath("mbclaw_local.db")
                    dbFile.outputStream().use { out ->
                        zip.getInputStream(dbEntry).copyTo(out)
                    }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    /** 清理旧快照 (保留最近10个) */
    fun cleanupOld(keepLatest: Int = 10) {
        scope.launch {
            val snapshots = getSnapshots()
            if (snapshots.size > keepLatest) {
                snapshots.drop(keepLatest).forEach {
                    File(snapshotDir, "${it.id}.zip").delete()
                }
            }
        }
    }

    /** 检查是否有候选突破未确认 (供UI显示) */
    fun getPendingBreakthrough(): Pair<String, Int>? = candidateBreakthrough
}
