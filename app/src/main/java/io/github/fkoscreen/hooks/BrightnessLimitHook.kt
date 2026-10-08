package io.github.fkoscreen.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.fkoscreen.ConfigManager

object BrightnessLimitHook {
    private const val TAG = "FkOScreen:BrightnessLimit"
    // 动态硬件参数缓存（默认以硬件出厂标称兜底，系统启动后自动从底层模型中刷新更新，彻底解耦硬编码）
    @Volatile private var mCachedPanelPeakBrightness: Float = 4674.0f
    @Volatile private var mCachedPanelPeakNit: Float = 1600.0f
    @Volatile private var mCachedHbmBrightness: Float = 4543.0f
    @Volatile private var mCachedHbmNit: Float = 1250.0f
    @Volatile private var sBrightnessModel: Any? = null

    fun getTargetBrightness(): Float? {
        return when {
            ConfigManager.isManualPeakEnabled() -> mCachedPanelPeakBrightness
            ConfigManager.isManualHbmEnabled() -> mCachedHbmBrightness
            else -> null
        }
    }

    fun getTargetNit(): Float? {
        return when {
            ConfigManager.isManualPeakEnabled() -> mCachedPanelPeakNit
            ConfigManager.isManualHbmEnabled() -> mCachedHbmNit
            else -> null
        }
    }

    private fun updateHbmNit(nit: Float) {
        if (nit <= 0f) return
        mCachedHbmNit = nit
        sBrightnessModel?.let { model ->
            try {
                val b = XposedHelpers.callMethod(model, "getBrightnessFromNit", nit) as? Float
                if (b != null && b > 0f) {
                    mCachedHbmBrightness = b
                }
            } catch (_: Throwable) {}
        }
    }

    fun init(classLoader: ClassLoader) {
        initSystemServer(classLoader)
    }

