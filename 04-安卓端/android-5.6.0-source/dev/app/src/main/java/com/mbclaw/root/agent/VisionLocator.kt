package com.mbclaw.dev.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.mbclaw.dev.data.UserSettings
import com.mbclaw.dev.data.VisionPresets
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
        val action: String = "",  // "Tap" | "Swipe" | "Type" | "Long Press" | "Launch" | "Wait"
        val text: String = "",    // Type/Launch 动作的参数
        val confidence: Float = 0f,
        val thinking: String = "", // VLM 的思考过程
        val errorReason: String = "",
        val startX: Int = 0,      // Swipe 起始X
        val startY: Int = 0,      // Swipe 起始Y
        val endX: Int = 0,        // Swipe 结束X
        val endY: Int = 0,        // Swipe 结束Y
        val duration: Int = 0,    // Swipe/LongPress/Wait 时长(ms)
        val packageName: String = "", // Launch 包名
    )

    private fun buildSystemPrompt(): String {
        val today = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date())
        return """
今天的日期是: $today
你是一个手机操作智能体。分析截图后输出操作指令。

输出格式:
<think>简短推理说明</think>
<answer>do(action="动作名", ...)</answer>

支持的全部动作:

1. do(action="Tap", element=[x,y])
   点击屏幕上的点。坐标归一化0-999。

2. do(action="Type", element=[x,y], text="要输入的文字")
   先点击element坐标获取焦点，再输入文字。

3. do(action="Swipe", start=[x1,y1], end=[x2,y2], duration=300)
   从start滑动到end，duration毫秒(默认300)。

4. do(action="LongPress", element=[x,y], duration=1000)
   长按指定位置。

5. do(action="Wait", duration=1500)
   等待界面加载，duration毫秒。

6. do(action="Launch", package="com.tencent.mm")
   启动指定包名的App。

7. finish(message="完成原因")
   任务完成或无法继续。

【重要规则】
- 先仔细观察截图，找到目标元素
- element坐标是该元素的中心点
- 只输出一个操作，不要一次输出多个
- 根据任务需求选择合适的动作类型
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
            tier.shellRoot("rm /sdcard/mb_vision_*.png 2>/dev/null")
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
        val sw = if (screenW > 0) screenW else 1080
        val sh = if (screenH > 0) screenH else 2400
        fun normX(nx: Int) = (nx / 999f * sw).toInt().coerceIn(0, sw)
        fun normY(ny: Int) = (ny / 999f * sh).toInt().coerceIn(0, sh)

        val actionText = content

        if (actionText.contains("finish(message=") || actionText.contains("finish\uff08message=")) {
            val msg = Regex("""finish[\uff08(]message[=:]\\s*["']?([^"')]+)""").find(actionText)?.groupValues?.getOrNull(1) ?: "\u4efb\u52a1\u7ed3\u675f"
            return LocateResult(false, errorReason = msg)
        }

        val action = Regex("""action\\s*[=:]\\s*["']?(\\w+(?:\\s*\\w+)?)""").find(actionText)?.groupValues?.getOrNull(1) ?: "Tap"
        val elem = Regex("""element\\s*[=:]\\s*\\[(\\d+)\\s*[,\uff0c]\\s*(\\d+)]""").find(actionText)
        val ex = elem?.groupValues?.get(1)?.toIntOrNull() ?: 500
        val ey = elem?.groupValues?.get(2)?.toIntOrNull() ?: 500
        val txt = Regex("""text\\s*[=:]\\s*["']([^"']+)""").find(actionText)?.groupValues?.getOrNull(1) ?: ""
        val swStart = Regex("""start\\s*[=:]\\s*\\[(\\d+)\\s*[,\uff0c]\\s*(\\d+)]""").find(actionText)
        val swEnd = Regex("""end\\s*[=:]\\s*\\[(\\d+)\\s*[,\uff0c]\\s*(\\d+)]""").find(actionText)
        val dur = Regex("""duration\\s*[=:]\\s*(\\d+)""").find(actionText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val pkg = Regex("""(?:package|app)\\s*[=:]\\s*["']?([^"',)]+)""").find(actionText)?.groupValues?.getOrNull(1) ?: ""

        val think = actionText.substringBefore("do(", actionText.substringBefore("finish", "").take(200))

        return when (action.trim()) {
            "Tap", "Double Tap" ->
                LocateResult(true, normX(ex), normY(ey), action, thinking = think)
            "Long Press", "LongPress" ->
                LocateResult(true, normX(ex), normY(ey), "LongPress", duration = dur, thinking = think)
            "Type", "Type_Name", "TypeName" ->
                LocateResult(true, normX(ex), normY(ey), "Type", text = txt, thinking = think)
            "Swipe" -> {
                val sxe = swStart?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val sye = swStart?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val exe = swEnd?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val eye = swEnd?.groupValues?.get(2)?.toIntOrNull() ?: 0
                LocateResult(true, ex, ey, action, thinking = think,
                    startX = normX(sxe), startY = normY(sye), endX = normX(exe), endY = normY(eye), duration = dur)
            }
            "Launch" ->
                LocateResult(true, action = "Launch", packageName = pkg.ifBlank { txt }, thinking = think)
            "Wait" ->
                LocateResult(true, action = "Wait", duration = if (dur > 0) dur else 1500, thinking = think)
            "Back" -> LocateResult(true, action = "Back", thinking = think)
            "Home" -> LocateResult(true, action = "Home", thinking = think)
            else -> LocateResult(false, errorReason = "未知动作: $action (${content.take(80)})")
        }
    }

    /** 快速探测 */suspend fun probe(ctx: Context, settings: UserSettings): String = withContext(Dispatchers.IO) {
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
