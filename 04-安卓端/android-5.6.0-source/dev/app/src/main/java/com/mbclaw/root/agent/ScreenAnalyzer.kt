package com.mbclaw.dev.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mbclaw.dev.service.MBclawAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ScreenAnalyzer — 通道1: uiautomator dump + 无障碍 双源融合
 *
 * v4.7 修复: XML 解析改为逐字符状态机, 正确处理嵌套 <node> 元素
 *
 * 输出: 每个可交互元素打标号 [1], [2], [3]...
 * LLM 选数字 → click_by_index(N) → TouchInjector.tap()
 */
object ScreenAnalyzer {

    data class UIElement(
        val index: Int,
        val text: String,
        val clazz: String,
        val bounds: IntArray,      // [l, t, r, b]
        val clickable: Boolean,
        val source: String,        // "uia" | "acc"
    ) {
        val centerX: Int get() = (bounds[0] + bounds[2]) / 2
        val centerY: Int get() = (bounds[1] + bounds[3]) / 2
        val width: Int get() = bounds[2] - bounds[0]
        val height: Int get() = bounds[3] - bounds[1]

        fun summary(): String {
            val type = when {
                clazz.contains("EditText") || clazz.contains("Edit") -> "📝输入框"
                clazz.contains("Button") || clickable -> "🔘按钮"
                clazz.contains("TextView") || clazz.contains("Text") -> "📄文本"
                clazz.contains("ImageView") || clazz.contains("Image") -> "🖼图标"
                clazz.contains("CheckBox") || clazz.contains("Switch") -> "🔲开关"
                clazz.contains("ListView") || clazz.contains("Recycler") -> "📋列表"
                clazz.contains("WebView") -> "🌐网页"
                else -> "·节点"
            }
            val txt = if (text.isBlank()) "(无文字)" else text.take(20)
            return "[$index] $type $txt @(${centerX},${centerY})"
        }
    }

    @Volatile private var lastSnapshot: List<UIElement> = emptyList()
    fun getCachedElement(index: Int): UIElement? = lastSnapshot.find { it.index == index }

