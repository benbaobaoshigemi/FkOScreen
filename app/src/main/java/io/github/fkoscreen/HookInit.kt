package io.github.fkoscreen

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.fkoscreen.hooks.*

class HookInit : IXposedHookLoadPackage {
    companion object {
        private const val TAG = "FkOScreen:HookInit"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                XposedBridge.log("$TAG: Hooking system_server...")
                BrightnessLimitHook.init(lpparam.classLoader)
                FossBypassHook.init(lpparam.classLoader)
                HdrRatioHook.init(lpparam.classLoader)
                ColorBallAnchorHook.initSystemServer(lpparam.classLoader)
                SettingsProviderHook.init(lpparam.classLoader)
                RefreshRate144Hook.initSystemServer(lpparam.classLoader)
                EyeProtectReadModeHook.initSystemServer(lpparam.classLoader)
            }
            "com.android.providers.settings" -> {
                XposedBridge.log("$TAG: Hooking SettingsProvider...")
                SettingsProviderHook.init(lpparam.classLoader)
            }
            "com.oplus.eyeprotect" -> {
                XposedBridge.log("$TAG: Hooking com.oplus.eyeprotect (ReadMode & PaperTexture)...")
                EyeProtectReadModeHook.initEyeProtect(lpparam.classLoader)
            }
            "com.android.settings" -> {
                XposedBridge.log("$TAG: Hooking com.android.settings (UI & Controls)...")
                DarkModeStyleHook.init(lpparam.classLoader)
                ColorBallAnchorHook.initSettings(lpparam.classLoader)
                RefreshRate144Hook.initSettings(lpparam.classLoader)
                MotionSicknessHook.initSettings(lpparam.classLoader)
            }
            "com.android.systemui" -> {
                XposedBridge.log("$TAG: Hooking com.android.systemui (HDR & QuickSettings)...")
                HdrRatioHook.init(lpparam.classLoader)
                BrightnessLimitHook.initSystemUI(lpparam.classLoader)
            }
        }
    }
}
