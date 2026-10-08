package io.github.fkoscreen.hooks

import android.view.Display
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 144Hz 屏幕刷新率完整解锁：
 *  - system_server：突破 OplusRefreshRateConfigs / OplusRefreshRatePolicyImpl / DisplayModeDirector 投票拦截
 *  - com.android.settings：解锁 ScreenRefreshUtils / ExtremeRefreshUtils / SysFeatureUtils UI 门控
 *
 * 此文件完全独立，不依赖阅读模式或其他实验性功能，保证基线安全。
 */
object RefreshRate144Hook {
    private const val TAG = "FkOScreen:RefreshRate144"

    fun initSystemServer(classLoader: ClassLoader) {
        hookOplusRefreshRateConfigs(classLoader)
        hookOplusRefreshRatePolicyImpl(classLoader)
        hookSettingsObserver(classLoader)
        hookDisplayModeDirector(classLoader)
        hookOplusFeatureConfigManager(classLoader)
    }

    fun initSettings(classLoader: ClassLoader) {
        hookScreenRefreshUtils(classLoader)
        hookExtremeRefreshUtils(classLoader)
        hookSysFeatureUtils(classLoader)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A. OplusRefreshRateConfigs  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookOplusRefreshRateConfigs(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateConfigs", classLoader
            )
            XposedBridge.hookAllMethods(cls, "getDefaultMaxRate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = 144.0f
                }
            })
            XposedBridge.hookAllMethods(cls, "getDefaultRateId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pickMode = param.args.getOrNull(0) as? Int ?: 0
                    val res = param.result as? Int ?: 0
                    if (pickMode == 8 || pickMode == 4 || res == 3) {
                        param.result = 4
                    }
                }
            })
            XposedBridge.hookAllMethods(cls, "isExtremeHighEnable", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.log("$TAG: Hooked OplusRefreshRateConfigs OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OplusRefreshRateConfigs failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B. OplusRefreshRatePolicyImpl  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookOplusRefreshRatePolicyImpl(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", classLoader
            )

            // 构造时写入字段
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "mSupportExtremeHighRate", true)
                        XposedHelpers.setIntField(param.thisObject, "mSupportMaxRefreshId", 4)
                    } catch (_: Throwable) {}
                }
            })

            // 各查询方法
            fun forceInt4(name: String) {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val res = param.result as? Int ?: 0
                        if (res == 3 || res <= 0) param.result = 4
                    }
                })
            }
            forceInt4("getMaxRateIdInLimit144")
            forceInt4("getKeyguardRefreshRateId")
            forceInt4("getCurrentDefaultRateId")
            forceInt4("adjustRefreshForDefaultMax")

            XposedBridge.hookAllMethods(cls, "adjustPreferredModeId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val winPreferredId = param.args.getOrNull(0) as? Int ?: 0
                    val res = param.result as? Int ?: 0
                    if (winPreferredId == 0 || res == 3) param.result = 4
                }
            })

            XposedBridge.hookAllMethods(cls, "getPkgRefreshRateId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val res = param.result as? Int ?: 0
                    // 若命中 120Hz(3) 或未配置(≤0) 则提升到 144Hz；尊重 60Hz(2)/90Hz(1) 的显式设定
                    if (res == 3 || res <= 0) param.result = 4
                }
            })

            XposedBridge.hookAllMethods(cls, "isExtremeHighRatePkg", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = true }
            })
            XposedBridge.hookAllMethods(cls, "supportExtremeHighRateBoost", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = true }
            })

            // 硬件模式 ID 映射：1(1440p@120) → 4(1440p@144)，5(1080p@120) → 8(1080p@144)
            XposedBridge.hookAllMethods(cls, "getPreferredModeId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    when (param.result as? Int) {
                        1 -> param.result = 4
                        5 -> param.result = 8
                    }
                }
            })

            XposedBridge.hookAllMethods(cls, "getPreferredMaxRefreshRate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val maxRate = param.result as? Float ?: 0f
                    if (Math.abs(maxRate - 120.0f) < 1.0f) param.result = 144.00002f
                }
            })

            // setDisplayConfigSpecs：将 120Hz 硬件模式替换为同分辨率 144Hz 模式
            XposedBridge.hookAllMethods(cls, "setDisplayConfigSpecs", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mode = param.args.getOrNull(0) as? Display.Mode ?: return
                    if (Math.abs(mode.refreshRate - 120.0f) < 1.0f) {
                        try {
                            val displayInfo = XposedHelpers.getObjectField(param.thisObject, "mDisplayInfo")
                            @Suppress("UNCHECKED_CAST")
                            val supportedModes = XposedHelpers.getObjectField(displayInfo, "supportedModes") as? Array<Display.Mode>
                            supportedModes?.forEach { m ->
                                if (m.physicalWidth == mode.physicalWidth &&
                                    m.physicalHeight == mode.physicalHeight &&
                                    Math.abs(m.refreshRate - 144.0f) < 1.0f
                                ) {
                                    param.args[0] = m
                                    return
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            })

            XposedBridge.log("$TAG: Hooked OplusRefreshRatePolicyImpl OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OplusRefreshRatePolicyImpl failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C. SettingsObserver（内部类）  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookSettingsObserver(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl\$SettingsObserver", classLoader
            )
            XposedBridge.hookAllMethods(cls, "isExtremeHighSettingOn", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = true }
            })
            XposedBridge.log("$TAG: Hooked SettingsObserver.isExtremeHighSettingOn OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: SettingsObserver failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // D. DisplayModeDirector$AppRequestObserver  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookDisplayModeDirector(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.display.mode.DisplayModeDirector\$AppRequestObserver", classLoader
            )
            XposedBridge.hookAllMethods(cls, "setAppRequest", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val reqRate = param.args.getOrNull(2) as? Float ?: 0f
                    val reqMax  = param.args.getOrNull(4) as? Float ?: 0f
                    val modeId  = param.args.getOrNull(1) as? Int ?: 0
                    if (reqRate >= 119.9f || reqMax >= 119.9f || modeId == 1 || modeId == 5) {
                        if (reqRate >= 119.9f) param.args[2] = 144.00002f
                        if (reqMax  >= 119.9f) param.args[4] = 144.00002f
                        if (modeId == 1)       param.args[1] = 4
                        else if (modeId == 5)  param.args[1] = 8
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked AppRequestObserver.setAppRequest OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: AppRequestObserver failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // E. OplusFeatureConfigManager（app_extreme_high_refresh）  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookOplusFeatureConfigManager(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.oplus.content.OplusFeatureConfigManager", classLoader
            )
            XposedBridge.hookAllMethods(cls, "hasFeature", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.getOrNull(0) == "oplus.software.performance.app_extreme_high_refresh") {
                        param.result = true
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusFeatureConfigManager(app_extreme_high_refresh) OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OplusFeatureConfigManager failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F. Settings UI 解锁  （com.android.settings）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookScreenRefreshUtils(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.ScreenRefreshUtils", classLoader
            )
            XposedBridge.hookAllMethods(cls, "getExtremeRefreshLevelRate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = 144 }
            })
            XposedBridge.hookAllMethods(cls, "checkMaxRefreshSupport", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = false }
            })
            XposedBridge.hookAllMethods(cls, "isRefreshChoiceSupport", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = true }
            })
            XposedBridge.log("$TAG: Hooked ScreenRefreshUtils OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: ScreenRefreshUtils failed: ${t.message}")
        }
    }

    private fun hookExtremeRefreshUtils(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.settings.development.extremerefresh.ExtremeRefreshUtils", classLoader
            )
            XposedBridge.hookAllMethods(cls, "getOverrideRefreshRate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // 禁止将用户选择的 144Hz 被强制改写为 120Hz
                    if ((param.args.getOrNull(0) as? Int) == 144) {
                        param.result = 144
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked ExtremeRefreshUtils OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: ExtremeRefreshUtils failed: ${t.message}")
        }
    }

    private fun hookSysFeatureUtils(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.oplus.settings.utils.SysFeatureUtils", classLoader
            )
            XposedBridge.hookAllMethods(cls, "isDisableScreenHighRefreshChoiceSupport", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { param.result = false }
            })
            XposedBridge.log("$TAG: Hooked SysFeatureUtils OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: SysFeatureUtils failed: ${t.message}")
        }
    }
}
