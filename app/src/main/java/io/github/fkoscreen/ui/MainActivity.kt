package io.github.fkoscreen.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.fkoscreen.ConfigManager
import io.github.fkoscreen.util.ColorBallCalculator
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { ConfigManager.getAppPrefs(context) }

    var manualHbm by remember {
        mutableStateOf(
            if (prefs.contains(ConfigManager.KEY_MANUAL_HBM)) prefs.getBoolean(ConfigManager.KEY_MANUAL_HBM, true)
            else prefs.getBoolean(ConfigManager.KEY_MANUAL_1250, true)
        )
    }
    var manualPeak by remember {
        mutableStateOf(
            if (prefs.contains(ConfigManager.KEY_MANUAL_PEAK)) prefs.getBoolean(ConfigManager.KEY_MANUAL_PEAK, false)
            else prefs.getBoolean(ConfigManager.KEY_MANUAL_1600, false)
        )
    }
    var thermalBypass by remember {
        mutableStateOf(prefs.getBoolean(ConfigManager.KEY_THERMAL_BYPASS, false))
    }
    var fossBypass by remember { mutableStateOf(prefs.getBoolean(ConfigManager.KEY_FOSS_BYPASS, true)) }
    var hdrRatio by remember { mutableStateOf(prefs.getBoolean(ConfigManager.KEY_HDR_RATIO, true)) }
    var darkModeStyles by remember { mutableStateOf(prefs.getBoolean(ConfigManager.KEY_DARK_MODE_STYLES, true)) }
    var colorBallAnchor by remember { mutableStateOf(prefs.getBoolean(ConfigManager.KEY_COLOR_BALL_ANCHOR, true)) }
    var aodBrightnessScale by remember {
        mutableFloatStateOf(
            prefs.getFloat(ConfigManager.KEY_AOD_BRIGHTNESS_SCALE, ConfigManager.DEFAULT_AOD_BRIGHTNESS_SCALE)
                .coerceIn(ConfigManager.MIN_AOD_BRIGHTNESS_SCALE, ConfigManager.MAX_AOD_BRIGHTNESS_SCALE)
        )
    }

    val initialColorState = remember { ColorBallCalculator.readFromSystem(context) }
    var cctValue by remember { mutableFloatStateOf(initialColorState.cct) }
    var tintValue by remember { mutableFloatStateOf(initialColorState.tint) }
    var outputRgb by remember { mutableStateOf(Triple(initialColorState.r, initialColorState.g, initialColorState.b)) }
    var outputPoint by remember { mutableStateOf(Pair(initialColorState.x750, initialColorState.y750)) }
    var lastUserActionTimeMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(context) {
        val cr = context.contentResolver
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pendingRunnable: Runnable? = null

        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // 如果用户刚刚在 App 内滑动操作（800ms 内），屏蔽系统设置的回环通知，彻底避免互相干扰与颤动
                if (System.currentTimeMillis() - lastUserActionTimeMs < 800L) {
                    return
                }
                // 防抖 100ms：确保系统设置 X 和 Y 两个坐标全部写入稳定后再统一反解刷新
                pendingRunnable?.let { handler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (System.currentTimeMillis() - lastUserActionTimeMs >= 800L) {
                        val ext = ColorBallCalculator.readFromSystem(context)
                        cctValue = ext.cct
                        tintValue = ext.tint
                        outputRgb = Triple(ext.r, ext.g, ext.b)
                        outputPoint = Pair(ext.x750, ext.y750)
                    }
                }
                pendingRunnable = runnable
                handler.postDelayed(runnable, 100L)
            }
        }
        try {
            cr.registerContentObserver(android.provider.Settings.System.getUriFor("color_ball_last_pointx"), false, observer)
            cr.registerContentObserver(android.provider.Settings.System.getUriFor("color_ball_last_pointy"), false, observer)
            cr.registerContentObserver(android.provider.Settings.System.getUriFor("eyeprotect_rgb"), false, observer)
            cr.registerContentObserver(android.provider.Settings.System.getUriFor("color_temperate_ball_mode"), false, observer)
        } catch (_: Throwable) {}

        onDispose {
            pendingRunnable?.let { handler.removeCallbacks(it) }
            try {
                cr.unregisterContentObserver(observer)
            } catch (_: Throwable) {}
        }
    }

    fun updateCct(newCct: Float) {
        cctValue = newCct
        lastUserActionTimeMs = System.currentTimeMillis()
        val newState = ColorBallCalculator.compose(newCct, tintValue)
        outputRgb = Triple(newState.r, newState.g, newState.b)
        outputPoint = Pair(newState.x750, newState.y750)
        ColorBallCalculator.applyToSystem(context, newState)
    }

    fun updateTint(newTint: Float) {
        tintValue = newTint
        lastUserActionTimeMs = System.currentTimeMillis()
        val newState = ColorBallCalculator.compose(cctValue, newTint)
        outputRgb = Triple(newState.r, newState.g, newState.b)
        outputPoint = Pair(newState.x750, newState.y750)
        ColorBallCalculator.applyToSystem(context, newState)
    }

    fun resetColorBall() {
        cctValue = 0.0f
        tintValue = 0.0f
        lastUserActionTimeMs = System.currentTimeMillis()
        val resetState = ColorBallCalculator.compose(0.0f, 0.0f)
        outputRgb = Triple(resetState.r, resetState.g, resetState.b)
        outputPoint = Pair(resetState.x750, resetState.y750)
        ColorBallCalculator.applyToSystem(context, resetState)
    }

    fun updatePref(key: String, value: Boolean) {
        ConfigManager.writeConfig(context, key, value)
    }

    LaunchedEffect(Unit) {
        val editor = prefs.edit()
        var needCommit = false
        if (!prefs.contains(ConfigManager.KEY_MANUAL_HBM)) {
            editor.putBoolean(ConfigManager.KEY_MANUAL_HBM, manualHbm)
            needCommit = true
        }
        if (!prefs.contains(ConfigManager.KEY_MANUAL_PEAK)) {
            editor.putBoolean(ConfigManager.KEY_MANUAL_PEAK, manualPeak)
            needCommit = true
        }
        if (!prefs.contains(ConfigManager.KEY_THERMAL_BYPASS)) {
            editor.putBoolean(ConfigManager.KEY_THERMAL_BYPASS, thermalBypass)
            needCommit = true
        }
        if (!prefs.contains(ConfigManager.KEY_FOSS_BYPASS)) {
            editor.putBoolean(ConfigManager.KEY_FOSS_BYPASS, true)
            needCommit = true
        }
        if (!prefs.contains(ConfigManager.KEY_HDR_RATIO)) {
            editor.putBoolean(ConfigManager.KEY_HDR_RATIO, true)
            needCommit = true
        }
        if (!prefs.contains(ConfigManager.KEY_DARK_MODE_STYLES)) {
            editor.putBoolean(ConfigManager.KEY_DARK_MODE_STYLES, true)
            needCommit = true
        }
        if (!prefs.contains(ConfigManager.KEY_COLOR_BALL_ANCHOR)) {
            editor.putBoolean(ConfigManager.KEY_COLOR_BALL_ANCHOR, true)
            needCommit = true
        }
        if (needCommit) {
            editor.apply()
        }
        // 每次打开界面都把全部开关同步到 Settings.System，hook 侧只认这一处
        updatePref(ConfigManager.KEY_MANUAL_HBM, manualHbm)
        updatePref(ConfigManager.KEY_MANUAL_PEAK, manualPeak)
        updatePref(ConfigManager.KEY_THERMAL_BYPASS, thermalBypass)
        updatePref(ConfigManager.KEY_FOSS_BYPASS, fossBypass)
        updatePref(ConfigManager.KEY_HDR_RATIO, hdrRatio)
        updatePref(ConfigManager.KEY_DARK_MODE_STYLES, darkModeStyles)
        updatePref(ConfigManager.KEY_COLOR_BALL_ANCHOR, colorBallAnchor)
        ConfigManager.writeConfig(context, ConfigManager.KEY_AOD_BRIGHTNESS_SCALE, aodBrightnessScale)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "FkOScreen",
                subtitle = "ColorOS 屏幕显示与色彩增强"
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 卡片 1：亮度与 HDR
            SmallTitle(text = "屏幕亮度与 HDR")
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "高亮激发模式 (HBM)",
                    summary = "突破系统 SDR 默认上限，允许手动调节至激发亮度档位",
                    checked = manualHbm,
                    onCheckedChange = {
                        manualHbm = it
                        updatePref(ConfigManager.KEY_MANUAL_HBM, it)
                        updatePref(ConfigManager.KEY_MANUAL_1250, it)
                    }
                )
                SwitchPreference(
                    title = "阻止 FOSS 自动降亮",
                    summary = "拦截针对特定应用的 15% 亮度扣减",
                    checked = fossBypass,
                    onCheckedChange = {
                        fossBypass = it
                        updatePref(ConfigManager.KEY_FOSS_BYPASS, it)
                    }
                )
                SwitchPreference(
                    title = "修复 HDR 亮度比例",
                    summary = "锁定 hdrSdrRatio 为 1.56，正常显示 Ultra HDR 高光",
                    checked = hdrRatio,
                    onCheckedChange = {
                        hdrRatio = it
                        updatePref(ConfigManager.KEY_HDR_RATIO, it)
                    }
                )
                SliderPreference(
                    title = "AOD 亮度",
                    summary = "全景息屏亮度系数，暗处 (< 25 lux) 保持原生；不会超过息屏前的屏幕亮度",
                    value = aodBrightnessScale,
                    onValueChange = { aodBrightnessScale = it },
                    onValueChangeFinished = {
                        ConfigManager.writeConfig(context, ConfigManager.KEY_AOD_BRIGHTNESS_SCALE, aodBrightnessScale)
                    },
                    valueRange = ConfigManager.MIN_AOD_BRIGHTNESS_SCALE..ConfigManager.MAX_AOD_BRIGHTNESS_SCALE,
                    valueText = if (Math.abs(aodBrightnessScale - ConfigManager.DEFAULT_AOD_BRIGHTNESS_SCALE) < 0.005f) {
                        "原生 (×0.63)"
                    } else {
                        "×${"%.2f".format(aodBrightnessScale)}"
                    },
                    showKeyPoints = true,
                    keyPoints = listOf(ConfigManager.DEFAULT_AOD_BRIGHTNESS_SCALE)
                )
            }

            // 卡片 2：屏幕色彩正交调节
            SmallTitle(text = "屏幕色彩正交调节")
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "输出增益: ",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Text(
                                text = "R:${outputRgb.first} G:${outputRgb.second} B:${outputRgb.third}",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "基准坐标: (${outputPoint.first.roundToInt()}, ${outputPoint.second.roundToInt()})",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }

                    Button(
                        onClick = { resetColorBall() },
                        minHeight = 28.dp,
                        cornerRadius = 10.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors()
                    ) {
                        Text(
                            text = "复位默认",
                            style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MiuixTheme.colorScheme.dividerLine,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                SliderPreference(
                    title = "冷暖色温微调",
                    summary = "极暖 (-100%) ~ 极冷 (+100%)",
                    value = cctValue,
                    onValueChange = { updateCct(it) },
                    valueRange = -1f..1f,
                    valueText = when {
                        cctValue < -0.01f -> "暖色 ${(cctValue * -100).roundToInt()}%"
                        cctValue > 0.01f -> "冷色 ${(cctValue * 100).roundToInt()}%"
                        else -> "标准 (6500K)"
                    },
                    showKeyPoints = true,
                    keyPoints = listOf(0f)
                )

                SliderPreference(
                    title = "青品偏色校正",
                    summary = "偏青 (-100%) ~ 偏品 (+100%)",
                    value = tintValue,
                    onValueChange = { updateTint(it) },
                    valueRange = -1f..1f,
                    valueText = when {
                        tintValue < -0.01f -> "偏青 ${(tintValue * -100).roundToInt()}%"
                        tintValue > 0.01f -> "偏品 ${(tintValue * 100).roundToInt()}%"
                        else -> "中性 (无偏色)"
                    },
                    showKeyPoints = true,
                    keyPoints = listOf(0f)
                )
            }

            // 卡片 3：色彩与深色模式
            SmallTitle(text = "色彩与深色模式")
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "深色模式三档调节",
                    summary = "开启增强、适中、柔和三档深色样式选择及效果预览",
                    checked = darkModeStyles,
                    onCheckedChange = {
                        darkModeStyles = it
                        updatePref(ConfigManager.KEY_DARK_MODE_STYLES, it)
                    }
                )
                SwitchPreference(
                    title = "色温球作为自适应基底",
                    summary = "解除色温球调节限制，将护眼与环境色调节叠加在色温球白点上",
                    checked = colorBallAnchor,
                    onCheckedChange = {
                        colorBallAnchor = it
                        updatePref(ConfigManager.KEY_COLOR_BALL_ANCHOR, it)
                    }
                )
            }

            // 卡片 4：试验性功能
            SmallTitle(text = "试验性功能")
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "解锁硬件极限峰值亮度",
                    summary = "解除系统显示限制，开放至面板硬件最高极限亮度",
                    checked = manualPeak,
                    onCheckedChange = {
                        manualPeak = it
                        updatePref(ConfigManager.KEY_MANUAL_PEAK, it)
                        updatePref(ConfigManager.KEY_MANUAL_1600, it)
                    }
                )
                SwitchPreference(
                    title = "解除温度对亮度限制",
                    summary = "拦截系统温控降亮策略，高温环境下保持屏幕高亮不衰减",
                    checked = thermalBypass,
                    onCheckedChange = {
                        thermalBypass = it
                        updatePref(ConfigManager.KEY_THERMAL_BYPASS, it)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 快捷热重载
            Button(
                onClick = {
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "killall com.android.settings com.android.systemui"))
                        Toast.makeText(context, "已重载系统设置与界面服务", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "执行热重载需要 Root 权限: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text("重载系统设置与界面")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
