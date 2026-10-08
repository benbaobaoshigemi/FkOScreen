package io.github.fkoscreen.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SettingsProviderHook {
    private const val TAG = "FkOScreen:SettingsProviderHook"

    private val ALLOWED_KEYS = hashSetOf(
        "eyeprotect_rgb",
        "color_ball_last_pointx",
        "color_ball_last_pointy",
        "color_temperate_ball_mode",
        "manual_hbm_brightness",
        "manual_peak_brightness",
        "foss_bypass",
        "hdr_ratio_fix",
        "dark_mode_styles",
        "color_ball_anchor",
        "thermal_limit_bypass",
        "manual_1250_brightness",
        "manual_1600_brightness"
    )

    fun init(classLoader: ClassLoader) {
        val spClass = try {
            XposedHelpers.findClass("com.android.providers.settings.SettingsProvider", classLoader)
        } catch (_: Throwable) {
            return
        }

        try {
            XposedBridge.hookAllMethods(
                spClass,
                "enforceRestrictedSystemSettingsMutationForCallingPackage",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(1) as? String
                        if (name != null && ALLOWED_KEYS.contains(name)) {
                            // 允许修改色彩与色温球相关系统设置，直接绕过抛出异常
                            param.result = null
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked SettingsProvider.enforceRestrictedSystemSettingsMutationForCallingPackage")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook enforceRestrictedSystemSettingsMutation: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(
                spClass,
                "warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(2) as? String
                        if (name != null && ALLOWED_KEYS.contains(name)) {
                            param.result = null
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked SettingsProvider.warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook warnOrThrowForUndesiredSecureSettingsMutation: ${t.message}")
        }
    }
}
