package io.github.fkoscreen

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences
import java.io.File

object ConfigManager {
    const val PACKAGE_NAME = "io.github.benbaobaoshigemi.fkoscreen"
    const val LEGACY_PACKAGE_NAME = "io.github.fkoscreen"
    const val PREFS_NAME = "fkoscreen_prefs"

    const val KEY_MANUAL_HBM = "manual_hbm_brightness"
    const val KEY_MANUAL_PEAK = "manual_peak_brightness"
    const val KEY_THERMAL_BYPASS = "thermal_limit_bypass"
    const val KEY_FOSS_BYPASS = "foss_bypass"
    const val KEY_HDR_RATIO = "hdr_ratio_fix"
    const val KEY_DARK_MODE_STYLES = "dark_mode_styles"
    const val KEY_COLOR_BALL_ANCHOR = "color_ball_anchor"
    const val KEY_AOD_BRIGHTNESS_SCALE = "aod_brightness_scale"

    // 原生全景 AOD 把尼特表 ÷1.6，即 0.625
    const val DEFAULT_AOD_BRIGHTNESS_SCALE = 0.625f
    const val MIN_AOD_BRIGHTNESS_SCALE = 0.5f
    const val MAX_AOD_BRIGHTNESS_SCALE = 1.0f

    // 兼容旧版键名
    const val KEY_MANUAL_1250 = KEY_MANUAL_HBM
    const val KEY_MANUAL_1600 = KEY_MANUAL_PEAK

    private var xPrefs: XSharedPreferences? = null

    fun getXPrefs(): XSharedPreferences {
        if (xPrefs == null) {
            val deFile = File("/data/user_de/0/$PACKAGE_NAME/shared_prefs/$PREFS_NAME.xml")
            val ceFile = File("/data/data/$PACKAGE_NAME/shared_prefs/$PREFS_NAME.xml")
            val legDeFile = File("/data/user_de/0/$LEGACY_PACKAGE_NAME/shared_prefs/$PREFS_NAME.xml")
            val legCeFile = File("/data/data/$LEGACY_PACKAGE_NAME/shared_prefs/$PREFS_NAME.xml")
            xPrefs = when {
                deFile.exists() -> XSharedPreferences(deFile)
                ceFile.exists() -> XSharedPreferences(ceFile)
                legDeFile.exists() -> XSharedPreferences(legDeFile)
                legCeFile.exists() -> XSharedPreferences(legCeFile)
                else -> XSharedPreferences(PACKAGE_NAME, PREFS_NAME)
            }
            xPrefs?.makeWorldReadable()
        } else {
            xPrefs?.reload()
        }
        return xPrefs!!
    }

