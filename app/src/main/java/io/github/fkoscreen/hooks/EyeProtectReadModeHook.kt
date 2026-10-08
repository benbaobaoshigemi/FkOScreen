package io.github.fkoscreen.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 明眸护眼（com.oplus.eyeprotect）独立解锁：
 *
 *  1. 阅读模式卡片（读墨模式 / ColorInk）：
 *       - 门控：g8.h.H0() = OplusFeatureConfigManager.hasFeature("oplus.software.display.read_mode")
 *       - 绕过：强制返回 true，令 ProtectEyesFragment 执行初始化阅读模式卡片与"淡彩"/"黑白"选项的代码分支
 *
 *  2. 纸质护眼滑块（Paper Texture）：
 *       - 门控：g8.h.R0() = "return false"（硬编码，原意为仅 realme 品牌启用）
 *       - 绕过：强制返回 true，令 ColorTemperaturePreference 加载含纸质纹理调节滑块的布局
 *
 *  3. OplusFeatureConfigManager 特性门控（系统级）：
 *       - 拦截 hasFeature("oplus.software.display.read_mode") → true
 *
 * 取证来源：
 *   e8.w (ProtectEyesFragment): 第 2271 行 g8.h.H0() 控制 key_category_color_ink 可见性
 *   com.oplus.eyeprotect.util.ColorTemperaturePreference 第 81 行 g8.h.R0() 控制布局选择
 *   g8.h 第 322 行 H0() = hasFeature("oplus.software.display.read_mode")
 *   g8.h 第 461 行 R0() = return false (硬编码)
 */
object EyeProtectReadModeHook {
    private const val TAG = "FkOScreen:EyeProtect"

    fun initSystemServer(classLoader: ClassLoader) {
        hookSystemServerReadMode(classLoader)
    }

    fun initEyeProtect(classLoader: ClassLoader) {
        hookProtectEyesUtil(classLoader)
        hookFeatureConfigManager(classLoader)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 0. system_server 底层特性门控解锁
    //    1) OplusDisplayFeatureConfig.isSupportReadMode -> true，使 MatrixConverter 注册 OplusFeatureReadMode
    //    2) OplusReadModeManager.isSupportReadMode -> true
    //    3) OplusFeatureConfigManager(oplus.software.display.read_mode) -> true
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookSystemServerReadMode(classLoader: ClassLoader) {
        try {
            val dispConfigClass = XposedHelpers.findClass(
                "com.android.server.display.config.OplusDisplayFeatureConfig",
                classLoader
            )
            XposedBridge.hookAllMethods(dispConfigClass, "isSupportReadMode", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.log("$TAG: Hooked OplusDisplayFeatureConfig.isSupportReadMode -> true")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusDisplayFeatureConfig: ${t.message}")
        }

        try {
            val readModeMgrClass = XposedHelpers.findClass(
                "com.android.server.display.color.eyeprotect.manager.OplusReadModeManager",
                classLoader
            )
            XposedBridge.hookAllConstructors(readModeMgrClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        XposedHelpers.setBooleanField(param.thisObject, "mIsSupportReadMode", true)
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.hookAllMethods(readModeMgrClass, "isSupportReadMode", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.log("$TAG: Hooked OplusReadModeManager -> true")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusReadModeManager: ${t.message}")
        }

        try {
            val configMgrClass = XposedHelpers.findClass(
                "com.oplus.content.OplusFeatureConfigManager",
                classLoader
            )
            XposedBridge.hookAllMethods(configMgrClass, "hasFeature", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.getOrNull(0) == "oplus.software.display.read_mode") {
                        param.result = true
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusFeatureConfigManager(read_mode) in system_server")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusFeatureConfigManager in system_server: ${t.message}")
        }

        // 4) OplusFeatureReadMode.modifyMatrix：软性计算淡彩色彩矩阵（饱和度约 35%），补足面板驱动不支持 panel feature 35 的缺陷
        try {
            val readModeFeatureClass = XposedHelpers.findClass(
                "com.android.server.display.color.feature.OplusFeatureReadMode",
                classLoader
            )
            // 列主序（OpenGL）淡彩矩阵：低饱和度 + 柔和纸质色温
            val lightColorMatrix = floatArrayOf(
                0.4882f, 0.1382f, 0.1382f, 0.0f,
                0.4649f, 0.8149f, 0.4649f, 0.0f,
                0.0469f, 0.0469f, 0.3969f, 0.0f,
                0.0f,    0.0f,    0.0f,    1.0f
            )

            XposedBridge.hookAllMethods(readModeFeatureClass, "modifyMatrix", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val isReadModeOpen = XposedHelpers.getBooleanField(param.thisObject, "mIsReadModeOpen")
                        val readMode = XposedHelpers.getIntField(param.thisObject, "mReadMode")
                        if (isReadModeOpen && readMode == 1) {
                            val inputMatrix = param.args.getOrNull(0) as? FloatArray
                            if (inputMatrix == null) {
                                param.result = lightColorMatrix.clone()
                            } else {
                                val result = FloatArray(16)
                                android.opengl.Matrix.multiplyMM(result, 0, lightColorMatrix, 0, inputMatrix, 0)
                                param.result = result
                            }
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: Error in modifyMatrix hook: ${e.message}")
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusFeatureReadMode.modifyMatrix for LIGHT_COLOR (35% saturation)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusFeatureReadMode.modifyMatrix: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A. g8.h (ProtectEyesUtil) 双门控绕过
    //    H0() → 阅读模式特性门控（原调用 hasFeature，失败返回 false）
    //    R0() → 纸质护眼品牌门控（硬编码 return false）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookProtectEyesUtil(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass("g8.h", classLoader)

            // 阅读模式门控
            XposedBridge.hookAllMethods(cls, "H0", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.log("$TAG: Hooked g8.h.H0() -> true (read mode enabled)")

            // 纸质护眼门控（硬编码 false，需要强制返回 true）
            XposedBridge.hookAllMethods(cls, "R0", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedBridge.log("$TAG: Hooked g8.h.R0() -> true (paper texture enabled)")

        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook g8.h: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B. OplusFeatureConfigManager 特性门控（com.oplus.eyeprotect 进程内）
    //    防止系统层 hasFeature 仍然返回 false 抵消 g8.h.H0() 的拦截
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookFeatureConfigManager(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.oplus.content.OplusFeatureConfigManager", classLoader
            )
            XposedBridge.hookAllMethods(cls, "hasFeature", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val feature = param.args.getOrNull(0) as? String
                    if (feature == "oplus.software.display.read_mode") {
                        param.result = true
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusFeatureConfigManager(read_mode) in eyeprotect")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook OplusFeatureConfigManager in eyeprotect: ${t.message}")
        }
    }
}
