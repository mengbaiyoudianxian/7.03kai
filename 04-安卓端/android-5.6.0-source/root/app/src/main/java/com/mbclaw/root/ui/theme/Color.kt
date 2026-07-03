package com.mbclaw.root.ui.theme

import androidx.compose.ui.graphics.Color

// ChatGPT 风格配色 — 极简黑白灰 + 蓝色点缀 + 红色仅危险操作
// 废除: 橙色强调 / 黄色警告 / 绿色对勾emoji

val C_Primary    = Color(0xFF1A1A1A)  // 主文字/按钮 纯黑
val C_Blue       = Color(0xFF3B82F6)  // 唯一强调色 蓝
val C_Red        = Color(0xFFEF4444)  // 仅删除/危险操作
val C_Bg         = Color(0xFFFFFFFF)  // 纯白背景
val C_Surface    = Color(0xFFF9FAFB)  // 卡片/列表浅灰
val C_Border     = Color(0xFFE5E7EB)  // 分割线
val C_Text       = Color(0xFF111827)  // 主文字 近乎黑
val C_Muted      = Color(0xFF6B7280)  // 次级文字
val C_Faint      = Color(0xFFD1D5DB)  // 占位/禁用

// 暗色
val CD_Bg        = Color(0xFF0F0F0F)
val CD_Surface   = Color(0xFF1A1A1A)
val CD_Border    = Color(0xFF2A2A2A)
val CD_Text      = Color(0xFFF5F5F5)
val CD_Muted     = Color(0xFF9CA3AF)

// 兼容旧代码别名
val MBclawSkyBlue = C_Blue
val MBclawAzure   = C_Blue
val MBclawAccent  = C_Blue
val MBclawGreen   = Color(0xFF22C55E)
val MBclawRed     = C_Red

val LightBackground  = C_Bg
val LightSurface     = Color(0xFFFFFFFF)
val LightSurfaceVar  = C_Surface
val LightSurfaceTint = Color(0xFFF3F4F6)
val LightBorder      = C_Border
val LightText        = C_Text
val LightTextMuted   = C_Muted
val LightUserBubble  = Color(0xFFEFF6FF)

val DarkBackground   = CD_Bg
val DarkSurface      = CD_Surface
val DarkSurfaceVariant = Color(0xFF1F1F1F)
val DarkSurfaceTint   = Color(0xFF272727)
val DarkBorder       = CD_Border
val DarkText         = CD_Text
val DarkTextMuted    = CD_Muted
val DarkUserBubble   = Color(0xFF1E293B)

val DarkFlow1 = Color(0x103B82F6)
val DarkFlow2 = Color(0x10FFFFFF)
