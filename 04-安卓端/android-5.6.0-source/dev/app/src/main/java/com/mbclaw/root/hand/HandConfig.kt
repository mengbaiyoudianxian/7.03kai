package com.mbclaw.dev.hand

/**
 * 智能体之手 — 参数体系
 *
 * 三种预设模式:
 *   SPEED   — 极速: 网格3×4, 0轮精定位, 模糊点击优先
 *   BALANCE — 均衡(默认): 网格4×6, 1轮精定位, 模糊辅助
 *   PRECISE — 高精度: 网格6×8, 2轮精定位, 模糊关闭
 */

enum class HandMode { SPEED, BALANCE, PRECISE }

data class HandConfig(
    // 粗筛网格密度
    var coarseGridCols: Int = 4,
    var coarseGridRows: Int = 6,
    // 精定位轮次 (0=只用粗筛)
    var fineRounds: Int = 1,
    // 快速通道阈值: 低于此置信度不走快速通道
    var fuzzyThreshold: Float = 0.85f,
    // 执行置信度下限: 低于此值不执行
    var minConfidence: Float = 0.75f,
    // 是否启用模糊点击
    var fuzzyEnabled: Boolean = true,
    // 是否自动校准
    var autoCalibrate: Boolean = true,
    // 点击失败最大重试次数
    var maxRetries: Int = 2,
    // 重试偏移量 (像素)
    var retryOffsetPx: Int = 10,
) {
    companion object {
        /** 极速模式 */
        fun speed() = HandConfig(
            coarseGridCols = 3, coarseGridRows = 4,
            fineRounds = 0, fuzzyThreshold = 0.7f,
            minConfidence = 0.7f, fuzzyEnabled = true,
        )
        /** 均衡模式 (默认) */
        fun balance() = HandConfig(
            coarseGridCols = 4, coarseGridRows = 6,
            fineRounds = 1, fuzzyThreshold = 0.85f,
            minConfidence = 0.75f, fuzzyEnabled = true,
        )
        /** 高精度模式 */
        fun precise() = HandConfig(
            coarseGridCols = 6, coarseGridRows = 8,
            fineRounds = 2, fuzzyThreshold = 1.0f,
            minConfidence = 0.85f, fuzzyEnabled = false,
        )
    }
}
