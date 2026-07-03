package com.mbclaw.nonroot.data

/**
 * VisionPresets — 视觉模型锁定预设
 *
 * Root 模式: 二选一
 *   • DOUBAO  (火山引擎豆包视觉, 性价比第一)
 *   • AUTOGLM (智谱 AutoGLM-Phone, 专业手机视觉)
 *
 * 非 Root 模式: 锁死智谱 AUTOGLM
 *   (不允许选火山, 因为非 root 无法保证调用环境)
 */

data class VisionPreset(
    val id: String,
    val name: String,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val note: String,
    val allowNonRoot: Boolean,
)

object VisionPresets {

    val DOUBAO = VisionPreset(
        id = "doubao-seed-vision",
        name = "豆包 (火山引擎)",
        displayName = "🔥 豆包视觉 (性价比第一)",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        model = "doubao-seed-2-0-lite-260428",
        note = "火山引擎 · 字节系 · 价格便宜 · 仅 Root 模式可选",
        allowNonRoot = false,
    )

    val AUTOGLM = VisionPreset(
        id = "autoglm-phone",
        name = "智谱 AutoGLM-Phone",
        displayName = "🎯 智谱 AutoGLM-Phone (专业手机视觉)",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "autoglm-phone",
        note = "智谱清华系 · 专为手机 UI 识别优化 · 全场景可选",
        allowNonRoot = true,
    )

    fun forRoot(): List<VisionPreset> = listOf(DOUBAO, AUTOGLM)
    fun forNonRoot(): List<VisionPreset> = listOf(AUTOGLM)
    fun all(): List<VisionPreset> = listOf(DOUBAO, AUTOGLM)
    fun byId(id: String): VisionPreset? = listOf(DOUBAO, AUTOGLM).find { it.id == id }
}
