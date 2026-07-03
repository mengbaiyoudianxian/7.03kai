package com.mbclaw.nonroot.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.mbclaw.nonroot.data.UserSettings
import com.mbclaw.nonroot.data.VisionPresets
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * VisionLocator — 通道2: 截图+VLM视觉定位 (参考 Open-AutoGLM)
 *
 * 工作流:
 *   1. screencap → PNG → base64
 *   2. 嵌入 OpenAI 格式的 image_url → 发给 VLM
 *   3. VLM 返回: do(action="Tap", element=[500, 100])
 *   4. 解析坐标 → 返回物理像素坐标
 *
 * 配置来源: VisionPresets (豆包/AutoGLM) + VisionVoiceSheet 用户填的 Key
 *
 * 与 Open-AutoGLM 的关键差异:
 *   - MBclaw 用 root screencap (无需 adb pull)
 *   - MBclaw 用 root input tap (无需 ADB, 更可靠)
 *   - MBclaw 可选 uiautomator 通道作为快速路径 (互补)
 */
object VisionLocator {

    private const val TAG = "MBclaw-Vision"

    /** 定位结果 */
    data class LocateResult(
        val success: Boolean,
        val x: Int = 0,           // 物理像素 X
        val y: Int = 0,           // 物理像素 Y
        val action: String = "",  // "Tap" | "Swipe" | "Type" | "Long Press" | ...
        val text: String = "",    // Type 动作的文本
        val confidence: Float = 0f,
        val thinking: String = "", // VLM 的思考过程
        val errorReason: String = "",
    )

