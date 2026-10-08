package io.github.fkoscreen.util

import android.content.Context
import android.provider.Settings
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class ColorBallState(
    val cct: Float,       // [-1.0f, 1.0f]: -1.0 为极暖(暖橙), +1.0 为极冷(浅蓝), 0.0 为标准白点
    val tint: Float,      // [-1.0f, 1.0f]: -1.0 为偏青绿, +1.0 为偏品红, 0.0 为无偏色
    val x750: Float,      // 750 基准设计坐标系 X
    val y750: Float,      // 750 基准设计坐标系 Y
    val r: Int,           // 红色通道增益 (900~1000)
    val g: Int,           // 绿色通道增益 (900~1000)
    val b: Int,           // 蓝色通道增益 (900~1000)
    val rgbStr: String    // ColorOS 格式字符串，如 "1000,989,962,1,1"
)

object ColorBallCalculator {
    const val CENTER_750 = 375.0f
    const val RADIUS_750 = 315.0f

    // 物理特征参考角度（基于 ColorOS 6 扇区色轮与原厂实测标定点，单位：度）
    // 冷色预设点 (432.956, 562.888) -> theta = +72.857143°
    // 品红特征顶点 (R=max, G=min, B=max) -> theta = -30.0°
    // 暖色预设点 (230.582, 221.926) -> theta = -133.333333°
    // 青绿特征顶点 (R=min, G=max, B=min) -> theta = +150.0°
    const val THETA_COLD = 72.857143
    const val THETA_MAGENTA = -30.0
    const val THETA_WARM = -133.333333
    const val THETA_GREEN = 150.0

    private const val KEY_POINT_X = "color_ball_last_pointx"
    private const val KEY_POINT_Y = "color_ball_last_pointy"
    private const val KEY_EYEPROTECT_RGB = "eyeprotect_rgb"
    private const val KEY_BALL_MODE = "color_temperate_ball_mode"

    private var cachedMinVal: Int = -1
    private var cachedMaxVal: Int = -1

    fun getSystemRgbMin(): Int {
        if (cachedMinVal != -1) return cachedMinVal
        val v = getSystemProp("ro.oplus.display.rgb_ball_min_value", 900)
        cachedMinVal = v
        return v
    }

    fun getSystemRgbMax(): Int {
        if (cachedMaxVal != -1) return cachedMaxVal
        val v = getSystemProp("ro.oplus.display.rgb_ball_max_value", 1000)
        cachedMaxVal = v
        return v
    }