    /** 主入口: 全屏快照 — uiautomator dump 优先, 无障碍兜底 */
    suspend fun snapshot(ctx: Context): List<UIElement> = withContext(Dispatchers.IO) {
        val tier = PermissionTier.get(ctx)
        val elements = mutableListOf<UIElement>()

        // 方法 A: uiautomator dump (root) — 修复版 XML 解析
        if (tier.hasRoot) {
            try {
                val xmlPath = "/sdcard/mb_ui_${System.currentTimeMillis()}.xml"
                val out = tier.shellRoot(
                    "uiautomator dump $xmlPath >/dev/null 2>&1 && cat $xmlPath && rm $xmlPath",
                    timeoutMs = 15000
                )
                if (out != null && out.length > 100) {
                    val parsed = parseUiAutomatorXml(out, "uia")
                    if (parsed.isNotEmpty()) {
                        elements.addAll(parsed)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MBclaw-Eye", "uia dump 失败: ${e.message}")
            }
        }

        // 方法 B: 无障碍 dump (当 A 为空时兜底)
        if (elements.isEmpty()) {
            try {
                val svc = MBclawAccessibilityService.instance
                if (svc != null) {
                    elements.addAll(collectAccessibilityNodes(svc))
                }
            } catch (e: Exception) {
                android.util.Log.w("MBclaw-Eye", "acc dump 失败: ${e.message}")
            }
        }

        // 过滤 + 去重 + 打标号
        val cleaned = elements
            .filter { it.bounds[2] > it.bounds[0] && it.bounds[3] > it.bounds[1] }
            .distinctBy { "${it.bounds[0]}_${it.bounds[1]}_${it.clazz}_${it.text}" }
            .mapIndexed { i, e -> e.copy(index = i + 1) }

        lastSnapshot = cleaned
        cleaned
    }

    /**
     * ★ v4.7: 重写 XML 解析 — 逐行状态机
     *
     * uiautomator dump 格式:
     *   <node index="0" text="微信" class="android.widget.TextView" bounds="[0,100][200,150]" clickable="false"/>
     *   <node index="1" text="搜索" class="android.widget.EditText" bounds="[50,200][400,260]" clickable="true">
     *     <node ... />
     *   </node>
     *
     * 策略: 每行独立匹配属性, 自闭合和闭合标签都正确提取
     */
    private fun parseUiAutomatorXml(xml: String, source: String): List<UIElement> {
        val list = mutableListOf<UIElement>()
        // 按 <node 分割, 每个片段就是一个 node 的开头
        val segments = xml.split("<node ")
        for (seg in segments) {
            if (seg.isBlank()) continue
            // 提取属性: 取到第一个 > 之前的部分 (属性区)
            val attrEnd = seg.indexOf('>')
            val attrs = if (attrEnd > 0) seg.substring(0, attrEnd) else seg

            // 解析各个属性
            val text = extractAttr(attrs, "text")
            val desc = extractAttr(attrs, "content-desc")
            val clazz = extractAttr(attrs, "class")
            val clickable = extractAttr(attrs, "clickable") == "true"
            val boundsStr = extractAttr(attrs, "bounds")

            if (boundsStr.isBlank()) continue

            // 解析 bounds: "[l,t][r,b]"
            val boundsMatch = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(boundsStr)
                ?: continue
            val (l, t, r, b) = boundsMatch.destructured
            val bounds = intArrayOf(l.toInt(), t.toInt(), r.toInt(), b.toInt())

            // 无效尺寸跳过
            if (bounds[2] <= bounds[0] || bounds[3] <= bounds[1]) continue

            val displayText = text.ifBlank { desc }
            // 跳过纯装饰性容器 (无文字且不可点且不是输入框)
            val isInput = clazz.contains("Edit", ignoreCase = true) ||
                          clazz.contains("Input", ignoreCase = true)
            if (displayText.isBlank() && !clickable && !isInput) continue

            list.add(UIElement(
                index = 0,
                text = displayText,
                clazz = clazz.substringAfterLast('.'),
                bounds = bounds,
                clickable = clickable,
                source = source,
            ))
        }
        return list
    }

    /** 从属性串中提取值: text="微信" → "微信" */
    private fun extractAttr(attrs: String, name: String): String {
        // 匹配 name="value" 或 name='value'
        val re = Regex("""$name\s*=\s*"([^"]*)"""" )
        re.find(attrs)?.groupValues?.getOrNull(1)?.let { return it }
        // 单引号版本
        val re2 = Regex("""$name\s*=\s*'([^']*)'""")
        re2.find(attrs)?.groupValues?.getOrNull(1)?.let { return it }
        return ""
    }

    // ── 无障碍兜底 ──

    private fun collectAccessibilityNodes(svc: MBclawAccessibilityService): List<UIElement> {
        val list = mutableListOf<UIElement>()
        try {
            val root = svc.rootInActiveWindow ?: return list
            collectNode(root, list)
        } catch (_: Exception) {}
        return list
    }

    private fun collectNode(node: android.view.accessibility.AccessibilityNodeInfo?, out: MutableList<UIElement>) {
        if (node == null) return
        try {
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            val clazz = (node.className?.toString() ?: "").substringAfterLast('.')
            if (text.isNotBlank() || node.isClickable) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    out.add(UIElement(
                        index = 0, text = text, clazz = clazz,
                        bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
                        clickable = node.isClickable, source = "acc",
                    ))
                }
            }
            for (i in 0 until node.childCount) {
                collectNode(node.getChild(i), out)
            }
        } catch (_: Exception) {}
    }

    /** 格式化为 LLM 友好的文本 */
    fun formatForLLM(elements: List<UIElement>, max: Int = 50): String {
        if (elements.isEmpty()) return "❌ 无法识别屏幕元素 (需要 Root 或无障碍权限)"
        val pickable = elements.filter { it.text.isNotBlank() || it.clickable }.take(max)
        val sb = StringBuilder()
        sb.appendLine("📱 当前屏幕共 ${elements.size} 个元素 (显示前 ${pickable.size} 个可交互)")
        sb.appendLine("─────────────────────────────────")
        pickable.forEach { sb.appendLine(it.summary()) }
        sb.appendLine("─────────────────────────────────")
        sb.appendLine("💡 可调用 click_by_index(数字) 直接点对应元素, 不必猜坐标")
        return sb.toString()
    }
}
