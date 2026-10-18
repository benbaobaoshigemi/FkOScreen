package io.github.fkoscreen.hooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.provider.Settings
import android.util.SparseArray
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 144Hz 屏幕刷新率完整解锁：
 *  - system_server：让 OplusRefreshRatePolicyImpl 在"极速 144Hz"档位下从源头产出 144Hz（rateId 4），
 *    而不是先算出 120Hz 再在出口改写成 144Hz。这样 ADFR/LTPO、SF 侧 VRR 服务拿到的基准与实际一致，
 *    LTPO 的空闲降频照常由 SF 负责；同时屏蔽温控/高亮/DC/外部组件对滑动帧率的压制。
 *  - com.android.settings：解锁 ScreenRefreshUtils / ExtremeRefreshUtils / SysFeatureUtils UI 门控，
 *    "支持高刷新率的应用"默认全部勾选，取消勾选回落到 120Hz。
 *
 * 所有 system_server 侧改动仅在用户选择 144Hz（oplus_customize_screen_refresh_rate == 8）时生效，
 * 切回 120Hz 后完全走系统原生逻辑。
 *
 * ID 速查（OplusRefreshRateConstants）：
 *  rateId   ：1=90  2=60  3=120  4=144
 *  设置档位 ：3=120Hz  8=极速档（Settings 选 144Hz 时写入 8），8 对应配置表下标 4
 *  投票优先级：数值越大越优先，见 OplusRefreshRateCore.Vote
 */
object RefreshRate144Hook {
    private const val TAG = "FkOScreen:RefreshRate144"

    private const val REFRESH_RATE_SETTING = "oplus_customize_screen_refresh_rate"
    private const val SETTING_MODE_EXTREME = 8
    private const val SETTING_INDEX_EXTREME = 4
    private const val RATE_ID_120 = 3
    private const val RATE_ID_144 = 4
    private const val RATE_144 = 144.0f

    // 这些投票代表应用/游戏的显式诉求或真正需要低帧的场景，原样保留：
    // 0 窗口（源头已修正为 144）、10 应用请求、25 游戏、70 MEMC 插帧、100 OSync、110 投屏、121 AOD
    private val KEEP_AS_IS_PRIORITIES = setOf(0, 10, 25, 70, 100, 110, 121)

    @Volatile private var sExtremeMode = false
    @Volatile private var sLastSuppressed = ""

    private val sGamePkgCache = ConcurrentHashMap<String, Boolean>()
    private var sVoteForRateAndMin: Method? = null
    private var sVoteForRateAndSource: Method? = null

    fun initSystemServer(classLoader: ClassLoader) {
        hookSettingsObserver(classLoader)
        hookOplusRefreshRateConfigs(classLoader)
        hookPreferredRefreshRateData(classLoader)
        hookOplusRefreshRatePolicyImpl(classLoader)
        hookUnlockRefresh(classLoader)
        hookPickRefreshRateData(classLoader)
        hookRefreshRateCore(classLoader)
        hookDCController(classLoader)
        hookLowRefreshRate(classLoader)
        hookExternalRefreshRateManager(classLoader)
        hookOplusFeatureConfigManager(classLoader)
    }

