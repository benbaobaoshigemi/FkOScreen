package io.github.fkoscreen.hooks

import android.content.ContentResolver
import android.provider.Settings
import android.util.Pair
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.fkoscreen.ConfigManager

object ColorBallAnchorHook {
    private const val TAG = "FkOScreen:ColorBallAnchor"

    // 1. 在 com.android.settings 中解除置灰禁用
    fun initSettings(classLoader: ClassLoader) {
        // A. Hook ScreenColorTemperateBallPreference
        try {
            val prefClass = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.screencolortemp.ScreenColorTemperateBallPreference",
                classLoader
            )
            XposedBridge.hookAllMethods(prefClass, "setEnabled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (ConfigManager.isColorBallAnchorEnabled()) {
                        param.args[0] = true
                    }
                }
            })
            XposedBridge.hookAllMethods(prefClass, "onBindViewHolder", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isColorBallAnchorEnabled()) return
                    try {
                        XposedHelpers.callMethod(param.thisObject, "setEnabled", true)
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: setEnabled true error: ${e.message}")
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked ScreenColorTemperateBallPreference")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ScreenColorTemperateBallPreference: ${t.message}")
        }

        // B. Hook ScreenColorTemperateBall (View 层)
        try {
            val ballClass = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.screencolortemp.ScreenColorTemperateBall",
                classLoader
            )
            XposedBridge.hookAllMethods(ballClass, "setEnabled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (ConfigManager.isColorBallAnchorEnabled()) {
                        param.args[0] = true
                    }
                }
            })
            XposedBridge.hookAllMethods(ballClass, "isEnabled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (ConfigManager.isColorBallAnchorEnabled()) {
                        param.result = true
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked ScreenColorTemperateBall view")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ScreenColorTemperateBall: ${t.message}")
        }

        // C. Hook ColorModeFragment (Fragment 页面控制器层)
        try {
            val fragmentClass = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.protecteyes.ColorModeFragment",
                classLoader
            )
            XposedBridge.hookAllMethods(fragmentClass, "updateScreenColorTemperatureState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isColorBallAnchorEnabled()) return
                    try {
                        val ballPref = XposedHelpers.getObjectField(param.thisObject, "mScreenColorTempBallPref")
                        if (ballPref != null) {
                            XposedHelpers.callMethod(ballPref, "setEnabled", true)
                        }
                        // 隐藏置灰提示语（如“护眼模式开启时不可调节”或“环境色自适应开启时不可调节”）
                        val tipPref = XposedHelpers.getObjectField(param.thisObject, "mScreenColorSummary")
                        if (tipPref != null) {
                            XposedHelpers.callMethod(tipPref, "setVisible", false)
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: updateScreenColorTemperatureState error: ${e.message}")
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked ColorModeFragment.updateScreenColorTemperatureState")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook ColorModeFragment: ${t.message}")
        }
    }

    @Volatile private var sCachedBallR: Int = 1000
    @Volatile private var sCachedBallG: Int = 1000
    @Volatile private var sCachedBallB: Int = 1000
    @Volatile private var sContentResolver: ContentResolver? = null

    private fun refreshBallRgbIfDefault() {
        if (sCachedBallR == 1000 && sCachedBallG == 1000 && sCachedBallB == 1000) {
            val resolver = sContentResolver ?: try {
                val atClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                val app = XposedHelpers.callStaticMethod(atClass, "currentApplication") as? android.app.Application
                app?.contentResolver?.also { sContentResolver = it }
            } catch (_: Throwable) { null }
            if (resolver != null) {
                try {
                    val str = Settings.System.getString(resolver, "eyeprotect_rgb")
                    if (!str.isNullOrEmpty()) {
                        val parts = str.split(",")
                        if (parts.size >= 3) {
                            sCachedBallR = parts[0].trim().toIntOrNull() ?: 1000
                            sCachedBallG = parts[1].trim().toIntOrNull() ?: 1000
                            sCachedBallB = parts[2].trim().toIntOrNull() ?: 1000
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    // 2. 在 system_server 中将色温球基准注入底层 CCT 插值与实时下发链路（自适应 ColorOS 17 与 ColorOS 16）
    fun initSystemServer(classLoader: ClassLoader) {
        var isColorOS17 = false

        // ========================================================
        // 分支 A: ColorOS 17 全新显示色彩管线 (DisplayColorController / OplusColorConfig)
        // ========================================================
        try {
            val colorConfigClass = XposedHelpers.findClass(
                "com.android.server.display.color.config.OplusColorConfig",
                classLoader
            )
            isColorOS17 = true

            // 核心 Hook 1 (ColorOS 17): 拦截 CCT 插值结果，注入色温球白点物理基准
            XposedBridge.hookAllMethods(colorConfigClass, "interpolate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isColorBallAnchorEnabled()) return
                    val pair = param.result as? Pair<*, *> ?: return
                    val mainRGB = pair.first ?: return
                    refreshBallRgbIfDefault()

                    val ballR = sCachedBallR
                    val ballG = sCachedBallG
                    val ballB = sCachedBallB
                    if (ballR == 1000 && ballG == 1000 && ballB == 1000) return

                    val rGain = (ballR / 1000f).coerceIn(0.1f, 1.0f)
                    val gGain = (ballG / 1000f).coerceIn(0.1f, 1.0f)
                    val bGain = (ballB / 1000f).coerceIn(0.1f, 1.0f)

                    val origR = XposedHelpers.getIntField(mainRGB, "red")
                    val origG = XposedHelpers.getIntField(mainRGB, "green")
                    val origB = XposedHelpers.getIntField(mainRGB, "blue")

                    XposedHelpers.setIntField(mainRGB, "red", Math.round(origR * rGain).coerceIn(0, 1000))
                    XposedHelpers.setIntField(mainRGB, "green", Math.round(origG * gGain).coerceIn(0, 1000))
                    XposedHelpers.setIntField(mainRGB, "blue", Math.round(origB * bGain).coerceIn(0, 1000))

                    val subRGB = pair.second
                    if (subRGB != null) {
                        val subOrigR = XposedHelpers.getIntField(subRGB, "red")
                        val subOrigG = XposedHelpers.getIntField(subRGB, "green")
                        val subOrigB = XposedHelpers.getIntField(subRGB, "blue")
                        XposedHelpers.setIntField(subRGB, "red", Math.round(subOrigR * rGain).coerceIn(0, 1000))
                        XposedHelpers.setIntField(subRGB, "green", Math.round(subOrigG * gGain).coerceIn(0, 1000))
                        XposedHelpers.setIntField(subRGB, "blue", Math.round(subOrigB * bGain).coerceIn(0, 1000))
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusColorConfig.interpolate for ColorOS 17 anchor scaling")

            // 核心 Hook 2 (ColorOS 17): 拦截 DisplayColorController，在拖拽色温球时解除 CCT 抑制并触发 0ms 刷新
            val colorControllerClass = XposedHelpers.findClass(
                "com.android.server.display.color.control.DisplayColorController",
                classLoader
            )
            XposedBridge.hookAllConstructors(colorControllerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cr = XposedHelpers.getObjectField(param.thisObject, "mContentResolver") as? ContentResolver
                    if (cr != null) {
                        sContentResolver = cr
                    }
                }
            })
            XposedBridge.hookAllMethods(colorControllerClass, "updateEyeProtectRGB", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val rgb = param.args.getOrNull(0)
                    if (rgb != null) {
                        sCachedBallR = XposedHelpers.getIntField(rgb, "red")
                        sCachedBallG = XposedHelpers.getIntField(rgb, "green")
                        sCachedBallB = XposedHelpers.getIntField(rgb, "blue")
                    }
                    if (!ConfigManager.isColorBallAnchorEnabled()) return
                    try {
                        val colorContext = XposedHelpers.getObjectField(param.thisObject, "mColorContext") ?: return
                        val isEyeProtect = XposedHelpers.callMethod(colorContext, "isEyeProtectEnabled") as? Boolean ?: false
                        val isCctReg = XposedHelpers.callMethod(colorContext, "isColorTemperatureRegulationEnabled") as? Boolean ?: false
                        val isRhythm = XposedHelpers.callMethod(colorContext, "isComfortableRhythmEnabled") as? Boolean ?: false
                        if (isEyeProtect || isCctReg || isRhythm) {
                            val matrixConverter = XposedHelpers.getObjectField(param.thisObject, "mMatrixConverter")
                            if (matrixConverter != null) {
                                XposedHelpers.callMethod(matrixConverter, "apply")
                            }
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: DisplayColorController.updateEyeProtectRGB error: ${e.message}")
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked DisplayColorController for ColorOS 17 real-time color drag response")

            // 核心 Hook 3 (ColorOS 17): 捕获 OplusFeatureRgbBall 生命周期，提取 ContentResolver 与初始白点
            val featureRgbBallClass = XposedHelpers.findClass(
                "com.android.server.display.color.feature.postprocess.OplusFeatureRgbBall",
                classLoader
            )
            XposedBridge.hookAllMethods(featureRgbBallClass, "onSetUp", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cr = XposedHelpers.getObjectField(param.thisObject, "mContentResolver") as? ContentResolver
                    if (cr != null) {
                        sContentResolver = cr
                    }
                    refreshBallRgbIfDefault()
                }
            })
            XposedBridge.log("$TAG: Hooked OplusFeatureRgbBall.onSetUp for ColorOS 17")
        } catch (_: Throwable) {}

        if (isColorOS17) {
            XposedBridge.log("$TAG: Initialized ColorOS 17 display color pipeline hooks successfully")
            return
        }

        // ========================================================
        // 分支 B: ColorOS 16 经典显示色彩管线 (OplusRgbBallManager / CCTCoefficientUtil)
        // ========================================================
        val rgbBallClassName = "com.android.server.display.color.eyeprotect.manager.OplusRgbBallManager"
        val cctUtilClassName = "com.android.server.display.color.eyeprotect.util.CCTCoefficientUtil"
        val protectEyesUtilClassName = "com.android.server.display.color.eyeprotect.util.ProtectEyesUtil"
        val reduceSaturationUtilClassName = "com.android.server.display.color.eyeprotect.util.OplusReduceSaturationUtil"

        val rgbBallClass = try {
            XposedHelpers.findClass(rgbBallClassName, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to find $rgbBallClassName: ${t.message}")
            return
        }

        val cctUtilClass = try {
            XposedHelpers.findClass(cctUtilClassName, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to find $cctUtilClassName: ${t.message}")
            return
        }

        val protectEyesUtilClass = try {
            XposedHelpers.findClass(protectEyesUtilClassName, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to find $protectEyesUtilClassName: ${t.message}")
            null
        }

        val reduceSaturationUtilClass = try {
            XposedHelpers.findClass(reduceSaturationUtilClassName, classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to find $reduceSaturationUtilClassName: ${t.message}")
            null
        }

        // 核心 Hook 1 (ColorOS 16): 拦截所有护眼/自适应色彩场景下的 CCT 插值结果，注入色温球基准 (M_cct_anchored = M_cct * M_ball)
        try {
            XposedBridge.hookAllMethods(cctUtilClass, "interpolate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isColorBallAnchorEnabled()) return
                    val pair = param.result as? Pair<*, *> ?: return
                    val mainRGB = pair.first ?: return

                    // 获取当前系统已设置的色温球物理白点基准
                    val ballMgr = XposedHelpers.callStaticMethod(rgbBallClass, "getInstance") ?: return
                    val curRGB = XposedHelpers.getObjectField(ballMgr, "mCurRGB") ?: return
                    val ballR = XposedHelpers.getIntField(curRGB, "red")
                    val ballG = XposedHelpers.getIntField(curRGB, "green")
                    val ballB = XposedHelpers.getIntField(curRGB, "blue")

                    if (ballR == 1000 && ballG == 1000 && ballB == 1000) return

                    val rGain = (ballR / 1000f).coerceIn(0.1f, 1.0f)
                    val gGain = (ballG / 1000f).coerceIn(0.1f, 1.0f)
                    val bGain = (ballB / 1000f).coerceIn(0.1f, 1.0f)

                    val origR = XposedHelpers.getIntField(mainRGB, "red")
                    val origG = XposedHelpers.getIntField(mainRGB, "green")
                    val origB = XposedHelpers.getIntField(mainRGB, "blue")

                    XposedHelpers.setIntField(mainRGB, "red", Math.round(origR * rGain).coerceIn(0, 1000))
                    XposedHelpers.setIntField(mainRGB, "green", Math.round(origG * gGain).coerceIn(0, 1000))
                    XposedHelpers.setIntField(mainRGB, "blue", Math.round(origB * bGain).coerceIn(0, 1000))

                    val subRGB = pair.second
                    if (subRGB != null) {
                        val subOrigR = XposedHelpers.getIntField(subRGB, "red")
                        val subOrigG = XposedHelpers.getIntField(subRGB, "green")
                        val subOrigB = XposedHelpers.getIntField(subRGB, "blue")
                        XposedHelpers.setIntField(subRGB, "red", Math.round(subOrigR * rGain).coerceIn(0, 1000))
                        XposedHelpers.setIntField(subRGB, "green", Math.round(subOrigG * gGain).coerceIn(0, 1000))
                        XposedHelpers.setIntField(subRGB, "blue", Math.round(subOrigB * bGain).coerceIn(0, 1000))
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked CCTCoefficientUtil.interpolate for anchor scaling")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook CCTCoefficientUtil.interpolate: ${t.message}")
        }

        // 核心 Hook 2 (ColorOS 16): 解除护眼/自适应模式下拖拽色温球的熔断阻断，实现 0ms 实时下发至硬件管线
        try {
            XposedBridge.hookAllMethods(rgbBallClass, "updateEyeProtectRGB", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isColorBallAnchorEnabled()) return
                    val resolver = param.args[0] as? ContentResolver ?: return
                    val userHandle = param.args[1] as Int

                    val isOther = XposedHelpers.callMethod(
                        param.thisObject,
                        "isOtherColorTemperatureChangeScene",
                        resolver,
                        userHandle
                    ) as? Boolean ?: false

                    if (isOther) {
                        // 1. 同步加载最新的 eyeprotect_rgb 到 mCurRGB
                        XposedHelpers.callMethod(param.thisObject, "initCurRGB")

                        // 2. 中止可能正在执行的过渡动画，确保手势跟手无卡顿
                        try {
                            val animator = XposedHelpers.getObjectField(param.thisObject, "mValueAnimator") as? android.animation.ValueAnimator
                            if (animator != null && animator.isRunning) {
                                animator.cancel()
                            }
                            XposedHelpers.setBooleanField(param.thisObject, "mRGBAnimating", false)
                        } catch (_: Throwable) {}

                        // 3. 刷新灰阶/阅读模式状态
                        try {
                            XposedHelpers.callMethod(param.thisObject, "updateGraySacleMode")
                        } catch (_: Throwable) {}

                        // 4. 获取当前生效的 CCT
                        val currentCCT = if (protectEyesUtilClass != null) {
                            try {
                                XposedHelpers.callStaticMethod(
                                    protectEyesUtilClass,
                                    "getDisplayCCT",
                                    resolver,
                                    6500,
                                    userHandle
                                ) as Int
                            } catch (_: Throwable) { 6500 }
                        } else 6500

                        // 5. 执行插值（经过 interpolate hook 会自动附带色温球缩放基准）
                        val pair = XposedHelpers.callStaticMethod(
                            cctUtilClass,
                            "interpolate",
                            currentCCT.toFloat()
                        ) as? Pair<*, *>

                        if (pair != null && pair.first != null && reduceSaturationUtilClass != null) {
                            val mainRGB = pair.first
                            val mMode = XposedHelpers.getIntField(param.thisObject, "mMode")
                            // 立即下发至屏幕管线，0ms 触控响应
                            XposedHelpers.callStaticMethod(reduceSaturationUtilClass, "setRGB", mainRGB, mMode)
                            try {
                                XposedHelpers.callMethod(param.thisObject, "updateAnimatingRGB", currentCCT)
                            } catch (_: Throwable) {}
                        }

                        // 拦截原有空 return 熔断
                        param.result = null
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusRgbBallManager.updateEyeProtectRGB for realtime drag response")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusRgbBallManager.updateEyeProtectRGB: ${t.message}")
        }
    }
}
