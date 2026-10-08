package io.github.fkoscreen.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.fkoscreen.ConfigManager

/**
 * 全景 AOD 亮度系数  （com.android.systemui）
 *
 * SystemUI 的 OplusPanoramicAODLightController 按以下流程算出 AOD 亮度：
 *  1. 息屏光感 → 26 个 lux 档位
 *  2. 档位 → config_panoramic_aod_brightness_nit 尼特表
 *  3. 尼特值 ÷ 1.6（即原生系数 0.625）→ getBacklightFromNit 换算成 0~255 级别
 *  4. 不超过息屏前的屏幕亮度（命中时尼特值改为屏幕尼特）
 *
 * 级别从两个出口离开 SystemUI：进入 AOD 时 setBrightnessBeforeDozing 的返回值（取缓存结果），
 * AOD 期间光感变化时 setDozeScreenBrightness(nit, level)。在这两处把第 3 步的 0.625 换成用户系数，
 * 仍受第 4 步上限约束。第 0 档（< 25 lux，夜间/暗处）、被屏幕亮度压过的结果、系数为默认值时都不改。
 */
object AodBrightnessHook {
    private const val TAG = "FkOScreen:AodBrightness"
    private const val CONTROLLER =
        "com.oplus.systemui.aod.sensor.backlight.OplusPanoramicAODLightController"
    private const val STOCK_NIT_DIVISOR = 1.6f

    @Volatile private var sNitTable: FloatArray? = null
    private var sControllerCls: Class<*>? = null
    private var sDisplayManagerCls: Class<*>? = null

    fun init(classLoader: ClassLoader) {
        try {
            val controllerCls = XposedHelpers.findClass(CONTROLLER, classLoader)
            sControllerCls = controllerCls
            sDisplayManagerCls = XposedHelpers.findClass("android.hardware.display.OplusDisplayManager", classLoader)

            // 尼特表来自资源（overlay 可覆盖），从任一实例取一次
            XposedBridge.hookAllConstructors(controllerCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (sNitTable != null) return
                    try {
                        sNitTable = XposedHelpers.callMethod(param.thisObject, "getAodBrightnessNit") as FloatArray
                    } catch (_: Throwable) {}
                }
            })

            // 出口 1：进入 AOD
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.oplus.systemui.aod.OplusDozeServiceExImpl", classLoader),
                "setBrightnessBeforeDozing",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val level = param.result as? Int ?: return
                        if (level <= 0) return
                        val nit = XposedHelpers.getStaticFloatField(controllerCls, "curBrightnessNit")
                        // 级别不是来自光感缓存（而是直接取屏幕亮度）时不处理
                        if (level != XposedHelpers.getStaticIntField(controllerCls, "curBrightnessLevel")) return
                        adjust(nit, level)?.let { param.result = it }
                    }
                }
            )

            // 出口 2：AOD 期间光感变化
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("com.oplus.systemui.aod.display.BaseDisplayUtil", classLoader),
                "setDozeScreenBrightness",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size != 2) return
                        val nit = param.args[0] as? Float ?: return
                        val level = param.args[1] as? Int ?: return
                        adjust(nit, level)?.let { param.args[1] = it }
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked panoramic AOD brightness OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: panoramic AOD brightness failed: ${t.message}")
        }
    }

    /** 返回换算后的级别；不需要改动时返回 null */
    private fun adjust(nit: Float, level: Int): Int? {
        val scale = ConfigManager.getAodBrightnessScale()
        if (Math.abs(scale - ConfigManager.DEFAULT_AOD_BRIGHTNESS_SCALE) < 0.001f) return null
        val table = sNitTable ?: return null
        // 只处理"查表值 ÷1.6"得到的尼特；第 0 档保持原生
        val rank = table.indices.firstOrNull { Math.abs(table[it] / STOCK_NIT_DIVISOR - nit) < 0.01f } ?: return null
        if (rank == 0) return null
        return try {
            val displayManager = XposedHelpers.callStaticMethod(sDisplayManagerCls, "getInstance")
            val newLevel = XposedHelpers.callMethod(displayManager, "getBacklightFromNit", 0, table[rank] * scale) as Int
            val cap = XposedHelpers.getStaticFloatField(sControllerCls, "curScreenBrightness").toInt()
            val result = newLevel.coerceAtMost(cap).coerceIn(1, 255)
            XposedBridge.log("$TAG: rank=$rank nit=${table[rank]} scale=$scale level $level -> $result (cap=$cap)")
            result
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: adjust failed: ${t.message}")
            null
        }
    }
}
