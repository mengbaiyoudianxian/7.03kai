package com.mbclaw.dev.ui.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = MBclawSkyBlue,
    onPrimary = Color.White,
    primaryContainer = LightSurfaceTint,
    onPrimaryContainer = MBclawSkyBlue,
    secondary = MBclawSkyBlue,
    onSecondary = Color.White,
    secondaryContainer = LightUserBubble,
    onSecondaryContainer = Color(0xFF0B3A6A),
    tertiary = MBclawAccent,
    error = MBclawRed,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceVar,
    onSurfaceVariant = LightText,           // bug.3 — AI 气泡用主文字色 (非灰)
    outline = LightTextMuted,
    outlineVariant = LightBorder,
)

private val DarkColorScheme = darkColorScheme(
    primary = MBclawAzure,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = MBclawAzure,
    secondary = MBclawAzure,
    onSecondary = Color.White,
    secondaryContainer = DarkUserBubble,
    onSecondaryContainer = Color(0xFFD8E5FF),
    tertiary = MBclawAccent,
    error = MBclawRed,
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkText,
    outline = DarkTextMuted,
    outlineVariant = DarkBorder,
)

@Composable
fun MBclawTheme(
    darkTheme: Boolean = isSystemDark(),   // 默认跟系统; 系统浅色就浅
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme) {
        if (darkTheme) {
            // 暗色背景叠流动曲线
            Box(Modifier.fillMaxSize().background(colorScheme.background)) {
                FlowingBackground()
                content()
            }
        } else {
            content()
        }
    }
}

/** 流动曲线背景 (任务 1) — 用 Canvas 画两条对角线渐变曲线 */
@Composable
private fun FlowingBackground() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        // 曲线 1: 左上 → 右下
        val p1 = Path().apply {
            moveTo(0f, h * 0.2f)
            cubicTo(w * 0.4f, h * 0.4f, w * 0.6f, h * 0.5f, w, h * 0.85f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(p1, brush = Brush.verticalGradient(
            colors = listOf(DarkFlow1, Color.Transparent),
            startY = h * 0.2f, endY = h,
        ))
        // 曲线 2: 右上 → 中
        val p2 = Path().apply {
            moveTo(w, h * 0.05f)
            cubicTo(w * 0.6f, h * 0.25f, w * 0.4f, h * 0.45f, 0f, h * 0.55f)
            lineTo(0f, 0f); lineTo(w, 0f); close()
        }
        drawPath(p2, brush = Brush.verticalGradient(
            colors = listOf(DarkFlow2, Color.Transparent),
            startY = 0f, endY = h * 0.55f,
        ))
        // 高光点
        drawCircle(
            color = DarkFlow1,
            radius = w * 0.18f,
            center = Offset(w * 0.85f, h * 0.15f),
            alpha = 0.18f,
        )
        drawCircle(
            color = DarkFlow1,
            radius = w * 0.22f,
            center = Offset(w * 0.1f, h * 0.8f),
            alpha = 0.12f,
        )
    }
}

@Composable
private fun isSystemDark(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return ThemePreference.isDark(ctx)
}

/** 主题偏好 — light / dark / system 三选, 用 mutableStateOf 让 Compose 立即重组 */
object ThemePreference {
    private const val PREF = "mb_theme"
    private const val KEY = "mode"

    // 观察用 State, 改完即时切换
    @Volatile var currentMode: androidx.compose.runtime.MutableState<String>? = null

    fun ensureInit(ctx: android.content.Context) {
        if (currentMode == null) {
            val saved = ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                .getString(KEY, "light") ?: "light"
            currentMode = androidx.compose.runtime.mutableStateOf(saved)
        }
    }

    fun mode(ctx: android.content.Context): String {
        ensureInit(ctx)
        return currentMode!!.value
    }

    fun setMode(ctx: android.content.Context, mode: String) {
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).apply()
        ensureInit(ctx)
        currentMode!!.value = mode
    }

    fun isDark(ctx: android.content.Context): Boolean = when (mode(ctx)) {
        "dark" -> true
        "light" -> false
        else -> {
            val ui = ctx.resources.configuration.uiMode and
                     android.content.res.Configuration.UI_MODE_NIGHT_MASK
            ui == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
}