    private fun getSettingInt(key: String): Int {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = atClass.getMethod("currentApplication").invoke(null) as? Context
            val cr = app?.contentResolver ?: return -1
            android.provider.Settings.System.getInt(cr, key, -1)
        } catch (_: Throwable) {
            -1
        }
    }

    fun isManualHbmEnabled(): Boolean {
        val s = getSettingInt(KEY_MANUAL_HBM)
        if (s != -1) return s == 1
        val legacy = getSettingInt("manual_1250_brightness")
        if (legacy != -1) return legacy == 1
        return try {
            val p = getXPrefs()
            if (p.contains(KEY_MANUAL_HBM)) p.getBoolean(KEY_MANUAL_HBM, true)
            else p.getBoolean("manual_1250_brightness", true)
        } catch (_: Throwable) {
            true
        }
    }

    fun isManualPeakEnabled(): Boolean {
        val s = getSettingInt(KEY_MANUAL_PEAK)
        if (s != -1) return s == 1
        val legacy = getSettingInt("manual_1600_brightness")
        if (legacy != -1) return legacy == 1
        return try {
            val p = getXPrefs()
            if (p.contains(KEY_MANUAL_PEAK)) p.getBoolean(KEY_MANUAL_PEAK, false)
            else p.getBoolean("manual_1600_brightness", false)
        } catch (_: Throwable) {
            false
        }
    }

    fun isThermalBypassEnabled(): Boolean {
        val s = getSettingInt(KEY_THERMAL_BYPASS)
        if (s != -1) return s == 1
        return try {
            getXPrefs().getBoolean(KEY_THERMAL_BYPASS, false)
        } catch (_: Throwable) {
            false
        }
    }

    // 兼容历史调用
    fun isManual1250Enabled(): Boolean = isManualHbmEnabled()
    fun isManual1600Enabled(): Boolean = isManualPeakEnabled()

    fun isFossBypassEnabled(): Boolean = getBooleanConfig(KEY_FOSS_BYPASS, true)

    fun isHdrRatioEnabled(): Boolean = getBooleanConfig(KEY_HDR_RATIO, true)

    fun isDarkModeStylesEnabled(): Boolean = getBooleanConfig(KEY_DARK_MODE_STYLES, true)

    fun isColorBallAnchorEnabled(): Boolean = getBooleanConfig(KEY_COLOR_BALL_ANCHOR, true)

    // Settings.System 是唯一能被 system_server / SystemUI / 设置 可靠读到的通道（XSharedPreferences 会被 SELinux 拦截），
    // 因此优先读它，读不到再回退到模块自身的 prefs 文件
    private fun getBooleanConfig(key: String, def: Boolean): Boolean {
        val s = getSettingInt(key)
        if (s != -1) return s == 1
        return try {
            getXPrefs().getBoolean(key, def)
        } catch (_: Throwable) {
            def
        }
    }

    /**
     * 模块界面写入开关：本地 prefs + Settings.System。
     * 模块没有 WRITE_SETTINGS 授权时 putInt 会抛 SecurityException，此时改用 root 写入。
     */
    fun writeConfig(context: Context, key: String, value: Boolean) {
        getAppPrefs(context).edit().putBoolean(key, value).apply()
        fixPermissions(context)
        val intValue = if (value) 1 else 0
        val written = try {
            android.provider.Settings.System.putInt(context.contentResolver, key, intValue)
        } catch (_: Throwable) {
            false
        }
        if (!written) {
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put system $key $intValue")).waitFor()
                } catch (_: Throwable) {}
            }.start()
        }
    }

    fun getAodBrightnessScale(): Float {
        val value = try {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = atClass.getMethod("currentApplication").invoke(null) as? Context
            app?.contentResolver?.let {
                android.provider.Settings.System.getString(it, KEY_AOD_BRIGHTNESS_SCALE)?.toFloatOrNull()
            }
        } catch (_: Throwable) {
            null
        } ?: try {
            getXPrefs().getFloat(KEY_AOD_BRIGHTNESS_SCALE, DEFAULT_AOD_BRIGHTNESS_SCALE)
        } catch (_: Throwable) {
            DEFAULT_AOD_BRIGHTNESS_SCALE
        }
        return value.coerceIn(MIN_AOD_BRIGHTNESS_SCALE, MAX_AOD_BRIGHTNESS_SCALE)
    }

    fun writeConfig(context: Context, key: String, value: Float) {
        getAppPrefs(context).edit().putFloat(key, value).apply()
        fixPermissions(context)
        val text = value.toString()
        val written = try {
            android.provider.Settings.System.putString(context.contentResolver, key, text)
        } catch (_: Throwable) {
            false
        }
        if (!written) {
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put system $key $text")).waitFor()
                } catch (_: Throwable) {}
            }.start()
        }
    }

    fun getAppPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun fixPermissions(context: Context) {
        try {
            val prefsFile = File(context.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
                prefsFile.parentFile?.setReadable(true, false)
                prefsFile.parentFile?.setExecutable(true, false)
                // 确保 /data/data/io.github.fkoscreen 目录对其它 UID 具有 +x 遍历权限
                context.filesDir.parentFile?.setExecutable(true, false)
                context.filesDir.parentFile?.setReadable(true, false)
                Thread {
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 666 ${prefsFile.absolutePath}")).waitFor()
                    } catch (_: Throwable) {}
                }.start()
            }
        } catch (_: Exception) {}
    }
}