    /** 系统提示词 — 精简版，仿 Open-AutoGLM 中文 prompt */
    private fun buildSystemPrompt(): String {
        val today = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date())
        return """
今天的日期是: $today
你是一个手机操作智能体。你会收到一张手机屏幕截图和一个操作任务。你必须分析截图，然后输出一个具体的操作指令。

输出必须严格按照以下格式：
<think>简短推理说明</think>
<answer>你的操作指令</answer>

操作指令（二选一）：
1. do(action="Tap", element=[x,y])
   点击屏幕上的点。坐标 [x,y] 是归一化坐标，x 和 y 范围 0-999，(0,0)是左上角，(999,999)是右下角。
2. finish(message="原因")
   任务已完成或无法完成。

【重要规则】
- 先仔细观察截图，找到目标元素
- element 坐标是该元素的中心点
- 如果找不到目标，输出 finish(message="未找到目标")
- 只输出一个操作，不要一次输出多个
- 不要输出除 <think>/<answer> 之外的文字
""".trimIndent()
    }

    /**
     * 核心: 截图 → VLM → 坐标
     *
     * @param ctx Android Context
     * @param settings 用户设置 (获取 vision key/url/model)
     * @param taskDescription 操作描述 (如 "点击发送按钮"、"找到搜索框")
     * @return LocateResult 含物理坐标
     */
    suspend fun locate(
        ctx: Context,
        settings: UserSettings,
        taskDescription: String,
    ): LocateResult = withContext(Dispatchers.IO) {
        // 0. 检查视觉模型是否已配置
        if (!settings.visionEnabled || settings.visionApiKey.isBlank()) {
            return@withContext LocateResult(false, errorReason = "视觉模型未配置，请在设置→视觉识图中填写 API Key")
        }

        val tier = PermissionTier.get(ctx)

        // 1. 截图
        val screenshotB64 = captureScreenBase64(tier)
        if (screenshotB64 == null) {
            if (!tier.hasRoot) {
                return@withContext LocateResult(false,
                    errorReason = "非Root设备无法截图。视觉定位需要 Root 权限。\n替代方案: 使用 see_screen + click_by_index (基于无障碍)")
            }
            return@withContext LocateResult(false, errorReason = "截图失败")
        }

        // 2. 获取屏幕尺寸
        val dm = ctx.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // 3. 调用 VLM
        try {
            val response = callVisionModel(
                baseUrl = settings.visionBaseUrl.trimEnd('/'),
                apiKey = settings.visionApiKey.trim(),
                model = settings.visionModel.trim(),
                imageBase64 = screenshotB64,
                taskDescription = taskDescription,
                screenW = screenW,
                screenH = screenH,
            )

            // 4. 解析响应
            val parsed = parseVisionResponse(response, screenW, screenH)

            // 5. 清理临时截图
            tier.shellRoot("rm /sdcard/mb_vision_*.png 2>/dev/null")

            parsed
        } catch (e: Exception) {
            android.util.Log.e(TAG, "VLM 调用失败: ${e.message}")
            LocateResult(false, errorReason = "VLM 调用失败: ${e.message}")
        }
    }

    /** 通过 root shell 截图并返回 base64 */
    private fun captureScreenBase64(tier: PermissionTier): String? {
        return try {
            val path = "/sdcard/mb_vision_${System.currentTimeMillis()}.png"
            val cmd = "screencap -p $path && echo OK"
            val out = tier.shellRoot(cmd, timeoutMs = 10000)
            if (out == null || !out.contains("OK")) return null

            // 把文件读出来 (两种方式: root cat 或直接读 /sdcard)
            // root cat 能绕过一些权限限制
            val bytes = tier.shellRoot("cat $path | base64 -w0", timeoutMs = 15000)
            tier.shellRoot("rm $path 2>/dev/null")

            bytes?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "截图失败: ${e.message}")
            null
        }
    }

    /**
     * 调用视觉模型 API — OpenAI 兼容格式
     *
     * 支持: 豆包(火山引擎) / 智谱 AutoGLM-Phone / 及其他兼容接口
     */
    private fun callVisionModel(
        baseUrl: String,
        apiKey: String,
        model: String,
        imageBase64: String,
        taskDescription: String,
        screenW: Int,
        screenH: Int,
    ): String {
        // 构造消息: system prompt + user(图片+文字)
        val messages = JSONArray()

        // System
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", buildSystemPrompt())
        })

        // User: image + text
        val userContent = JSONArray()
        // 图片 (OpenAI 格式: image_url)
        userContent.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().apply {
                put("url", "data:image/png;base64,$imageBase64")
                put("detail", "high")  // 高细节, 豆包需要
            })
        })
        // 文字
        userContent.put(JSONObject().apply {
            put("type", "text")
            put("text", "屏幕尺寸: ${screenW}x${screenH} 像素\n\n" +
                        "任务: $taskDescription\n\n" +
                        "请找到目标元素并输出点击坐标(归一化0-999)。")
        })

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        // 请求体
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.0)      // 确定性输出
            put("max_tokens", 1024)
            put("stream", false)
        }

        // 发请求
        val url = URL("$baseUrl/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) {
            val errBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw Exception("VLM HTTP ${conn.responseCode}: ${errBody.take(500)}")
        }

        val responseText = conn.inputStream.bufferedReader().readText()
        val resp = JSONObject(responseText)
        val choices = resp.optJSONArray("choices")
        val content = choices
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: throw Exception("VLM 响应为空")

        android.util.Log.d(TAG, "VLM raw: ${content.take(300)}")
        return content
    }

    /**
     * 解析 VLM 响应 → LocateResult
     *
     * 支持格式:
     *   <answer>do(action="Tap", element=[500, 100])</answer>
     *   do(action="Tap", element=[500, 100])
     *   finish(message="xxx")
     */
    private fun parseVisionResponse(content: String, screenW: Int, screenH: Int): LocateResult {
        // 1. 提取 <think> 部分
        val thinking = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.groupValues?.getOrNull(1)?.trim() ?: ""

        // 2. 提取 <answer> 中的操作指令, 没有标签则直接用整个 content
        val actionText = Regex("""<answer>(.*?)</answer>""", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.groupValues?.getOrNull(1)?.trim() ?: content

        // 3. 检测 finish
        if (actionText.contains("finish(message=")) {
            val msg = Regex("""finish\(message="([^"]*)"""")
                .find(actionText)?.groupValues?.getOrNull(1) ?: "任务结束"
            return LocateResult(false, errorReason = msg)
        }

        // 4. 解析 do(action="Tap", element=[x,y])
        val actionMatch = Regex("""do\(action="(\w+(?:\s+\w+)?)"(?:,\s*(\w+)="?([^")]+)"?)?(?:,\s*(\w+)=\[(\d+)\s*,\s*(\d+)\])?""")
            .find(actionText)

        if (actionMatch == null) {
            // 尝试更宽松的匹配
            val tapMatch = Regex("""Tap.*?element\s*=\s*\[(\d+)\s*[,，]\s*(\d+)]""").find(actionText)
            if (tapMatch != null) {
                val nx = tapMatch.groupValues[1].toIntOrNull() ?: 0
                val ny = tapMatch.groupValues[2].toIntOrNull() ?: 0
                val px = (nx / 1000f * screenW).toInt()
                val py = (ny / 1000f * screenH).toInt()
                return LocateResult(true, px, py, "Tap", confidence = 0.7f, thinking = thinking)
            }

            return LocateResult(false, errorReason = "无法解析 VLM 响应: ${actionText.take(100)}")
        }

        val actionName = actionMatch.groupValues[1]
        val param1Name = actionMatch.groupValues[2]  // element / text / app
        val param1Val = actionMatch.groupValues[3]
        val param2Name = actionMatch.groupValues[4]  // element (如果是双参数)
        val nx = actionMatch.groupValues[5].toIntOrNull() ?: 0
        val ny = actionMatch.groupValues[6].toIntOrNull() ?: 0

        return when {
            actionName == "Tap" || actionName == "Long Press" || actionName == "Double Tap" -> {
                val px = (nx / 1000f * screenW).toInt()
                val py = (ny / 1000f * screenH).toInt()
                LocateResult(true, px, py, actionName, confidence = 0.8f, thinking = thinking)
            }
            actionName == "Type" || actionName == "Type_Name" -> {
                val text = param1Val
                LocateResult(true, action = actionName, text = text, confidence = 0.8f, thinking = thinking)
            }
            actionName == "Swipe" -> {
                // Swipe 有 start 和 end 参数, 这里简单处理
                val swipeMatch = Regex("""start=\[(\d+),(\d+)\].*?end=\[(\d+),(\d+)]""").find(actionText)
                if (swipeMatch != null) {
                    val sx = (swipeMatch.groupValues[1].toIntOrNull() ?: 0) / 1000f * screenW
                    val sy = (swipeMatch.groupValues[2].toIntOrNull() ?: 0) / 1000f * screenH
                    LocateResult(true, sx.toInt(), sy.toInt(), "Swipe", confidence = 0.8f, thinking = thinking)
                } else {
                    LocateResult(false, errorReason = "Swipe 参数不完整")
                }
            }
            actionName == "Launch" -> {
                LocateResult(true, action = "Launch", text = param1Val, confidence = 0.9f, thinking = thinking)
            }
            actionName == "Back" || actionName == "Home" || actionName == "Wait" -> {
                LocateResult(true, action = actionName, confidence = 0.9f, thinking = thinking)
            }
            else -> {
                LocateResult(false, errorReason = "未知动作: $actionName")
            }
        }
    }

    /** 快速探测: 视觉模型是否可用 (连通性测试) */
    suspend fun probe(ctx: Context, settings: UserSettings): String = withContext(Dispatchers.IO) {
        if (!settings.visionEnabled) return@withContext "视觉模型未启用"
        if (settings.visionApiKey.isBlank()) return@withContext "未配置 API Key"

        try {
            val url = URL("${settings.visionBaseUrl.trimEnd('/')}/models")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Authorization", "Bearer ${settings.visionApiKey.trim()}")
            val code = conn.responseCode
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                "✅ 视觉模型连通 (${settings.visionModel})\n可用模型: ${body.take(300)}"
            } else {
                "⚠️ 视觉模型返回 HTTP $code"
            }
        } catch (e: Exception) {
            "❌ 视觉模型不可达: ${e.message?.take(100)}"
        }
    }
}
