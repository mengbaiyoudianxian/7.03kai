package com.mbclaw.nonroot.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CustomToolStore — 用户/云端自定义工具持久化
 *
 * 工具来源 (任务 6):
 *   • BUILTIN    — 系统自带 (ToolRegistry.ALL)
 *   • LOCAL      — 用户在本机自定义 / 添加
 *   • CLOUD      — 从云市场下载
 *   • GENERATED  — Agent 自动生成总结
 *   • SHARED     — 其他玩家分享
 */
data class CustomTool(
    val name: String,
    val description: String,
    val parameters: String,        // JSON schema 字符串
    val source: String,            // BUILTIN / LOCAL / CLOUD / GENERATED / SHARED
    val enabled: Boolean = true,
    val author: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

object CustomToolStore {

    private fun file(ctx: Context): File =
        File(ctx.filesDir, "custom_tools.json").also { f ->
            if (!f.exists()) f.writeText("[]")
        }

    fun loadAll(ctx: Context): List<CustomTool> = try {
        val arr = JSONArray(file(ctx).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CustomTool(
                name = o.optString("name"),
                description = o.optString("description"),
                parameters = o.optString("parameters", "{}"),
                source = o.optString("source", "LOCAL"),
                enabled = o.optBoolean("enabled", true),
                author = o.optString("author", ""),
                createdAt = o.optLong("createdAt", 0L),
            )
        }
    } catch (_: Exception) { emptyList() }

    fun saveAll(ctx: Context, tools: List<CustomTool>) {
        val arr = JSONArray()
        tools.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name); put("description", it.description)
                put("parameters", it.parameters); put("source", it.source)
                put("enabled", it.enabled); put("author", it.author)
                put("createdAt", it.createdAt)
            })
        }
        file(ctx).writeText(arr.toString())
    }

    fun add(ctx: Context, tool: CustomTool) {
        val list = loadAll(ctx).toMutableList()
        list.removeAll { it.name == tool.name }
        list.add(tool)
        saveAll(ctx, list)
    }

    fun remove(ctx: Context, name: String) {
        saveAll(ctx, loadAll(ctx).filter { it.name != name })
    }

    fun setEnabled(ctx: Context, name: String, enabled: Boolean) {
        saveAll(ctx, loadAll(ctx).map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    /** 上传工具到云市场 (任务 6) */
    suspend fun uploadToCloud(ctx: Context, serverUrl: String, tool: CustomTool): Boolean = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val u = java.net.URL("${serverUrl.trimEnd('/')}/admin/client/tools/upload")
            val conn = u.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().apply {
                put("name", tool.name); put("description", tool.description)
                put("parameters", tool.parameters); put("author", tool.author)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode in 200..299
        }
    } catch (_: Exception) { false }

    /** 从云市场下载工具列表 */
    suspend fun fetchCloudList(serverUrl: String): List<CustomTool> = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val u = java.net.URL("${serverUrl.trimEnd('/')}/admin/client/tools/list")
            val conn = u.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            val txt = conn.inputStream.bufferedReader().readText()
            val arr = JSONObject(txt).optJSONArray("tools") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CustomTool(
                    name = o.optString("name"),
                    description = o.optString("description"),
                    parameters = o.optString("parameters", "{}"),
                    source = "CLOUD",
                    author = o.optString("author", ""),
                )
            }
        }
    } catch (_: Exception) { emptyList() }
}