    fun initSystemServer(classLoader: ClassLoader) {
        // 1. 拦截 OplusFeatureIncreaseBrightnessRange
        try {
            val increaseRangeClass = XposedHelpers.findClass(
                "com.android.server.display.feature.postprocess.OplusFeatureIncreaseBrightnessRange",
                classLoader
            )
            XposedBridge.hookAllMethods(increaseRangeClass, "getScreenNormalMaxBrightness", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = getTargetBrightness() ?: return
                    param.result = target
                }
            })
            XposedBridge.log("$TAG: Hooked OplusFeatureIncreaseBrightnessRange.getScreenNormalMaxBrightness")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusFeatureIncreaseBrightnessRange: ${t.message}")
        }

        // 2. 拦截 OplusBrightnessPostProcessingManager
        try {
            val postProcessingMgrClass = XposedHelpers.findClass(
                "com.android.server.display.feature.postprocess.OplusBrightnessPostProcessingManager",
                classLoader
            )
            XposedBridge.hookAllMethods(postProcessingMgrClass, "getScreenNormalMaxBrightness", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = getTargetBrightness() ?: return
                    param.result = target
                }
            })
            XposedBridge.log("$TAG: Hooked OplusBrightnessPostProcessingManager.getScreenNormalMaxBrightness")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusBrightnessPostProcessingManager: ${t.message}")
        }

        // 3. 拦截 OplusFeatureWindowBrightness：loadWindowMaxBrightnessLimitInfo 与 postProcessing
        try {
            val windowBrightnessClass = XposedHelpers.findClass(
                "com.android.server.display.feature.postprocess.OplusFeatureWindowBrightness",
                classLoader
            )
            XposedBridge.hookAllMethods(windowBrightnessClass, "loadWindowMaxBrightnessLimitInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val targetNit = getTargetNit() ?: return
                    try {
                        XposedHelpers.setFloatField(param.thisObject, "mLimitNit", targetNit)
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(windowBrightnessClass, "postProcessing", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val targetNit = getTargetNit() ?: return
                    try {
                        XposedHelpers.setFloatField(param.thisObject, "mLimitNit", targetNit)
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.log("$TAG: Hooked OplusFeatureWindowBrightness limits")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusFeatureWindowBrightness: ${t.message}")
        }

        // 4. 拦截 OplusDisplayBrightnessModel 关键上限方法并动态捕获硬件极值
        try {
            val brightnessModelClass = XposedHelpers.findClass(
                "com.android.server.display.model.OplusDisplayBrightnessModel",
                classLoader
            )
            XposedBridge.hookAllMethods(brightnessModelClass, "getMaxBrightness", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    sBrightnessModel = param.thisObject
                    val target = getTargetBrightness() ?: return
                    param.result = target
                }
            })
            XposedBridge.hookAllMethods(brightnessModelClass, "getMaxPanelNit", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    sBrightnessModel = param.thisObject
                    val origNit = (param.result as? Number)?.toFloat() ?: 0f
                    if (origNit > 0f) {
                        mCachedPanelPeakNit = origNit
                    }
                    val targetNit = getTargetNit() ?: return
                    if (origNit < targetNit) {
                        param.result = targetNit
                    }
                }
            })
            XposedBridge.hookAllMethods(brightnessModelClass, "getTotalBrightness", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    sBrightnessModel = param.thisObject
                    val origTotal = (param.result as? Number)?.toFloat() ?: 0f
                    if (origTotal > 0f) {
                        mCachedPanelPeakBrightness = origTotal
                    }
                    val target = getTargetBrightness() ?: return
                    if (origTotal < target) {
                        param.result = target
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusDisplayBrightnessModel limit methods")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusDisplayBrightnessModel: ${t.message}")
        }

        // 5. 拦截 getEdrNormalLimit 动态捕获 HBM 阈值（ColorOS 17 起该方法分散在多个类中）
        val edrHookCallback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val edrNit = (param.result as? Number)?.toFloat() ?: 0f
                if (edrNit > 0f) {
                    updateHbmNit(edrNit)
                }
            }
        }
        listOf(
            "com.android.server.display.config.global.OplusDisplayGlobalConfig",
            "com.android.server.display.feature.postprocess.OplusFeaturePwEdrEnhanceBrightness",
            "com.android.server.display.feature.postprocess.OplusFeatureEdrEnhanceBrightness"
        ).forEach { cName ->
            // 逐个隔离：任一类缺失都不能中断后续 hook（本方法在 system_server 入口中最先执行）
            try {
                val clazz = XposedHelpers.findClass(cName, classLoader)
                XposedBridge.hookAllMethods(clazz, "getEdrNormalLimit", edrHookCallback)
                XposedBridge.log("$TAG: Hooked $cName.getEdrNormalLimit")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Failed to hook $cName.getEdrNormalLimit: ${t.message}")
            }
        }

        // 6. 拦截 OplusFeatureTemperatureLimitBrightness（解除温度对亮度限制）
        try {
            val tempLimitClass = XposedHelpers.findClass(
                "com.android.server.display.feature.postprocess.OplusFeatureTemperatureLimitBrightness",
                classLoader
            )
            // 拦截温控后处理：高温时不再强行削减 Nit 与降低调整速率
            XposedBridge.hookAllMethods(tempLimitClass, "postProcessing", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (ConfigManager.isThermalBypassEnabled()) {
                        param.result = null
                    }
                }
            })
            // 拦截单点查询 getbrightness：直接返回未截断的目标 Nit
            XposedBridge.hookAllMethods(tempLimitClass, "getbrightness", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (ConfigManager.isThermalBypassEnabled()) {
                        param.result = param.args[0]
                    }
                }
            })
            // 拦截温控状态查询：返回 false 表示未受到温控限制
            XposedBridge.hookAllMethods(tempLimitClass, "isMaxBrightnessLimit", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (ConfigManager.isThermalBypassEnabled()) {
                        param.result = false
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusFeatureTemperatureLimitBrightness (Thermal Bypass)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusFeatureTemperatureLimitBrightness: ${t.message}")
        }

        // 7. 拦截 DisplayPowerController，扩展 AOSP 内部普通最大亮度限制
        try {
            val dpcClass = XposedHelpers.findClass(
                "com.android.server.display.DisplayPowerController",
                classLoader
            )
            XposedBridge.hookAllMethods(dpcClass, "setScreenBrightnessNormalMaximum", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = getTargetBrightness() ?: return
                    param.args[0] = target
                }
            })
            XposedBridge.hookAllMethods(dpcClass, "getScreenBrightnessNormalMaximum", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val target = getTargetBrightness() ?: return
                    param.result = target
                }
            })
            XposedBridge.log("$TAG: Hooked DisplayPowerController normal maximum brightness")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook DisplayPowerController: ${t.message}")
        }

        // 8. 拦截 DisplayManagerService.getBrightnessInfo，修改返回给外部的 brightnessMaximum
        try {
            val dmsClass = XposedHelpers.findClass(
                "com.android.server.display.DisplayManagerService",
                classLoader
            )
            XposedBridge.hookAllMethods(dmsClass, "getBrightnessInfo", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val info = param.result ?: return
                    val target = getTargetBrightness() ?: return
                    try {
                        XposedHelpers.setFloatField(info, "brightnessMaximum", target)
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.log("$TAG: Hooked DisplayManagerService.getBrightnessInfo")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook DisplayManagerService.getBrightnessInfo: ${t.message}")
        }
    }

    fun initSystemUI(classLoader: ClassLoader) {
        // 7. 拦截 SystemUI BrightnessController（新旧两套 QS 实现），拓宽滑块组件上限
        val hookCallback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val target = getTargetBrightness() ?: return
                try {
                    XposedHelpers.setFloatField(param.thisObject, "mBrightnessMax", target)
                } catch (_: Throwable) {}
            }
        }
        listOf(
            "com.android.systemui.qs.deprecated.brightness.BrightnessController",
            "com.android.systemui.settings.brightness.BrightnessController"
        ).forEach { name ->
            try {
                val bcClass = XposedHelpers.findClass(name, classLoader)
                XposedBridge.hookAllConstructors(bcClass, hookCallback)
                XposedBridge.log("$TAG: Hooked SystemUI $name")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: Failed to hook SystemUI $name: ${t.message}")
            }
        }
    }
}
