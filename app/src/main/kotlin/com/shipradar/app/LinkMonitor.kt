package com.shipradar.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.comms.service.DataLinkStats
import com.shipradar.contract.LinkState
import kotlinx.coroutines.delay

/** 链路状态配色(认证要求的链路/传感器状态指示)。 */
private fun linkColor(s: LinkState): Color = when (s) {
    LinkState.CONNECTED -> Color(0xFF7FE6A0)
    LinkState.NEGOTIATING -> Color(0xFFFFC766)
    LinkState.DEGRADED -> Color(0xFFFF9F4A)
    LinkState.DISCONNECTED -> Color(0xFFFF6B6B)
}

private fun linkLabel(s: LinkState): String = when (s) {
    LinkState.CONNECTED -> "CONNECTED 已连接"
    LinkState.NEGOTIATING -> "NEGOTIATING 协商中"
    LinkState.DEGRADED -> "DEGRADED 降级"
    LinkState.DISCONNECTED -> "DISCONNECTED 未连接"
}

/**
 * 常驻链路状态小标(PPI 顶部),LIVE 模式显示握手/链路状态 —— 认证要求的传感器链路常驻指示。
 * 放在 overlay(交互层之下),仅做指示不接收点击。
 */
@Composable
fun BoxScopeLinkChip(state: LinkState) {
    Text(
        "LINK ● ${linkLabel(state)}",
        color = linkColor(state),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * 数据链路监视浮层(L 键开关)。每 ~0.7s 轮询 [snapshot],按收包计数增量算速率 —— 与 SIM/LIVE 的时钟域
 * 无关,故两模式都准。显示握手状态、各通道收包/速率/活跃、回波序列完整性、61162-450 丢弃。
 * 接真雷达时,操作员一眼可判"收到没 / 解对没"。点击任意处或 Esc/L 关闭。
 */
@Composable
fun LinkMonitorOverlay(snapshot: (Long) -> DataLinkStats, onDismiss: () -> Unit) {
    var view by remember { mutableStateOf<MonitorView?>(null) }
    LaunchedEffect(Unit) {
        var prev: DataLinkStats? = null
        var prevWall = nowMs()
        while (true) {
            val wall = nowMs()
            val s = snapshot(wall)
            val dt = ((wall - prevWall).coerceAtLeast(1)) / 1000.0
            fun rate(cur: Long, was: Long?): Double = if (was == null) 0.0 else (cur - was) / dt
            val p = prev
            view = MonitorView(
                link = s.linkState,
                rows = listOf(
                    ChRow("回波 A (IMAGE)", s.echo.packets, rate(s.echo.packets, p?.echo?.packets)),
                    ChRow("回波 B (双量程)", s.echoB.packets, rate(s.echoB.packets, p?.echoB?.packets)),
                    ChRow("状态 (STATUS)", s.status.packets, rate(s.status.packets, p?.status?.packets)),
                    ChRow("目标 (TARGET)", s.target.packets, rate(s.target.packets, p?.target?.packets)),
                    ChRow("传感器 (61162-450)", s.iec450.packets, rate(s.iec450.packets, p?.iec450?.packets)),
                ),
                seqRecv = s.seq.received,
                seqMissing = s.seq.missing,
                seqGap = s.seq.gapEvents,
                discardsTotal = s.discards.run {
                    oversized + invalidHeader + nonSentenceDatagram + tagFormatError +
                        tagChecksumError + tagSyntaxError + tagFramingError + sentenceError
                },
                aisDeferred = s.aisDeferred,
            )
            prev = s
            prevWall = wall
            delay(700)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000810))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.background(Color(0xFF0B1418)).padding(20.dp)) {
            Text("数据链路监视 / LINK MONITOR", color = Color(0xFF9FC2CE), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            val v = view
            if (v == null) {
                Text("采样中…", color = Color(0xFF7FA6B3), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            } else {
                Row(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
                    Text("链路状态  ", color = Color(0xFF7FA6B3), fontSize = 12.sp)
                    Text("● ${linkLabel(v.link)}", color = linkColor(v.link), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                // 表头
                MonitorRow("通道", "收包", "速率/s", "状态", header = true)
                v.rows.forEach { r ->
                    val active = r.rate > 0.5
                    MonitorRow(
                        r.name,
                        r.packets.toString(),
                        "%.0f".format(r.rate),
                        if (active) "● 活跃" else if (r.packets > 0) "○ 静默" else "— 无包",
                        valueColor = if (active) Color(0xFF7FE6A0) else if (r.packets > 0L) Color(0xFFFFC766) else Color(0xFF7FA6B3),
                    )
                }
                Text(
                    "回波序列: 收 ${v.seqRecv}  缺 ${v.seqMissing}  跳变 ${v.seqGap}    450丢弃: ${v.discardsTotal}    AIS暂存: ${v.aisDeferred}",
                    color = Color(0xFFB8D2DA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "点击任意处、按 Esc 或 L 关闭",
                    color = Color(0xFF7FA6B3),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun MonitorRow(
    c1: String, c2: String, c3: String, c4: String,
    header: Boolean = false,
    valueColor: Color = Color(0xFFE6F2F5),
) {
    val base = if (header) Color(0xFF9FC2CE) else Color(0xFFE6F2F5)
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(c1, color = base, fontSize = 12.sp, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(150.dp))
        Text(c2, color = base, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(80.dp))
        Text(c3, color = base, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(70.dp))
        Text(c4, color = if (header) base else valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
    }
}

private data class MonitorView(
    val link: LinkState,
    val rows: List<ChRow>,
    val seqRecv: Long,
    val seqMissing: Long,
    val seqGap: Long,
    val discardsTotal: Int,
    val aisDeferred: Long,
)

private data class ChRow(val name: String, val packets: Long, val rate: Double)

private fun nowMs(): Long = System.currentTimeMillis()
