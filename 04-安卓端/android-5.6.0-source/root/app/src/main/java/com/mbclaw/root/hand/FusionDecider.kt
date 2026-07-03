package com.mbclaw.root.hand

import android.graphics.Point
import kotlin.math.abs

/**
 * FusionDecider — 多通道决策融合 (v4.7 简化)
 *
 * 现在只有两个通道:
 *   通道1: FuzzyClicker (关键词/历史)
 *   通道2: VisionLocator (VLM 看图)
 *
 * 没有 grid-based coarse/fine (已废弃)
 */
class FusionDecider(
    private val config: HandConfig,
    private val screenWidth: Int,
    private val screenHeight: Int,
) {

    data class ChannelResult(
        val normalizedX: Int, val normalizedY: Int,
        val confidence: Float, val method: String,
    )

    data class FinalDecision(
        val normalizedX: Int, val normalizedY: Int,
        val confidence: Float, val method: String,
        val executed: Boolean,
        val reason: String = "",
    )

    /**
     * 融合 VLM + Fuzzy + Memory 通道
     */
    fun decide(
        vlmResult: BlockRecognizer.RecognizedTarget?,
        fuzzy: FuzzyClicker.FuzzyResult?,
        history: OperationMemory.OpRecord?,
    ): FinalDecision {
        val channels = mutableListOf<ChannelResult>()

        // VLM 通道 (最高权重)
        if (vlmResult != null && vlmResult.confidence >= config.minConfidence) {
            channels.add(ChannelResult(
                vlmResult.normalizedX, vlmResult.normalizedY,
                vlmResult.confidence, "vlm"
            ))
        }

        // 历史记忆通道
        if (history != null && history.confidence >= 0.8f) {
            channels.add(ChannelResult(
                history.x, history.y, history.confidence, "memory"
            ))
        }

        // 模糊通道 (低权重)
        if (config.fuzzyEnabled && fuzzy != null && fuzzy.confidence >= config.fuzzyThreshold) {
            channels.add(ChannelResult(
                fuzzy.bounds.centerX(), fuzzy.bounds.centerY(),
                fuzzy.confidence * 0.7f, "fuzzy"
            ))
        }

        if (channels.isEmpty()) {
            return FinalDecision(0, 0, 0f, "none", false, "无可用通道")
        }

        // VLM 直接命中 → 直接执行
        val vlm = channels.find { it.method == "vlm" }
        if (vlm != null && vlm.confidence >= 0.6f) {
            return FinalDecision(vlm.normalizedX, vlm.normalizedY, vlm.confidence, "vlm", true, "VLM 视觉定位")
        }

        // Memory 高置信度 → 直接执行
        val mem = channels.find { it.method == "memory" }
        if (mem != null && mem.confidence >= 0.8f) {
            return FinalDecision(mem.normalizedX, mem.normalizedY, mem.confidence, "memory", true, "历史记忆命中")
        }

        // 检测 VLM vs Memory 冲突
        if (vlm != null && mem != null) {
            val dx = abs(vlm.normalizedX - mem.normalizedX)
            val dy = abs(vlm.normalizedY - mem.normalizedY)
            val threshold = (screenWidth * 0.1).toInt()
            if (dx > threshold || dy > threshold) {
                // 冲突: 信任 VLM
                return FinalDecision(vlm.normalizedX, vlm.normalizedY, vlm.confidence, "vlm", true, "VLM优先 (与记忆冲突)")
            }
        }

        // 取最高置信度
        val best = channels.maxByOrNull { it.confidence }
            ?: return FinalDecision(0, 0, 0f, "none", false, "无可用定位通道")
        if (best.confidence >= config.minConfidence) {
            return FinalDecision(best.normalizedX, best.normalizedY, best.confidence, best.method, true, "最高置信度")
        }

        return FinalDecision(best.normalizedX, best.normalizedY, best.confidence, best.method, false,
            "置信度不足 ${"%.1f".format(best.confidence)} < ${config.minConfidence}")
    }
}
