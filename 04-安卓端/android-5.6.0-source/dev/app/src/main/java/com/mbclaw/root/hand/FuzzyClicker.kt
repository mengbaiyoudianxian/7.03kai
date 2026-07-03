package com.mbclaw.dev.hand

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 快速模糊点击 — 关键词匹配 + UI元素特征检测
 *
 * 秒级响应常见操作，不经过多模态大模型
 */
class FuzzyClicker {

    data class FuzzyResult(
        val keyword: String,
        val bounds: Rect,              // 边界框 (像素坐标), 0-size表示仅关键词匹配无坐标
        val confidence: Float,
        val method: String,            // "keyword" | "pattern" | "historical"
        val positionKnown: Boolean = false,  // true=有坐标, false=仅关键词命中
    )

    // 内置关键词库
    private val keywordLibrary = mutableMapOf<String, List<String>>().apply {
        put("确认", listOf("确认", "确定", "OK", "好的", "知道了", "同意", "允许", "了解"))
        put("取消", listOf("取消", "关闭", "CLOSE", "取消关注", "不感兴趣", "跳过", "忽略"))
        put("发送", listOf("发送", "发布", "评论", "提交", "完成", "下一步", "继续", "保存"))
        put("返回", listOf("返回", "退出", "关闭", "最小化", "取消", "返回键"))
        put("删除", listOf("删除", "移除", "清除", "卸载", "删除好友", "踢出"))
        put("设置", listOf("设置", "更多", "管理", "编辑", "修改", "详情"))
        put("搜索", listOf("搜索", "查找", "筛选", "检索"))
        put("分享", listOf("分享", "转发", "复制链接", "导出"))
        put("点赞", listOf("点赞", "喜欢", "收藏", "关注", "赞"))
        put("播放", listOf("播放", "暂停", "停止", "下一个", "上一个"))
        put("下载", listOf("下载", "保存", "另存为", "导出", "缓存"))
        put("登录", listOf("登录", "注册", "绑定", "授权", "验证"))
    }

    /** 尝试匹配关键词 — 仅表示"知道要找什么"，无坐标 */
    fun matchKeyword(operationDesc: String): FuzzyResult? {
        val desc = operationDesc.lowercase().trim()
        for ((category, keywords) in keywordLibrary) {
            for (kw in keywords) {
                if (desc.contains(kw.lowercase())) {
                    return FuzzyResult(
                        keyword = kw, bounds = Rect(0, 0, 0, 0),
                        confidence = 0.6f, method = "keyword",
                        positionKnown = false  // ★ BugB修复: 关键词匹配没有坐标
                    )
                }
            }
        }
        return null
    }

    /** 基于历史经验匹配 — 有真实坐标 */
    fun matchHistorical(screenSignature: String, operationDesc: String, memory: OperationMemory): FuzzyResult? {
        val pastSuccess = memory.findSimilar(screenSignature, operationDesc)
        if (pastSuccess != null && pastSuccess.confidence >= 0.8f) {
            return FuzzyResult(
                keyword = pastSuccess.operationDesc,
                bounds = Rect(pastSuccess.x - 10, pastSuccess.y - 10, pastSuccess.x + 10, pastSuccess.y + 10),
                confidence = pastSuccess.confidence,
                method = "historical",
                positionKnown = true  // ★ 历史记忆有真实坐标
            )
        }
        return null
    }

    /** 扩充关键词库 (自动学习) */
    fun learnKeyword(operationDesc: String) {
        val words = operationDesc.trim().split(Regex("[的到一个]")).map { it.trim() }.filter { it.length >= 2 }
        for (word in words) {
            val existing = keywordLibrary.entries.find { it.value.contains(word) }
            if (existing == null) {
                keywordLibrary[word] = mutableListOf(word)
            }
        }
    }

    /** 手动添加自定义关键词 */
    fun addCustomKeyword(category: String, keywords: List<String>) {
        keywordLibrary[category] = (keywordLibrary[category] ?: emptyList()) + keywords
    }

    fun getKeywordLibrary(): Map<String, List<String>> = keywordLibrary.toMap()
}
