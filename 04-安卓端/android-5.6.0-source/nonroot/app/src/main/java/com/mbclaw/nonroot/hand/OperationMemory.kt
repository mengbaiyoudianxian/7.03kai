package com.mbclaw.nonroot.hand

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 操作记忆 — 对接项目DNA
 *
 * 每次点击 → 结构化记录 → 后续同界面直接复用坐标
 * 失败3次 → 自动调整策略
 */
class OperationMemory(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mbclaw_hand_memory", Context.MODE_PRIVATE)

    data class OpRecord(
        val sessionId: String,
        val deviceId: String,
        val screenSignature: String,   // "com.xxx.app/1080x2400/layout_hash"
        val operationDesc: String,
        val methodUsed: String,        // "fuzzy" | "block" | "fusion" | "historical"
        val x: Int, val y: Int,       // 归一化坐标 (0-1000)
        val success: Boolean,
        val confidence: Float,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val records = mutableListOf<OpRecord>()

    init { loadRecords() }

    // ── 记录 ──

    fun record(op: OpRecord) {
        records.add(op)
        // 保留最近200条
        while (records.size > 200) records.removeAt(0)
        saveRecords()

        // 失败3次 → 记录失败经验
        if (!op.success) {
            val failures = records.filter { it.screenSignature == op.screenSignature && !it.success }
            if (failures.size >= 3) {
                prefs.edit().putBoolean("avoid_${op.screenSignature}_${op.methodUsed}", true).apply()
            }
        }
    }

    /** 构建屏幕签名 */
    fun screenSignature(packageName: String, width: Int, height: Int, layoutHash: String = "default"): String {
        return "$packageName/${width}x$height/$layoutHash"
    }

    // ── 检索 ──

    /** 查找相似操作的历史成功经验 */
    fun findSimilar(screenSignature: String, operationDesc: String): OpRecord? {
        val candidates = records.filter {
            it.success && it.screenSignature == screenSignature
        }
        if (candidates.isEmpty()) {
            // 宽松匹配: 相同包名
            val pkg = screenSignature.split("/").first()
            return records.filter { it.success && it.screenSignature.startsWith(pkg) }
                .maxByOrNull { it.confidence }
        }
        // 精确匹配: 相同签名 + 相似描述
        val exact = candidates.filter { similarity(it.operationDesc, operationDesc) > 0.6f }
        return exact.maxByOrNull { it.confidence }
    }

    /** 检查某个策略在该场景下是否被标记为不可用 */
    fun isMethodAvoided(screenSignature: String, method: String): Boolean {
        return prefs.getBoolean("avoid_${screenSignature}_$method", false)
    }

    /** 获取该界面的所有历史操作 */
    fun getHistoryForScreen(screenSignature: String): List<OpRecord> {
        return records.filter { it.screenSignature == screenSignature }
    }

    // ── 统计 ──

    fun getSuccessRate(): Float {
        if (records.isEmpty()) return 0f
        return records.count { it.success }.toFloat() / records.size
    }

    fun getMethodStats(): Map<String, Pair<Int, Int>> {  // method → (success, total)
        return records.groupBy { it.methodUsed }.mapValues { (_, ops) ->
            Pair(ops.count { it.success }, ops.size)
        }
    }

    // ── 持久化 ──

    private fun loadRecords() {
        val json = prefs.getString("op_records", null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                records.add(OpRecord(
                    obj.getString("sid"), obj.getString("did"), obj.getString("sig"),
                    obj.getString("desc"), obj.getString("method"),
                    obj.getInt("x"), obj.getInt("y"),
                    obj.getBoolean("ok"), obj.getDouble("conf").toFloat(),
                    obj.getLong("ts"),
                ))
            }
        } catch (_: Exception) {}
    }

    private fun saveRecords() {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("sid", r.sessionId); put("did", r.deviceId)
                put("sig", r.screenSignature); put("desc", r.operationDesc)
                put("method", r.methodUsed); put("x", r.x); put("y", r.y)
                put("ok", r.success); put("conf", r.confidence.toDouble()); put("ts", r.timestamp)
            })
        }
        prefs.edit().putString("op_records", arr.toString()).apply()
    }

    private fun similarity(a: String, b: String): Float {
        val setA = a.split(Regex("[\\s的到一个了]")).filter { it.length >= 2 }.toSet()
        val setB = b.split(Regex("[\\s的到一个了]")).filter { it.length >= 2 }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val intersection = setA.intersect(setB).size
        return intersection.toFloat() / maxOf(setA.size, setB.size)
    }
}
