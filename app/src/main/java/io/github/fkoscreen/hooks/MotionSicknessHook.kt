package io.github.fkoscreen.hooks

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 晕动舒缓（MotionSicknessRelief）完整解锁：
 *  - 恢复被 initViews() 强制抹除的"仅在车辆行驶时自动开启"选项（mode=1）
 *  - 让 mCarMovesMarkPreference / mPermanentMarkPreference 始终可见
 *
 * 此文件完全独立，不依赖阅读模式或刷新率特性，保证基线安全。
 *
 * 取证来源：
 *   MotionSicknessReliefSettingFragment.initViews()
 *   → 发现 mode==1 时会被强制改写为 mode=2 并关闭 enable，
 *     同时两个 Mark 选项从未被 setVisible(true)。
 */
object MotionSicknessHook {
    private const val TAG = "FkOScreen:MotionSickness"

    fun initSettings(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.oplus.settings.feature.motionsickness.MotionSicknessReliefSettingFragment",
                classLoader
            )

            // 1. initPreferences()：Preference 对象绑定完成后，直接强制可见
            XposedBridge.hookAllMethods(cls, "initPreferences", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val carPref  = XposedHelpers.getObjectField(param.thisObject, "mCarMovesMarkPreference")
                        val permPref = XposedHelpers.getObjectField(param.thisObject, "mPermanentMarkPreference")
                        carPref?.let  { XposedHelpers.callMethod(it, "setVisible", true) }
                        permPref?.let { XposedHelpers.callMethod(it, "setVisible", true) }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: initPreferences post-hook error: ${t.message}")
                    }
                }
            })

            // 2. initViews()：拦截整个方法，阻止 mode==1 被降级为 2 并关掉开关
            //    用最小侵入重写：仅调用 updateModePreferences + updateForceOffState，
            //    保留原始读取的状态，不修改任何 Settings 值。
            XposedBridge.hookAllMethods(cls, "initViews", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null  // 替换原实现，阻止 mode=1→2 的写入
                    try {
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return
                        val state = XposedHelpers.callStaticMethod(
                            param.thisObject.javaClass,
                            "getMotionSicknessReliefState",
                            context
                        ) as? Int ?: 0
                        val isEnabled = (state == 1)
                        XposedHelpers.callMethod(param.thisObject, "updateModePreferences", isEnabled)
                        XposedHelpers.callMethod(param.thisObject, "updateForceOffState")
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: initViews replacement error: ${t.message}")
                    }
                }
            })

            // 3. updateModePreferences()：追加确保两个选项始终可见且状态正确
            XposedBridge.hookAllMethods(cls, "updateModePreferences", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val isEnabled = param.args.getOrNull(0) as? Boolean ?: false
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return
                        val mode = XposedHelpers.callStaticMethod(
                            param.thisObject.javaClass,
                            "getMotionSicknessReliefMode",
                            context
                        ) as? Int ?: -1
                        val carPref  = XposedHelpers.getObjectField(param.thisObject, "mCarMovesMarkPreference")
                        val permPref = XposedHelpers.getObjectField(param.thisObject, "mPermanentMarkPreference")
                        if (carPref != null && permPref != null) {
                            XposedHelpers.callMethod(carPref,  "setChecked", mode == 1)
                            XposedHelpers.callMethod(permPref, "setChecked", mode == 2)
                            XposedHelpers.callMethod(carPref,  "setEnabled", isEnabled)
                            XposedHelpers.callMethod(permPref, "setEnabled", isEnabled)
                            XposedHelpers.callMethod(carPref,  "setVisible", true)
                            XposedHelpers.callMethod(permPref, "setVisible", true)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: updateModePreferences post-hook error: ${t.message}")
                    }
                }
            })

            XposedBridge.log("$TAG: Hooked MotionSicknessReliefSettingFragment OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: MotionSicknessReliefSettingFragment failed: ${t.message}")
        }
    }
}
