package com.mbclaw.nonroot.hand

import android.content.Context
import android.graphics.Point
import com.mbclaw.nonroot.agent.VisionLocator
import com.mbclaw.nonroot.data.UserSettings

/**
 * BlockRecognizer — 视觉定位通道选择器 (v4.7 重写)
 *
 * 双通道架构:
 *   通道1 (快速): 关键词匹配 → 返回 FuzzyClicker 结果 (kw→已知坐标)
 *   通道2 (精确): VisionLocator → 截图发给 VLM → VLM 返回坐标
 *
 * 通道选择规则:
 *   - 有视觉模型配置 → 优先走通道2 (VLM 真看)
 *   - 无视觉模型配置 → 通道1 (关键词/历史兜底)
 *   - 通道2 失败 → 降级到通道1
 *
 * 注意: 之前的 grid-based coarse/fine 管线已废弃。
 * Open-AutoGLM 证明: 直接让 VLM 看全图比网格定位更准、更快。
 */
class BlockRecognizer(
    private val context: Context,
    private val settings: UserSettings,
    private val config: HandConfig,
    private val fuzzyClicker: FuzzyClicker,
    private val operationMemory: OperationMemory,
) {

    data class RecognizedTarget(
        val normalizedX: Int, val normalizedY: Int,
        val confidence: Float, val method: String,
    )

    // ── 屏幕尺寸缓存 ──
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0

    private fun ensureScreenSize() {
        if (screenW > 0 && screenH > 0) return
        val tier = com.mbclaw.nonroot.agent.PermissionTier.get(context)
        if (tier.hasRoot) {
            val wm = tier.shellRoot("wm size 2>/dev/null") ?: ""
            val m = Regex("""(\d+)x(\d+)""").find(wm)
            if (m != null) {
                screenW = m.groupValues[1].toIntOrNull() ?: 1080
                screenH = m.groupValues[2].toIntOrNull() ?: 2400
            }
        }
        if (screenW == 0) {
            val dm = context.resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
    }

    /**
     * 全屏直接定位 — VLM 看图 → 坐标
     *
     * 这是目前最准确的方法 (参考 Open-AutoGLM)
     */
    suspend fun fullScreenLocate(
        screenshotBase64: String,
        operationDesc: String,
    ): RecognizedTarget? {
        ensureScreenSize()

        // 尝试 VLM 通道
        if (settings.visionEnabled && settings.visionApiKey.isNotBlank()) {
            try {
                val result = VisionLocator.locate(context, settings, operationDesc)
                if (result.success && result.action == "Tap") {
                    // 物理坐标 → 归一化
                    val nx = (result.x.toFloat() / screenW * 1000).toInt()
                    val ny = (result.y.toFloat() / screenH * 1000).toInt()
                    return RecognizedTarget(
                        normalizedX = nx.coerceIn(0, 1000),
                        normalizedY = ny.coerceIn(0, 1000),
                        confidence = result.confidence,
                        method = "vlm_vision",
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("MBclaw-Block", "VLM 失败, 降级: ${e.message}")
            }
        }

        // 降级: 关键词匹配
        val fuzzy = fuzzyClicker.matchKeyword(operationDesc)
        if (fuzzy != null && fuzzy.confidence >= 0.5f) {
            // 关键词命中了, 但没有坐标信息 (Rect=0)
            // 尝试历史记忆
            val hist = operationMemory.findSimilar("fullscreen", operationDesc)
            if (hist != null && hist.confidence >= 0.7f) {
                return RecognizedTarget(hist.x, hist.y, hist.confidence, "memory_fallback")
            }
        }

        return null
    }

    /**
     * 粗筛阶段 — 已废弃 (VLM 直接看全图更准)
     * 保留接口兼容性, 内部直接走全屏定位
     */
    @Deprecated("Use fullScreenLocate instead", ReplaceWith("fullScreenLocate(screenshotBase64, operationDesc)"))
    suspend fun coarseLocate(
        screenshotBase64: String,
        operationDesc: String,
    ): List<RecognizedTarget> {
        val r = fullScreenLocate(screenshotBase64, operationDesc)
        return if (r != null) listOf(r) else emptyList()
    }

    /**
     * 精定位阶段 — 已废弃
     */
    @Deprecated("Use fullScreenLocate instead")
    suspend fun fineLocate(
        screenshotBase64: String,
        operationDesc: String,
        candidateRegions: List<Any>,
        rounds: Int = 1,
    ): List<RecognizedTarget> {
        val r = fullScreenLocate(screenshotBase64, operationDesc)
        return if (r != null) listOf(r) else emptyList()
    }
}
