package com.mbclaw.nonroot.hand

import android.content.Context
import android.graphics.Point
import com.mbclaw.nonroot.data.UserSettings
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * 智能体之手 — 主编排器 (v4.7 双通道版)
 *
 * 通道1 (快速): 关键词 + 历史记忆 → FuzzyClicker
 * 通道2 (精确): 截图 → VisionLocator → VLM 看图给坐标
 *
 * 工作流:
 *   输入: (截图base64, 操作描述)
 *     → 检查DNA记忆 → 关键词匹配 → VLM视觉定位 → 执行 → 验证 → 记录
 *   输出: HandOutput
 */
class AgentHand(
    private val context: Context,
    private val settings: UserSettings,
    private val mode: HandMode = HandMode.BALANCE,
    private val executor: (Point) -> Boolean,
) {
    private val config = when (mode) {
        HandMode.SPEED -> HandConfig.speed()
        HandMode.BALANCE -> HandConfig.balance()
        HandMode.PRECISE -> HandConfig.precise()
    }

    val calibration = ScreenCalibration(context)
    private val fuzzyClicker = FuzzyClicker()
    val memory = OperationMemory(context)
    private val blockRecognizer = BlockRecognizer(context, settings, config, fuzzyClicker, memory)

    data class HandInput(
        val screenshotBase64: String,
        val operationDesc: String,
        val packageName: String = "unknown",
        val layoutHash: String = "default",
    )

    data class HandOutput(
        val success: Boolean,
        val operationType: String,
        val normalizedX: Int, val normalizedY: Int,
        val physicalX: Int, val physicalY: Int,
        val confidence: Float,
        val methodUsed: String,
        val duration: Long,
        val errorReason: String = "",
    )

    // ── 主编排 ──

    suspend fun execute(input: HandInput): HandOutput = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val screenSig = memory.screenSignature(
            input.packageName, calibration.screenSize.x, calibration.screenSize.y, input.layoutHash
        )

        try {
            // ═══ STEP 0: DNA 记忆快速命中 ═══
            val historicalOp = memory.findSimilar(screenSig, input.operationDesc)
            if (historicalOp != null && historicalOp.confidence >= 0.85f) {
                val phys = calibration.normalizedToPhysical(historicalOp.x, historicalOp.y)
                val ok = executor(phys)
                val elapsed = System.currentTimeMillis() - startTime
                memory.record(OperationMemory.OpRecord(
                    sessionId = "hand_$startTime", deviceId = calibration.screenSize.toString(),
                    screenSignature = screenSig, operationDesc = input.operationDesc,
                    methodUsed = "memory", x = historicalOp.x, y = historicalOp.y,
                    success = ok, confidence = historicalOp.confidence,
                ))
                return@withContext HandOutput(ok, "tap", historicalOp.x, historicalOp.y,
                    phys.x, phys.y, historicalOp.confidence, "memory", elapsed)
            }

            // ═══ STEP 1: 通道1 — 关键词模糊匹配 ═══
            var fuzzyResult: FuzzyClicker.FuzzyResult? = null
            if (config.fuzzyEnabled) {
                fuzzyResult = fuzzyClicker.matchKeyword(input.operationDesc)
                if (fuzzyResult == null || (fuzzyResult.confidence < config.fuzzyThreshold)) {
                    fuzzyResult = fuzzyClicker.matchHistorical(screenSig, input.operationDesc, memory)
                }
            }

            // 如果关键词高置信度命中且有历史坐标 → 直接执行
            if (fuzzyResult != null && fuzzyResult.confidence >= config.fuzzyThreshold) {
                // 尝试从历史获取坐标
                val hist = memory.findSimilar(screenSig, input.operationDesc)
                if (hist != null && hist.confidence >= 0.8f) {
                    val phys = calibration.normalizedToPhysical(hist.x, hist.y)
                    val ok = executor(phys)
                    val elapsed = System.currentTimeMillis() - startTime
                    return@withContext HandOutput(ok, "tap", hist.x, hist.y,
                        phys.x, phys.y, hist.confidence, "fuzzy_history", elapsed)
                }
            }

            // ═══ STEP 2: 通道2 — VLM 视觉定位 (核心) ═══
            val vlmTarget = blockRecognizer.fullScreenLocate(
                input.screenshotBase64, input.operationDesc
            )

            if (vlmTarget != null && vlmTarget.confidence >= config.minConfidence) {
                val phys = calibration.normalizedToPhysical(vlmTarget.normalizedX, vlmTarget.normalizedY)
                var success = executor(phys)
                var retries = 0

                // 失败时微调重试
                while (!success && retries < config.maxRetries) {
                    val offset = config.retryOffsetPx * (retries + 1)
                    val offsets = listOf(
                        Point(phys.x + offset, phys.y),
                        Point(phys.x - offset, phys.y),
                        Point(phys.x, phys.y + offset),
                        Point(phys.x, phys.y - offset),
                    )
                    for (off in offsets) {
                        success = executor(off)
                        if (success) break
                    }
                    retries++
                }

                val elapsed = System.currentTimeMillis() - startTime
                memory.record(OperationMemory.OpRecord(
                    sessionId = "hand_$startTime", deviceId = calibration.screenSize.toString(),
                    screenSignature = screenSig, operationDesc = input.operationDesc,
                    methodUsed = vlmTarget.method, x = vlmTarget.normalizedX, y = vlmTarget.normalizedY,
                    success = success, confidence = vlmTarget.confidence,
                ))

                if (success) {
                    fuzzyClicker.learnKeyword(input.operationDesc)
                    if (config.autoCalibrate) {
                        calibration.recordDeviation(
                            vlmTarget.normalizedX, vlmTarget.normalizedY, phys.x, phys.y
                        )
                    }
                }

                return@withContext HandOutput(
                    success = success, operationType = "tap",
                    normalizedX = vlmTarget.normalizedX, normalizedY = vlmTarget.normalizedY,
                    physicalX = phys.x, physicalY = phys.y,
                    confidence = vlmTarget.confidence,
                    methodUsed = "${vlmTarget.method}_r$retries",
                    duration = elapsed,
                    errorReason = if (!success) "重试${retries}次后仍失败" else "",
                )
            }

            // ═══ STEP 3: 全失败 ═══
            val elapsed = System.currentTimeMillis() - startTime
            HandOutput(false, "tap", 0, 0, 0, 0, 0f, "all_failed", elapsed,
                if (fuzzyResult != null) "关键词命中但无坐标 (置信度${fuzzyResult.confidence})"
                else "视觉模型未配置或未识别到目标")
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            HandOutput(false, "tap", 0, 0, 0, 0, 0f, "exception", elapsed,
                e.message ?: "未知错误")
        }
    }

    /** 切换模式 */
    fun setMode(newMode: HandMode) {
        val newConfig = when (newMode) {
            HandMode.SPEED -> HandConfig.speed()
            HandMode.BALANCE -> HandConfig.balance()
            HandMode.PRECISE -> HandConfig.precise()
        }
        config.coarseGridCols = newConfig.coarseGridCols
        config.coarseGridRows = newConfig.coarseGridRows
        config.fineRounds = newConfig.fineRounds
        config.fuzzyThreshold = newConfig.fuzzyThreshold
        config.minConfidence = newConfig.minConfidence
        config.fuzzyEnabled = newConfig.fuzzyEnabled
        config.autoCalibrate = newConfig.autoCalibrate
        config.maxRetries = newConfig.maxRetries
    }

    fun getStats(): Map<String, Any> = mapOf(
        "success_rate" to memory.getSuccessRate(),
        "method_stats" to memory.getMethodStats(),
        "screen_calibrated" to calibration.isCalibrated(),
        "screen_size" to "${calibration.screenSize.x}x${calibration.screenSize.y}",
        "keywords_count" to fuzzyClicker.getKeywordLibrary().values.sumOf { it.size },
    )
}
