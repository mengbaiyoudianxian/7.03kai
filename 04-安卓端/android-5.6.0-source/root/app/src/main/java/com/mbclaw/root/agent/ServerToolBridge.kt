package com.mbclaw.root.agent

import com.mbclaw.root.api.MBclawApiService
import com.mbclaw.root.api.ToolExecRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 服务端工具桥接 — 将 r0 服务端的 17 个工具接入 Android 客户端
 * 客户端本地工具 (31个手机操作) + 服务端工具 (17个通用工具) = 48 个工具
 */
class ServerToolBridge(private val api: MBclawApiService) {

    /** 从服务端拉取工具列表 */
    suspend fun fetchServerTools(): List<ToolRegistry.ToolDef> = withContext(Dispatchers.IO) {
        try {
            val response = api.listTools()
            if (response.isSuccessful) {
                response.body()?.map { tool ->
                    ToolRegistry.ToolDef(
                        name = "server_${tool.name}",
                        description = tool.summary,
                        parameters = org.json.JSONObject(tool.parameters ?: mapOf<String, Any?>())
                    )
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 在服务端执行工具并返回结果 */
    suspend fun executeOnServer(toolName: String, content: String): String = withContext(Dispatchers.IO) {
        try {
            val response = api.executeTool(ToolExecRequest(toolName, content))
            if (response.isSuccessful) {
                response.body()?.result ?: "服务端工具执行返回空"
            } else {
                "服务端工具执行失败: ${response.code()}"
            }
        } catch (e: Exception) {
            "服务端连接失败: ${e.message}"
        }
    }

    /** 获取合并后的全量工具列表 (本地 + 服务端) */
    suspend fun getAllTools(): List<ToolRegistry.ToolDef> {
        val server = fetchServerTools()
        return ToolRegistry.ALL + server
    }
}
