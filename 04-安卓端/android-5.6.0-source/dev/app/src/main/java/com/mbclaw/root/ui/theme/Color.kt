package com.mbclaw.dev.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────
// MBclaw v3.8 调色
//
// 浅色 (默认): 白 + 天蓝
//   • 背景 #F7FAFE 略带蓝调的白
//   • 卡片纯白 (FFFFFF)
//   • 主色天蓝 #4A90E2
//   • 用户气泡 浅天蓝 #E3F1FF
//   • AI 气泡 浅灰白 #F2F4F8
//
// 暗色: 深蓝 + 流动曲线
//   • 背景深蓝 #0A1428 (午夜蓝)
//   • 卡片 #142037
//   • 二级表面 #1C2B47
//   • 主色亮蓝 #5EA8FF
//   • 用户气泡 深蓝 #1E3A5F
//   • AI 气泡 深灰蓝 #1C2B47
//   • 装饰用流动曲线 (背景层)
// ─────────────────────────────────────────────────

// 品牌色 (跨主题)
val MBclawSkyBlue   = Color(0xFF4A90E2)        // 浅色主色 — 天蓝
val MBclawAzure     = Color(0xFF5EA8FF)        // 暗色主色 — 亮蓝
val MBclawAccent    = Color(0xFFFF8A3D)        // 强调色 — 暖橙 (用在按钮 hover/danger 才用)
val MBclawGreen     = Color(0xFF34C759)
val MBclawRed       = Color(0xFFFF3B30)

// ──── 浅色 (强化白) ──────────────────────────────
val LightBackground    = Color(0xFFFCFEFF)     // 全局底 — 几乎纯白(微微泛蓝)
val LightSurface       = Color(0xFFFFFFFF)     // 卡片 100% 白
val LightSurfaceVar    = Color(0xFFF6F9FC)     // 二级卡片 / AI 气泡 (更白)
val LightSurfaceTint   = Color(0xFFEDF4FB)     // 三级 (浅天蓝)
val LightBorder        = Color(0xFFEAF0F7)     // 分隔线更浅
val LightText          = Color(0xFF14202F)     // 主文字 (略加深增对比)
val LightTextMuted     = Color(0xFF7A8794)     // 副文字
val LightUserBubble    = Color(0xFFDCEAFF)     // 用户气泡 (略饱和)

// ──── 暗色 (深蓝海洋) ─────────────────────────────
val DarkBackground     = Color(0xFF0A1428)     // 深午夜蓝
val DarkSurface        = Color(0xFF142037)     // 卡片
val DarkSurfaceVariant = Color(0xFF1C2B47)     // 二级卡片 / AI 气泡
val DarkSurfaceTint    = Color(0xFF243859)     // 三级
val DarkBorder         = Color(0xFF2C3E60)
val DarkText           = Color(0xFFEAEEF5)
val DarkTextMuted      = Color(0xFFA8B2C3)     // 加深，提高对比
val DarkUserBubble     = Color(0xFF1E3A5F)

// 流动曲线 (暗色背景装饰用 - 半透明蓝)
val DarkFlow1          = Color(0x405EA8FF)
val DarkFlow2          = Color(0x20FFFFFF)
