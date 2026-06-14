package com.shipradar.app.bite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.contract.LinkState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W6-C 性能监视 / BITE 自检面板(IEC 62388 §6/§15 性能监测)。纯展示:接收一个 [BiteReport] 入参,
 * 分区显示链路、各数据通道(收包/速率/时延 + 绿黄红活跃灯)、传感器有效性、整体健康灯,并提供
 * "运行自检 RUN BITE" 按钮。**不连 comms / 不读 socket。**
 *
 * 无状态(stateless):RUN BITE 仅回调 [onRunBite],由编排者更新 [BiteReport.lastBiteMillis] 后回灌;
 * 面板据 `report.lastBiteMillis` 显示上次自检时间。
 */
@Composable
fun BitePanel(
    report: BiteReport,
    modifier: Modifier = Modifier,
    onRunBite: () -> Unit = {},
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = BiteColors.SURFACE),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 标题 + 整体健康灯
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("性能监视 / BITE", color = BiteColors.LABEL, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                val overall = report.overall()
                Dot(BiteColors.overall(overall), size = 14)
                Text(overall.name, color = BiteColors.overall(overall), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Section("链路 LINK")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(BiteColors.link(report.linkState))
                Text(report.linkState.name, color = BiteColors.VALUE, fontSize = 13.sp)
            }

            Section("数据通道 CHANNELS")
            report.channels.forEach { ch -> ChannelRow(ch) }

            Section("传感器 SENSORS")
            SensorRow("HDG  航向", report.headingValid)
            SensorRow("POSN 位置", report.positionValid)
            SensorRow("SOG  对地航速", report.sogValid)

            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRunBite) { Text("运行自检 RUN BITE") }
                Text(biteStamp(report.lastBiteMillis), color = BiteColors.LABEL, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: ChannelHealth) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(BiteColors.activity(ch.activity()))
        Text(ch.name, color = BiteColors.VALUE, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text("pkts ${ch.packets}", color = BiteColors.LABEL, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.weight(1f))
        Text(ageText(ch.ageMs), color = BiteColors.activity(ch.activity()), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SensorRow(label: String, valid: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dot(if (valid) BiteColors.GREEN else BiteColors.RED)
        Text(label, color = BiteColors.VALUE, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(if (valid) "有效" else "无效", color = if (valid) BiteColors.GREEN else BiteColors.RED, fontSize = 12.sp)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = BiteColors.SECTION, fontWeight = FontWeight.Medium, fontSize = 12.sp)
}

@Composable
private fun Dot(color: Color, size: Int = 12) {
    Spacer(Modifier.size(size.dp).clip(CircleShape).background(color))
}

private fun ageText(ageMs: Long?): String = when {
    ageMs == null -> "无数据 ---"
    ageMs < 1000 -> "${ageMs}ms"
    else -> "%.1fs".format(ageMs / 1000.0)
}

private fun biteStamp(lastBiteMillis: Long?): String =
    if (lastBiteMillis == null) "尚未自检"
    else "上次自检 " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastBiteMillis))

/** 性能监视面板配色(IEC 62288 §4.7.2.1:红=故障/报警;绿=正常;黄=注意/静默。暗背景)。 */
private object BiteColors {
    val SURFACE = Color(0xFF14181F)
    val LABEL = Color(0xFF9AA3AD)
    val SECTION = Color(0xFF7E8894)
    val VALUE = Color(0xFFE6EAEF)
    val GREEN = Color(0xFF35C759)
    val AMBER = Color(0xFFFFB300)
    val RED = Color(0xFFE53935)

    fun activity(a: ChannelActivity): Color = when (a) {
        ChannelActivity.ACTIVE -> GREEN
        ChannelActivity.SILENT -> AMBER
        ChannelActivity.NO_DATA -> RED
    }

    fun overall(h: OverallHealth): Color = when (h) {
        OverallHealth.OK -> GREEN
        OverallHealth.DEGRADED -> AMBER
        OverallHealth.FAULT -> RED
    }

    fun link(s: LinkState): Color = when (s) {
        LinkState.CONNECTED -> GREEN
        LinkState.NEGOTIATING -> AMBER
        LinkState.DEGRADED -> AMBER
        LinkState.DISCONNECTED -> RED
    }
}

// ---------------- Previews ----------------

private fun previewChannels(allActive: Boolean) = listOf(
    ChannelHealth("ECHO", packets = 124_503, ageMs = 40),
    ChannelHealth("ECHO-B", packets = 124_488, ageMs = 45),
    ChannelHealth("STATUS", packets = 612, ageMs = if (allActive) 220 else 8_000), // 黄:静默
    ChannelHealth("TARGET", packets = 3_044, ageMs = if (allActive) 300 else null), // 红:无数据
    ChannelHealth("IEC450", packets = 9_801, ageMs = 120),
)

@Preview(name = "BITE — OK", widthDp = 340, heightDp = 460)
@Composable
private fun PreviewOk() {
    BitePanel(
        BiteReport(
            linkState = LinkState.CONNECTED,
            channels = previewChannels(allActive = true),
            headingValid = true, positionValid = true, sogValid = true,
            lastBiteMillis = 1_700_000_000_000,
        ),
    )
}

@Preview(name = "BITE — DEGRADED", widthDp = 340, heightDp = 460)
@Composable
private fun PreviewDegraded() {
    BitePanel(
        BiteReport(
            linkState = LinkState.CONNECTED,
            channels = previewChannels(allActive = false),
            headingValid = true, positionValid = false, sogValid = true, // 位置无效 -> 降级
            lastBiteMillis = null,
        ),
    )
}
