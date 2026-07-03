package com.mbclaw.nonroot.hand

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 屏幕标定 — 归一化坐标(0-1000) ↔ 物理像素映射
 *
 * 一次校准，永久记忆。自动横竖屏切换。自动微调偏移。
 */
class ScreenCalibration(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mbclaw_calibration", Context.MODE_PRIVATE)

    data class CalibrationPoint(val displayX: Int, val displayY: Int, val physicalX: Int, val physicalY: Int)
    data class CalibrationData(
        val scaleX: Float, val scaleY: Float,
        val offsetX: Float, val offsetY: Float,
        val verified: Boolean = false,
    )

    /** 当前屏幕物理尺寸 */
    val screenSize: Point by lazy {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        Point(metrics.widthPixels, metrics.heightPixels)
    }

    /** 归一化坐标 → 物理像素 */
    fun normalizedToPhysical(nx: Int, ny: Int): Point {
        val data = loadData() ?: return Point(
            (nx / 1000f * screenSize.x).roundToInt(),
            (ny / 1000f * screenSize.y).roundToInt(),
        )
        val px = (nx / 1000f * screenSize.x * data.scaleX + data.offsetX).roundToInt()
        val py = (ny / 1000f * screenSize.y * data.scaleY + data.offsetY).roundToInt()
        return Point(
            px.coerceIn(0, screenSize.x),
            py.coerceIn(0, screenSize.y),
        )
    }

    /** 物理像素 → 归一化坐标 */
    fun physicalToNormalized(px: Int, py: Int): Point {
        val data = loadData() ?: return Point(
            (px.toFloat() / screenSize.x * 1000).roundToInt(),
            (py.toFloat() / screenSize.y * 1000).roundToInt(),
        )
        val nx = ((px - data.offsetX) / (screenSize.x * data.scaleX) * 1000).roundToInt()
        val ny = ((py - data.offsetY) / (screenSize.y * data.scaleY) * 1000).roundToInt()
        return Point(nx.coerceIn(0, 1000), ny.coerceIn(0, 1000))
    }

    // ── 校准流程 ──

    /** 获取四个标定点的显示坐标 (15% 边距避开圆角) */
    fun getCalibrationPoints(): List<Point> {
        val margin = 0.15f
        return listOf(
            Point((screenSize.x * margin).roundToInt(), (screenSize.y * margin).roundToInt()), // 左上
            Point((screenSize.x * (1 - margin)).roundToInt(), (screenSize.y * margin).roundToInt()), // 右上
            Point((screenSize.x * margin).roundToInt(), (screenSize.y * (1 - margin)).roundToInt()), // 左下
            Point((screenSize.x * (1 - margin)).roundToInt(), (screenSize.y * (1 - margin)).roundToInt()), // 右下
        )
    }

    /** 获取验证点的显示坐标 (随机) */
    fun getVerificationPoint(): Point {
        return Point(
            (screenSize.x * (0.3f + Math.random().toFloat() * 0.4f)).roundToInt(),
            (screenSize.y * (0.3f + Math.random().toFloat() * 0.4f)).roundToInt(),
        )
    }

    /** 计算映射参数 */
    fun computeMapping(displayPoints: List<Point>, physicalPoints: List<Point>): CalibrationData {
        var sumSx = 0f; var sumSy = 0f; var sumOx = 0f; var sumOy = 0f
        for (i in displayPoints.indices) {
            val dp = displayPoints[i]; val pp = physicalPoints[i]
            val sx = pp.x.toFloat() / max(dp.x, 1)
            val sy = pp.y.toFloat() / max(dp.y, 1)
            sumSx += sx; sumSy += sy
            sumOx += pp.x - dp.x * sx
            sumOy += pp.y - dp.y * sy
        }
        val n = displayPoints.size.toFloat()
        return CalibrationData(sumSx / n, sumSy / n, sumOx / n, sumOy / n)
    }

    /** 验证映射精度 */
    fun verify(verified: Boolean) {
        prefs.edit().putBoolean("calib_verified", verified).apply()
    }

    fun isCalibrated(): Boolean {
        return prefs.getBoolean("calib_verified", false)
    }

    /** 重置标定 */
    fun reset() {
        prefs.edit().clear().apply()
    }

    /** 快速标定 — 假设屏幕没有偏移（1:1 映射），仅记录屏幕物理尺寸
     *  适合大多数没有挖孔/曲屏的设备
     */
    fun quickCalibrate(physicalW: Int, physicalH: Int) {
        // displayPoints 默认就是网格化的 0-1000 范围，物理也按 physicalW/physicalH 比例缩放
        // scale = physicalW / 1000, offset = 0
        val sx = physicalW / 1000f
        val sy = physicalH / 1000f
        saveData(CalibrationData(sx, sy, 0f, 0f))
        verify(true)
    }

    // ── 自动微调 ──

    /** 记录一次偏移，积累数据后自动修正 */
    fun recordDeviation(expectedNX: Int, expectedNY: Int, actualPX: Int, actualPY: Int) {
        val history = loadDeviationHistory()
        history.add(DeviationRecord(expectedNX, expectedNY, actualPX, actualPY, System.currentTimeMillis()))
        // 保留最近 20 条
        while (history.size > 20) history.removeAt(0)
        saveDeviationHistory(history)

        // 积累够10条 → 自动修正
        if (history.size >= 10) {
            autoCorrect(history)
        }
    }

    private fun autoCorrect(history: MutableList<DeviationRecord>) {
        val data = loadData() ?: return
        var totalDx = 0f; var totalDy = 0f
        for (r in history) {
            val expected = normalizedToPhysical(r.nx, r.ny)
            totalDx += r.px - expected.x
            totalDy += r.py - expected.y
        }
        val avgDx = totalDx / history.size
        val avgDy = totalDy / history.size
        val corrected = data.copy(
            offsetX = data.offsetX + avgDx * 0.3f,  // 30% 学习率
            offsetY = data.offsetY + avgDy * 0.3f,
        )
        saveData(corrected)
        history.clear()
    }

    // ── 持久化 ──

    private fun loadData(): CalibrationData? {
        val orientation = getOrientation()
        val sx = prefs.getFloat("cal_${orientation}_sx", Float.NaN)
        if (sx.isNaN()) return null
        return CalibrationData(
            scaleX = sx, scaleY = prefs.getFloat("cal_${orientation}_sy", 1f),
            offsetX = prefs.getFloat("cal_${orientation}_ox", 0f), offsetY = prefs.getFloat("cal_${orientation}_oy", 0f),
            verified = prefs.getBoolean("cal_${orientation}_v", false),
        )
    }

    fun saveData(data: CalibrationData) {
        val orientation = getOrientation()
        prefs.edit()
            .putFloat("cal_${orientation}_sx", data.scaleX).putFloat("cal_${orientation}_sy", data.scaleY)
            .putFloat("cal_${orientation}_ox", data.offsetX).putFloat("cal_${orientation}_oy", data.offsetY)
            .putBoolean("cal_${orientation}_v", data.verified).apply()
    }

    private fun getOrientation(): String {
        return if (screenSize.x > screenSize.y) "landscape" else "portrait"
    }

    data class DeviationRecord(val nx: Int, val ny: Int, val px: Int, val py: Int, val time: Long)

    private fun loadDeviationHistory(): MutableList<DeviationRecord> {
        val json = prefs.getString("deviation_history", null) ?: return mutableListOf()
        // Simple serialization
        val items = json.split(";").filter { it.isNotBlank() }
        return items.map {
            val parts = it.split(",")
            DeviationRecord(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toLong())
        }.toMutableList()
    }

    private fun saveDeviationHistory(history: List<DeviationRecord>) {
        val json = history.joinToString(";") { "${it.nx},${it.ny},${it.px},${it.py},${it.time}" }
        prefs.edit().putString("deviation_history", json).apply()
    }
}
