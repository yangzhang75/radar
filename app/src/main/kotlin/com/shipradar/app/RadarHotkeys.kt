package com.shipradar.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.app.control.RadarDisplaySettings
import com.shipradar.uicore.ppi.RangeModel
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.app.control.MotionMode
import kotlin.math.abs

/**
 * 雷达控制级快捷键定义 — **键位的唯一权威来源**(同时驱动 [keymapForHelp] 的帮助浮层,二者不会失同步)。
 *
 * 这一层处理面向雷达/画面的操作(量程、发射、增益、方位/运动模式、SIM-LIVE、帮助);光标 / 目标 /
 * EBL / VRM / 平行索引线等交互键由 [com.shipradar.app.input.RadarInputLayer.handleKey] 处理。两层不冲突
 * (本层在 RadarScreen 根部 onPreviewKeyEvent 先消费控制键,其余键透传给获得焦点的输入层)。
 *
 * 在屏幕根部 onPreviewKeyEvent 调用;返回 true 表示已消费(阻止透传)。
 */
fun handleControlKey(
    key: Key,
    display: RadarDisplaySettings,
    setDisplay: (RadarDisplaySettings) -> Unit,
    controller: RadarController,
    status: RadarStatus,
    onToggleLive: () -> Unit,
    onToggleDual: () -> Unit,
    onToggleMonitor: () -> Unit,
    onToggleHelp: () -> Unit,
    onCloseHelp: () -> Unit,
): Boolean {
    when (key) {
        // 量程(IEC 62388 §9.4.1.1 法定量程档):+ 放大档(更大 NM),− 缩小档。
        Key.Plus, Key.Equals, Key.NumPadAdd ->
            setRange(RangeModel.nextRangeScale(display.rangeScaleNm), display, setDisplay, controller)
        Key.Minus, Key.NumPadSubtract ->
            setRange(RangeModel.previousRangeScale(display.rangeScaleNm), display, setDisplay, controller)
        // 发射 / 待机
        Key.T ->
            if (status.powerState == RadarPowerState.TRANSMIT) {
                controller.send(RadarCommand.Transmit(false))
            } else {
                controller.send(RadarCommand.Power(true))
                controller.send(RadarCommand.Transmit(true))
            }
        // 增益 自动 / 手动
        Key.A -> controller.send(RadarCommand.Gain(auto = !status.gainAuto, level = status.gain))
        // 方位模式循环 Head-Up → North-Up → Course-Up
        Key.N -> setDisplay(
            display.copy(
                orientation = when (display.orientation) {
                    PpiOrientation.HEAD_UP -> PpiOrientation.NORTH_UP
                    PpiOrientation.NORTH_UP -> PpiOrientation.COURSE_UP
                    PpiOrientation.COURSE_UP -> PpiOrientation.HEAD_UP
                },
            ),
        )
        // 运动模式 相对 / 真
        Key.M -> setDisplay(
            display.copy(
                motion = if (display.motion == MotionMode.RELATIVE_MOTION) {
                    MotionMode.TRUE_MOTION
                } else {
                    MotionMode.RELATIVE_MOTION
                },
            ),
        )
        // 数据源 模拟 / 实时
        Key.S -> onToggleLive()
        // 单量程 / 双量程画面
        Key.D -> onToggleDual()
        // 数据链路监视
        Key.L -> onToggleMonitor()
        // 快捷键帮助
        Key.F1, Key.Slash -> onToggleHelp()
        Key.Escape -> onCloseHelp()
        else -> return false
    }
    return true
}

private fun setRange(
    nm: Double,
    display: RadarDisplaySettings,
    setDisplay: (RadarDisplaySettings) -> Unit,
    controller: RadarController,
) {
    if (abs(nm - display.rangeScaleNm) <= 1e-9) return
    setDisplay(display.copy(rangeScaleNm = nm))
    // 雷达须扫到所选量程。SetRange 取米(03C1 编码,见 comms-core)。
    controller.send(RadarCommand.SetRange(RangeModel.nmToMeters(nm).toInt()))
}

/** 帮助浮层用的键位说明 —— 与 [handleControlKey] 及输入层共同的"快捷键定义"。 */
private val controlKeys = listOf(
    "+  =" to "量程放大(RANGE ↑)",
    "−" to "量程缩小(RANGE ↓)",
    "T" to "发射 / 待机",
    "A" to "增益 自动 / 手动",
    "N" to "方位模式 Head/North/Course-Up",
    "M" to "运动模式 相对 / 真 (RM/TM)",
    "S" to "数据源 模拟 / 实时 (SIM/LIVE)",
    "D" to "单量程 / 双量程画面 (Radar B)",
    "L" to "数据链路监视 (LINK MONITOR)",
    "F1  ?" to "显示 / 隐藏本帮助",
)
private val cursorKeys = listOf(
    "↑ ↓ ← →" to "画面平移 (Pan)",
    "Enter / Space" to "捕获光标处目标 (Acquire)",
    "Tab" to "循环选择目标",
    "Del / ⌫" to "取消选中目标",
    "R" to "回中 (Recenter)",
    "E" to "EBL1 开 / 关",
    "V" to "VRM1 开 / 关",
    "[  ]" to "EBL 微调 ∓",
    ",  ." to "VRM 微调 ∓",
    "P" to "平行索引线 开 / 关",
    "G" to "PI 线组切换",
    "Esc" to "关闭帮助",
)

/**
 * 快捷键帮助浮层 — 半透明遮罩 + 两栏键位表(雷达控制 / 光标·目标)。点击任意处或按 Esc/F1 关闭。
 * 让快捷键"可发现",满足人因工程(操作员可随时查阅当前键位定义)。
 */
@Composable
fun HotkeyHelpOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000810))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Color(0xFF0B1418))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("快捷键定义 / KEYBOARD SHORTCUTS", color = Color(0xFF9FC2CE), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(Modifier.padding(top = 12.dp)) {
                keyColumn("雷达控制", controlKeys)
                Box(Modifier.width(28.dp))
                keyColumn("光标 / 目标", cursorKeys)
            }
            Text(
                "点击任意处、按 Esc 或 F1 关闭",
                color = Color(0xFF7FA6B3),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun keyColumn(title: String, rows: List<Pair<String, String>>) {
    Column {
        Text(title, color = Color(0xFFFFC766), fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        rows.forEach { (k, desc) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    k,
                    color = Color(0xFFE6F2F5),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.width(120.dp),
                )
                Text(desc, color = Color(0xFFB8D2DA), fontSize = 12.sp)
            }
        }
    }
}