    private fun getSystemProp(key: String, def: Int): Int {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getInt = clazz.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            getInt.invoke(null, key, def) as Int
        } catch (_: Throwable) {
            def
        }
    }

    /**
     * ColorOS 17 内外圈非线性手感映射函数
     */
    fun mapPointPercentInnerOuter(d: Double): Double {
        val c = d.coerceIn(0.0, 1.0)
        return if (c <= 0.55) {
            (c / 0.55) * 0.15
        } else {
            0.15 + ((c - 0.55) / 0.45) * 0.85
        }
    }

    /**
     * ColorOS 17 内外圈非线性手感逆映射函数
     */
    fun invertPointPercentInnerOuter(d: Double): Double {
        val c = d.coerceIn(0.0, 1.0)
        return if (c <= 0.15) {
            (c / 0.15) * 0.55
        } else {
            0.55 + ((c - 0.15) / 0.85) * 0.45
        }
    }

    /**
     * 将参数空间极角 phi [-180, 180] 映射到物理圆盘角度 theta [-180, 180]
     * 0° (冷色) -> +72.857°
     * 90° (品红) -> -30.0°
     * 180° (暖色) -> -133.333°
     * 270°/-90° (青绿) -> +150.0°
     */
    fun phiToTheta(phiDeg: Double): Double {
        var p = phiDeg % 360.0
        if (p < 0.0) p += 360.0

        val theta = when {
            p < 90.0 -> {
                val frac = p / 90.0
                THETA_COLD - frac * (THETA_COLD - THETA_MAGENTA)
            }
            p <= 180.0 -> {
                val frac = (p - 90.0) / 90.0
                THETA_MAGENTA - frac * (Math.abs(THETA_WARM) - Math.abs(THETA_MAGENTA))
            }
            p <= 270.0 -> {
                val frac = (p - 180.0) / 90.0
                val tWarmNorm = 360.0 + THETA_WARM
                var t = tWarmNorm - frac * (tWarmNorm - THETA_GREEN)
                if (t > 180.0) t -= 360.0
                t
            }
            else -> {
                val frac = (p - 270.0) / 90.0
                THETA_GREEN - frac * (THETA_GREEN - THETA_COLD)
            }
        }

        var tNorm = theta
        while (tNorm <= -180.0) tNorm += 360.0
        while (tNorm > 180.0) tNorm -= 360.0
        return tNorm
    }

    /**
     * 将物理圆盘角度 theta [-180, 180] 逆映射回参数空间极角 phi [-180, 180]
     */
    fun thetaToPhi(thetaDeg: Double): Double {
        var t = thetaDeg
        while (t <= -180.0) t += 360.0
        while (t > 180.0) t -= 360.0

        var phi = when {
            t <= THETA_COLD && t >= THETA_MAGENTA -> {
                val frac = (THETA_COLD - t) / (THETA_COLD - THETA_MAGENTA)
                frac * 90.0
            }
            t < THETA_MAGENTA && t >= THETA_WARM -> {
                val frac = (THETA_MAGENTA - t) / (THETA_MAGENTA - THETA_WARM)
                90.0 + frac * 90.0
            }
            t >= THETA_GREEN || t < THETA_WARM -> {
                val tNorm = if (t < 0.0) t + 360.0 else t
                val tWarmNorm = 360.0 + THETA_WARM
                val frac = (tWarmNorm - tNorm) / (tWarmNorm - THETA_GREEN)
                180.0 + frac * 90.0
            }
            else -> {
                val frac = (THETA_GREEN - t) / (THETA_GREEN - THETA_COLD)
                270.0 + frac * 90.0
            }
        }

        while (phi > 180.0) phi -= 360.0
        while (phi <= -180.0) phi += 360.0
        return phi
    }

    /**
     * 官方 ColorOS 17 原厂 6 扇区 RGB 计算法 (来自 ScreenColorTemperateBall.java)
     */
    fun calculateRange(angle: Float, dx: Float, dy: Float, radius: Float): String {
        val minVal = getSystemRgbMin()
        val maxVal = getSystemRgbMax()
        val rangeDis = maxVal - minVal

        val dist = hypot(dx.toDouble(), dy.toDouble())
        val rawPercent = (dist / radius.toDouble()).coerceIn(0.0, 1.0)
        val pointPercent = mapPointPercentInnerOuter(rawPercent)

        val deg = Math.toDegrees(angle.toDouble())
        val dAbs = Math.abs(deg)

        val rVal: Int
        val gVal: Int
        val bVal: Int

        when {
            deg >= -90.0 && deg <= -30.0 -> {
                val bInterp = minVal + ((90.0 - dAbs) / 60.0) * rangeDis
                rVal = maxVal
                gVal = (maxVal - (rangeDis * pointPercent)).toInt()
                bVal = (maxVal - ((maxVal - bInterp) * pointPercent)).toInt()
            }
            deg < -90.0 && deg >= -150.0 -> {
                val gInterp = minVal + ((dAbs - 90.0) / 60.0) * rangeDis
                rVal = maxVal
                gVal = (maxVal - ((maxVal - gInterp) * pointPercent)).toInt()
                bVal = (maxVal - (rangeDis * pointPercent)).toInt()
            }
            deg < -150.0 && deg >= -180.0 -> {
                val rInterp = minVal + ((180.0 - dAbs) / 60.0) * rangeDis
                rVal = (maxVal - ((maxVal - rInterp) * pointPercent)).toInt()
                gVal = maxVal
                bVal = (maxVal - (rangeDis * pointPercent)).toInt()
            }
            deg > 150.0 && deg <= 180.0 -> {
                val rInterp = minVal + ((dAbs - 150.0) / 60.0) * rangeDis
                rVal = (maxVal - ((maxVal - rInterp) * pointPercent)).toInt()
                gVal = maxVal
                bVal = (maxVal - (rangeDis * pointPercent)).toInt()
            }
            deg > 90.0 && deg <= 150.0 -> {
                val bInterp = minVal + ((150.0 - dAbs) / 60.0) * rangeDis
                rVal = (maxVal - (rangeDis * pointPercent)).toInt()
                gVal = maxVal
                bVal = (maxVal - ((maxVal - bInterp) * pointPercent)).toInt()
            }
            deg > 30.0 && deg <= 90.0 -> {
                val gInterp = minVal + ((dAbs - 30.0) / 60.0) * rangeDis
                rVal = (maxVal - (rangeDis * pointPercent)).toInt()
                gVal = (maxVal - ((maxVal - gInterp) * pointPercent)).toInt()
                bVal = maxVal
            }
            deg >= 0.0 && deg <= 30.0 -> {
                val rInterp = minVal + ((30.0 - dAbs) / 60.0) * rangeDis
                rVal = (maxVal - ((maxVal - rInterp) * pointPercent)).toInt()
                gVal = (maxVal - (rangeDis * pointPercent)).toInt()
                bVal = maxVal
            }
            else -> {
                val rInterp = minVal + ((dAbs + 30.0) / 60.0) * rangeDis
                rVal = (maxVal - ((maxVal - rInterp) * pointPercent)).toInt()
                gVal = (maxVal - (rangeDis * pointPercent)).toInt()
                bVal = maxVal
            }
        }

        val rClamped = rVal.coerceIn(minVal, maxVal)
        val gClamped = gVal.coerceIn(minVal, maxVal)
        val bClamped = bVal.coerceIn(minVal, maxVal)

        return "$rClamped,$gClamped,$bClamped,1,1"
    }

    /**
     * 正向合成：由冷暖 (cct) 和青品 (tint) 滑杆数值计算 750 坐标系位置与 RGB
     */
    fun compose(cct: Float, tint: Float): ColorBallState {
        var c = cct.coerceIn(-1.0f, 1.0f)
        var t = tint.coerceIn(-1.0f, 1.0f)

        var rNorm = hypot(c.toDouble(), t.toDouble())
        if (rNorm > 1.0) {
            c = (c / rNorm).toFloat()
            t = (t / rNorm).toFloat()
            rNorm = 1.0
        }

        val maxV = getSystemRgbMax()
        if (rNorm < 1e-4) {
            return ColorBallState(
                cct = 0.0f,
                tint = 0.0f,
                x750 = CENTER_750,
                y750 = CENTER_750,
                r = maxV,
                g = maxV,
                b = maxV,
                rgbStr = "$maxV,$maxV,$maxV,1,1"
            )
        }

        val phiRad = atan2(t.toDouble(), c.toDouble())
        val phiDeg = Math.toDegrees(phiRad)
        val thetaDeg = phiToTheta(phiDeg)
        val thetaRad = Math.toRadians(thetaDeg)

        val dx = (rNorm * RADIUS_750 * cos(thetaRad)).toFloat()
        val dy = (rNorm * RADIUS_750 * sin(thetaRad)).toFloat()

        val x750 = CENTER_750 + dx
        val y750 = CENTER_750 + dy

        val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat()
        val rgbStr = calculateRange(angle, dx, dy, RADIUS_750)

        val parts = rgbStr.split(",")
        val r = parts.getOrNull(0)?.toIntOrNull() ?: maxV
        val g = parts.getOrNull(1)?.toIntOrNull() ?: maxV
        val b = parts.getOrNull(2)?.toIntOrNull() ?: maxV

        return ColorBallState(
            cct = c,
            tint = t,
            x750 = x750,
            y750 = y750,
            r = r,
            g = g,
            b = b,
            rgbStr = rgbStr
        )
    }

    /**
     * 逆向反解：由 750 坐标系 (x750, y750) 反解出冷暖 (cct) 和青品 (tint)
     */
    fun decompose(x750: Float, y750: Float): ColorBallState {
        val dx = x750 - CENTER_750
        val dy = y750 - CENTER_750
        val dist = hypot(dx.toDouble(), dy.toDouble())

        val maxV = getSystemRgbMax()
        if (dist < 0.5) {
            return ColorBallState(
                cct = 0.0f,
                tint = 0.0f,
                x750 = CENTER_750,
                y750 = CENTER_750,
                r = maxV,
                g = maxV,
                b = maxV,
                rgbStr = "$maxV,$maxV,$maxV,1,1"
            )
        }

        val rNorm = (dist / RADIUS_750.toDouble()).coerceIn(0.0, 1.0)
        val thetaRad = atan2(dy.toDouble(), dx.toDouble())
        val thetaDeg = Math.toDegrees(thetaRad)

        val phiDeg = thetaToPhi(thetaDeg)
        val phiRad = Math.toRadians(phiDeg)

        val cct = (rNorm * cos(phiRad)).toFloat().coerceIn(-1.0f, 1.0f)
        val tint = (rNorm * sin(phiRad)).toFloat().coerceIn(-1.0f, 1.0f)

        val angle = thetaRad.toFloat()
        val rgbStr = calculateRange(angle, dx, dy, RADIUS_750)

        val parts = rgbStr.split(",")
        val r = parts.getOrNull(0)?.toIntOrNull() ?: maxV
        val g = parts.getOrNull(1)?.toIntOrNull() ?: maxV
        val b = parts.getOrNull(2)?.toIntOrNull() ?: maxV

        return ColorBallState(
            cct = cct,
            tint = tint,
            x750 = x750,
            y750 = y750,
            r = r,
            g = g,
            b = b,
            rgbStr = rgbStr
        )
    }

    /**
     * 从系统读取当前色温球状态
     */
    fun readFromSystem(context: Context): ColorBallState {
        val cr = context.contentResolver
        val mode = try {
            Settings.System.getInt(cr, KEY_BALL_MODE, 2)
        } catch (_: Throwable) { 2 }

        val x = try {
            Settings.System.getFloat(cr, KEY_POINT_X, -1.0f)
        } catch (_: Throwable) { -1.0f }

        val y = try {
            Settings.System.getFloat(cr, KEY_POINT_Y, -1.0f)
        } catch (_: Throwable) { -1.0f }

        if (mode == 2 || (x <= 0.0f && y <= 0.0f)) {
            // 处于默认白点中心
            return compose(0.0f, 0.0f)
        }

        if (mode == 1 && (x <= 0.0f || y <= 0.0f)) {
            return decompose(230.582f, 221.926f)
        }

        if (mode == 3 && (x <= 0.0f || y <= 0.0f)) {
            return decompose(432.956f, 562.888f)
        }

        return decompose(x, y)
    }

    private val rootExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile
    private var pendingRootState: ColorBallState? = null
    @Volatile
    private var isRootWorkerRunning = false

    /**
     * 下发至系统管线与 Settings
     */
    fun applyToSystem(context: Context, state: ColorBallState) {
        val cr = context.contentResolver
        val isCenter = Math.abs(state.cct) < 0.005f && Math.abs(state.tint) < 0.005f
        val isWarmPreset = Math.abs(state.cct - (-0.6681f)) < 0.02f && Math.abs(state.tint) < 0.02f
        val isColdPreset = Math.abs(state.cct - 0.6242f) < 0.02f && Math.abs(state.tint) < 0.02f

        val ballMode = when {
            isCenter -> 2
            isWarmPreset -> 1
            isColdPreset -> 3
            else -> 4
        }

        var directSuccess = false
        try {
            Settings.System.putString(cr, KEY_EYEPROTECT_RGB, state.rgbStr)
            Settings.System.putFloat(cr, KEY_POINT_X, state.x750)
            Settings.System.putFloat(cr, KEY_POINT_Y, state.y750)
            Settings.System.putInt(cr, KEY_BALL_MODE, ballMode)
            directSuccess = true
        } catch (_: Throwable) {
            directSuccess = false
        }

        if (!directSuccess) {
            // 权限后备方案：通过异步队列执行 su，避免阻塞 UI 渲染
            pendingRootState = state
            synchronized(rootExecutor) {
                if (!isRootWorkerRunning) {
                    isRootWorkerRunning = true
                    rootExecutor.execute {
                        while (true) {
                            val targetState = pendingRootState ?: break
                            pendingRootState = null
                            try {
                                val isC = Math.abs(targetState.cct) < 0.005f && Math.abs(targetState.tint) < 0.005f
                                val isW = Math.abs(targetState.cct - (-0.6681f)) < 0.02f && Math.abs(targetState.tint) < 0.02f
                                val isK = Math.abs(targetState.cct - 0.6242f) < 0.02f && Math.abs(targetState.tint) < 0.02f
                                val modeInt = when {
                                    isC -> 2
                                    isW -> 1
                                    isK -> 3
                                    else -> 4
                                }
                                val cmd = "settings put system $KEY_EYEPROTECT_RGB '${targetState.rgbStr}'; " +
                                        "settings put system $KEY_POINT_X ${targetState.x750}; " +
                                        "settings put system $KEY_POINT_Y ${targetState.y750}; " +
                                        "settings put system $KEY_BALL_MODE $modeInt"
                                Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
                            } catch (_: Throwable) {}
                        }
                        isRootWorkerRunning = false
                    }
                }
            }
        }
    }
}