    fun initSettings(classLoader: ClassLoader) {
        hookScreenRefreshUtils(classLoader)
        hookExtremeRefreshUtils(classLoader)
        hookSysFeatureUtils(classLoader)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A. SettingsObserver：跟踪当前是否处于 144Hz 档位  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookSettingsObserver(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl\$SettingsObserver", classLoader
            )
            XposedBridge.hookAllMethods(cls, "onRefreshRateSettingChange", object : XC_MethodHook() {
                // before：方法内部会同步触发窗口重算，必须先切换标志
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                        val mode = Settings.Secure.getInt(context.contentResolver, REFRESH_RATE_SETTING, 0)
                        updateExtremeMode(mode == SETTING_MODE_EXTREME)
                    } catch (_: Throwable) {}
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val mode = XposedHelpers.getIntField(param.thisObject, "mCurrentPickMode")
                        updateExtremeMode(mode == SETTING_MODE_EXTREME)
                    } catch (_: Throwable) {}
                }
            })
            XposedBridge.log("$TAG: Hooked SettingsObserver.onRefreshRateSettingChange OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: SettingsObserver failed: ${t.message}")
        }
    }

    private fun updateExtremeMode(extreme: Boolean) {
        if (sExtremeMode != extreme) {
            sExtremeMode = extreme
            sLastSuppressed = ""
            XposedBridge.log("$TAG: extreme 144Hz mode = $extreme")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B. OplusRefreshRateConfigs：极速档的默认刷新率与上限  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookOplusRefreshRateConfigs(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateConfigs", classLoader
            )
            // 仅极速档默认 144；120 档必须保持原值，否则切回 120 后仍按 144 运行
            XposedBridge.hookAllMethods(cls, "getDefaultRateId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if ((param.args.getOrNull(0) as? Int) == SETTING_MODE_EXTREME) {
                        param.result = RATE_ID_144
                    }
                }
            })
            // 配置上限 120 会把缩放/画布窗口及 0 号请求压回 120
            XposedBridge.hookAllMethods(cls, "getDefaultMaxRate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (sExtremeMode) param.result = RATE_144
                }
            })
            XposedBridge.log("$TAG: Hooked OplusRefreshRateConfigs OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OplusRefreshRateConfigs failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C. PreferredRefreshRateData：极速档列（下标 4）的应用默认值 120 → 144  （system_server）
    //    XML 只填写 0~3 列，第 4 列恒为构造默认值 3（120Hz）。这里改的是"默认值"，
    //    用户在设置里对单个应用的覆盖值走 getOverridePreferredRefreshRateId，不受影响。
    //    设置页的勾选状态也读取这里，因此全部应用默认显示为已勾选。
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookPreferredRefreshRateData(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateConstants\$PreferredRefreshRateData", classLoader
            )
            XposedBridge.hookAllMethods(cls, "getPreferredRefreshRateId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // (settingMode) 或 (screenMode, settingMode)：下标总是最后一个参数
                    val settingIndex = param.args.lastOrNull() as? Int ?: return
                    if (settingIndex == SETTING_INDEX_EXTREME && (param.result as? Int) == RATE_ID_120) {
                        param.result = RATE_ID_144
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked PreferredRefreshRateData OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: PreferredRefreshRateData failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // D. OplusRefreshRatePolicyImpl  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookOplusRefreshRatePolicyImpl(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", classLoader
            )

            // 锁屏、指纹（FOD）、近距闪光等场景的"最高档"在原生逻辑里写死为 120
            fun raise120To144(name: String) {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (sExtremeMode && (param.result as? Int) == RATE_ID_120) {
                            param.result = RATE_ID_144
                        }
                    }
                })
            }
            raise120To144("getKeyguardRefreshRateId")
            raise120To144("getMaxRateIdInLimit144")

            // 支持锁屏单独配置刷新率的机型（mSupportLockScreenConfigRate），锁屏/AOD 时不走
            // getKeyguardRefreshRateId，而是查 systemui 的 NotificationShade 配置，表里写的是 120
            XposedBridge.hookAllMethods(cls, "getPkgRefreshRateId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (sExtremeMode && (param.result as? Int) == RATE_ID_120 &&
                        param.args.getOrNull(0) == "com.android.systemui" &&
                        param.args.getOrNull(1) == "NotificationShade"
                    ) {
                        param.result = RATE_ID_144
                    }
                }
            })

            // 温控：不写入 PRIORITY_HIGH_TEMPERATURE 投票，也不通知 ADFR 进入高温策略
            XposedBridge.hookAllMethods(cls, "setHighTemperatureStatusWithoutUpdate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (sExtremeMode && (param.args.getOrNull(0) as? Int ?: 0) != 0) {
                        XposedBridge.log("$TAG: suppress high temperature limit, rateId=${param.args.getOrNull(1)}")
                        param.args[0] = 0
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusRefreshRatePolicyImpl OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OplusRefreshRatePolicyImpl failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // D2. 解锁后重新拉满  （system_server）
    //    SF 的 OTI（触控空闲）投票 oti-hw 只在显示模式切换时重读当前上限。息屏时上限被压到
    //    120（AOD 动画）或 60（Doze），解锁后上限回到 144，但若当前模式恰好等于 OTI 的投票，
    //    就没有模式切换，OTI 一直停在旧上限。原生上限本就是 120，看不出来。打开应用时的
    //    startAppSwitchBoost 会把 min=max 设为 144 两秒，触发模式切换，这里在解锁后补一次。
    // ─────────────────────────────────────────────────────────────────────────
    private const val UNLOCK_BOOST_DELAY_MS = 1200L

    private fun hookUnlockRefresh(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", classLoader
            )
            XposedBridge.hookAllMethods(cls, "onSystemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val policy = param.thisObject
                    val context = XposedHelpers.getObjectField(policy, "mContext") as Context
                    val handler = XposedHelpers.getObjectField(policy, "mHandler") as Handler
                    context.registerReceiver(object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            if (!sExtremeMode) return
                            // 解锁动画期间 startAppSwitchBoost 会直接放弃，等动画结束
                            handler.postDelayed({ boostAfterUnlock(policy) }, UNLOCK_BOOST_DELAY_MS)
                        }
                    }, IntentFilter(Intent.ACTION_USER_PRESENT), null, handler)
                    XposedBridge.log("$TAG: unlock refresh receiver registered")
                }
            })
            XposedBridge.log("$TAG: Hooked unlock refresh OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: unlock refresh failed: ${t.message}")
        }
    }

    private fun boostAfterUnlock(policy: Any) {
        if (!sExtremeMode) return
        try {
            val display = XposedHelpers.getObjectField(policy, "mDisplayContent")
            val wms = XposedHelpers.getObjectField(policy, "mWmService")
            val lock = XposedHelpers.getObjectField(wms, "mGlobalLock")
            synchronized(lock) {
                val top = XposedHelpers.callMethod(display, "topRunningActivity") ?: return
                XposedHelpers.callMethod(policy, "startAppSwitchBoost", top)
            }
            XposedBridge.log("$TAG: refresh boost after unlock")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: refresh boost after unlock failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // E. PickRefreshRateData：窗口级别的降帧分支  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookPickRefreshRateData(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl\$PickRefreshRateData", classLoader
            )
            // 输入法 / 录音中的应用 / 相机占用中的应用 → 60Hz；录音包名列表在停止事件丢失时会残留
            XposedBridge.hookAllMethods(cls, "getSpecialLowRefreshRateId", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (sExtremeMode) param.result = param.args[1]
                }
            })
            // 存在悬浮窗/缩放窗时把全屏窗口压到悬浮窗档位（常见 90Hz）
            XposedBridge.hookAllMethods(cls, "adjustRefreshForFloatAndZoomWindow", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (sExtremeMode) param.result = param.args[1]
                }
            })
            // DC 低亮度模式下冻结为上一次的档位，若上一次恰好是 60/90 会一直卡住
            XposedBridge.hookAllMethods(cls, "dontUpdateRefreshRate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (sExtremeMode) param.result = false
                }
            })
            XposedBridge.log("$TAG: Hooked PickRefreshRateData OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: PickRefreshRateData failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // F. OplusRefreshRateCore：投票仲裁  （system_server）
    //    在"读取"时过滤投票，不改写系统记录的投票本身，保证各模块的重复请求判断、
    //    死亡回调清理等簿记逻辑与原生一致；切回 120 档后立即恢复原生仲裁。
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookRefreshRateCore(classLoader: ClassLoader) {
        try {
            val coreCls = XposedHelpers.findClass("com.android.server.wm.OplusRefreshRateCore", classLoader)
            val voteCls = XposedHelpers.findClass("com.android.server.wm.OplusRefreshRateCore\$Vote", classLoader)
            sVoteForRateAndMin = XposedHelpers.findMethodExact(
                voteCls, "forRefreshRate", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
            )
            sVoteForRateAndSource = XposedHelpers.findMethodExact(
                voteCls, "forRefreshRate", Float::class.javaPrimitiveType, String::class.java
            )

            XposedBridge.hookAllMethods(coreCls, "getPerfectRefreshRate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!sExtremeMode) return
                    val votes = param.args[0] as? SparseArray<*> ?: return
                    param.args[0] = filterVotes(votes)
                }
            })
            // 最高优先级决定 ADFR 是否停用、是否进入高亮模式分支等，需与过滤后的结果一致
            XposedBridge.hookAllMethods(coreCls, "getCurMaxVotePriority", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!sExtremeMode) return
                    val votes = XposedHelpers.getObjectField(param.thisObject, "mActiveVotes") as? SparseArray<*> ?: return
                    var max = 0
                    for (i in 0 until votes.size()) {
                        val priority = votes.keyAt(i)
                        if (priority > max && !isSuppressed(priority, votes.valueAt(i))) max = priority
                    }
                    param.result = max
                }
            })
            XposedBridge.log("$TAG: Hooked OplusRefreshRateCore OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OplusRefreshRateCore failed: ${t.message}")
        }
    }

    private fun voteRate(vote: Any?): Float =
        if (vote == null) 0f else XposedHelpers.getFloatField(vote, "mRefreshRate")

    // 非保留优先级上低于 120 的投票都是降帧限制（温控、高亮、DC、测评/省电、外部组件请求等）
    private fun isSuppressed(priority: Int, vote: Any?): Boolean =
        vote != null && priority !in KEEP_AS_IS_PRIORITIES && voteRate(vote) < 119f

    private fun filterVotes(votes: SparseArray<*>): SparseArray<Any?> {
        val result = SparseArray<Any?>(votes.size())
        val suppressed = StringBuilder()
        for (i in 0 until votes.size()) {
            val priority = votes.keyAt(i)
            val vote = votes.valueAt(i)
            when {
                vote == null || priority in KEEP_AS_IS_PRIORITIES -> result.put(priority, vote)
                isSuppressed(priority, vote) -> suppressed.append(priority).append('@').append(voteRate(vote)).append(' ')
                voteRate(vote) < 143f -> result.put(priority, raiseVoteTo144(vote))
                else -> result.put(priority, vote)
            }
        }
        val summary = suppressed.toString()
        if (summary != sLastSuppressed) {
            sLastSuppressed = summary
            if (summary.isNotEmpty()) XposedBridge.log("$TAG: suppress refresh rate votes [ $summary]")
        }
        return result
    }

    // 场景"最高档"投票（启动加速、指纹、锁屏等）在原生配置里是 120，抬到 144
    private fun raiseVoteTo144(vote: Any): Any {
        return try {
            val rate = XposedHelpers.getFloatField(vote, "mRefreshRate")
            val minRate = XposedHelpers.getFloatField(vote, "mMinRefreshRate")
            val source = XposedHelpers.getObjectField(vote, "mRequestSource") as? String
            if (source != null) {
                sVoteForRateAndSource?.invoke(null, RATE_144, source) ?: vote
            } else {
                val newMin = if (Math.abs(minRate - rate) < 1f) RATE_144 else minRate
                sVoteForRateAndMin?.invoke(null, RATE_144, newMin) ?: vote
            }
        } catch (_: Throwable) {
            vote
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // G. OplusRefreshRateDCController：DC 调光时的最高档限制  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookDCController(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateDCController", classLoader
            )
            XposedBridge.hookAllMethods(cls, "getDCLimitRefreshRateId", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (sExtremeMode) param.result = param.args[0]
                }
            })
            XposedBridge.log("$TAG: Hooked OplusRefreshRateDCController OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OplusRefreshRateDCController failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // H. OplusLowRefreshRate：绕过策略直接向 SF(oplus_vrr_service) 下发的按应用限帧
    //    （录音/语音场景、应用低帧配置、近距闪光），fps=0 表示解除限制  （system_server）
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookLowRefreshRate(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass("com.android.server.wm.OplusLowRefreshRate", classLoader)
            XposedBridge.hookAllMethods(cls, "setFps", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val fps = param.args.getOrNull(0) as? Float ?: return
                    if (sExtremeMode && fps > 0f) {
                        XposedBridge.log("$TAG: suppress low refresh rate $fps for ${param.args.getOrNull(1)}")
                        param.args[0] = 0f
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OplusLowRefreshRate OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OplusLowRefreshRate failed: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // I. OPlusExternalRefreshRateManager：外部组件对 SF 的帧率上限请求  （system_server）
    //    FRTC/CVT 是按应用的帧率封顶，会让整个应用（包括滑动）被锁在 60/90。
    //    游戏的封顶通常来自玩家主动设置（游戏助手），保留；温控类请求一律拦截。
    // ─────────────────────────────────────────────────────────────────────────
    private fun hookExternalRefreshRateManager(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass("com.oplus.vrr.OPlusExternalRefreshRateManager", classLoader)
            // addFRTCFrameRate(float frameRate, String proc, boolean strict, String desc, int pid, int uid)
            XposedBridge.hookAllMethods(cls, "addFRTCFrameRate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rate = param.args.getOrNull(0) as? Float ?: return
                    val proc = param.args.getOrNull(1) as? String
                    val desc = param.args.getOrNull(3) as? String
                    if (shouldBlockExternalCap(rate, proc, desc)) {
                        XposedBridge.log("$TAG: block FRTC $rate for $proc ($desc)")
                        param.args[0] = 0f
                    }
                }
            })
            // addCVTFrameRate(int frameRate, String source)
            XposedBridge.hookAllMethods(cls, "addCVTFrameRate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rate = param.args.getOrNull(0) as? Int ?: return
                    val source = param.args.getOrNull(1) as? String
                    if (shouldBlockExternalCap(rate.toFloat(), source, null)) {
                        XposedBridge.log("$TAG: block CVT $rate for $source")
                        param.args[0] = 0
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked OPlusExternalRefreshRateManager OK")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: OPlusExternalRefreshRateManager failed: ${t.message}")
        }
    }

    private fun shouldBlockExternalCap(rate: Float, proc: String?, desc: String?): Boolean {
        if (!sExtremeMode || rate <= 0f || rate >= 143f) return false
        val lowerDesc = desc?.lowercase() ?: ""
        if ("temperature" in lowerDesc || "thermal" in lowerDesc) return true
        return !isGamePackage(proc)
    }

    private fun isGamePackage(proc: String?): Boolean {
        val pkg = proc?.substringBefore(':')?.takeIf { it.isNotEmpty() } ?: return false
        return sGamePkgCache.getOrPut(pkg) {
            try {
                val activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread"
                )
                val context = XposedHelpers.callMethod(activityThread, "getSystemContext") as Context
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                @Suppress("DEPRECATION")
                info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            } catch (_: Throwable) {
                false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // J. OplusFeatureConfigManager（app_extreme_high_refresh）  （system_server）
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
    // K. Settings UI 解锁  （com.android.settings）
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
            // "支持高刷新率的应用"开关目标 [开, 关]：144 档下原生"关"为 90Hz，改为回落到 120Hz
            XposedBridge.hookAllMethods(cls, "getAppSwitchTargets", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val targets = param.result as? IntArray ?: return
                    if (targets.size >= 2 && targets[0] > 120 && targets[1] < 120) {
                        param.result = intArrayOf(targets[0], 120)
                    }
                }
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
