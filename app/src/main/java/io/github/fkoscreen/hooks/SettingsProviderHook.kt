package io.github.fkoscreen.hooks

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.fkoscreen.ConfigManager

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
        "aod_brightness_scale",
        "manual_1250_brightness",
        "manual_1600_brightness"
    )

    private val MODULE_PACKAGES = setOf(ConfigManager.LEGACY_PACKAGE_NAME, ConfigManager.PACKAGE_NAME)

    // 当前线程正在处理"本模块写白名单键"的 mutateSystemSetting
    private val sModuleWrite = ThreadLocal<Boolean>()

    fun init(classLoader: ClassLoader) {
        val spClass = try {
            XposedHelpers.findClass("com.android.providers.settings.SettingsProvider", classLoader)
        } catch (_: Throwable) {
            return
        }

        // 模块界面没有"修改系统设置"授权：mutateSystemSetting 会因 checkAndNoteWriteSettingsOperation 返回 false
        // 而静默拒绝写入，hook 端就永远读不到界面里的开关。仅对本模块写白名单键放行这一项检查。
        try {
            XposedBridge.hookAllMethods(spClass, "mutateSystemSetting", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(0) as? String ?: return
                    if (!ALLOWED_KEYS.contains(name)) return
                    val pkg = try {
                        XposedHelpers.callMethod(param.thisObject, "getCallingPackage") as? String
                    } catch (_: Throwable) {
                        null
                    }
                    if (pkg in MODULE_PACKAGES) sModuleWrite.set(true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    sModuleWrite.remove()
                }
            })
            XposedBridge.hookAllMethods(android.provider.Settings::class.java, "checkAndNoteWriteSettingsOperation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (sModuleWrite.get() == true) param.result = true
                }
            })
            XposedBridge.log("$TAG: Hooked SettingsProvider.mutateSystemSetting (module write access)")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook mutateSystemSetting: ${t.message}")
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
