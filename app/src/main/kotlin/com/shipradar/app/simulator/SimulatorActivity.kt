package com.shipradar.app.simulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.sim.ais.AisEncoder
import com.shipradar.sim.ais.AisTarget
import com.shipradar.sim.ais.countryOf
import com.shipradar.sim.nmea.EngineSensor
import com.shipradar.sim.nmea.GnssSensor
import com.shipradar.sim.nmea.HeadingSensor
import com.shipradar.sim.nmea.UtcTime
import com.shipradar.sim.nmea.WaterSpeedSensor
import com.shipradar.sim.nmea.WindSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.Calendar
import java.util.TimeZone

/**
 * 传感器数据模拟器 —— 独立人机界面(类 NemaStudio,但本项目自有)。
 *
 * 生成 **AIS(多目标:船名/MMSI/国籍/位置/航速/航向)+ GPS + 罗经 + 风 + 流 + 机舱** 的 IEC 61162-1 /
 * !AIVDM 语句,**实时显示**供核对,并经 UDP 输出(可指向雷达 app 的 61162-450 端口或任意监听端)。
 * 串口号/波特率/格式为可设字段(串口经 USB-serial 输出为后续;当前活动输出为 UDP)。语句生成在
 * [com.shipradar.sim] 纯 JVM 核心(已单测)。
 */
class SimulatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { SimulatorScreen() } } }
    }
}

/** 一个可编辑的 AIS 目标(界面态)。 */
class AisRow(mmsi: Int, name: String, lat: Double, lon: Double, sog: Double, cog: Double, hdg: Int) {
    var mmsi by mutableStateOf(mmsi.toString())
    var name by mutableStateOf(name)
    var lat by mutableStateOf(lat.toString())
    var lon by mutableStateOf(lon.toString())
    var sog by mutableStateOf(sog.toString())
    var cog by mutableStateOf(cog.toString())
    var hdg by mutableStateOf(hdg.toString())

    val mmsiInt get() = mmsi.toIntOrNull() ?: 0
    fun toTarget() = AisTarget(
        mmsi = mmsiInt, name = name, latitude = lat.toDoubleOrNull() ?: 0.0,
        longitude = lon.toDoubleOrNull() ?: 0.0, sogKn = sog.toDoubleOrNull() ?: 0.0,
        cogDeg = cog.toDoubleOrNull() ?: 0.0, headingDeg = hdg.toIntOrNull(),
    )
}

@Composable
private fun SimulatorScreen() {
    val ais = remember {
        mutableStateListOf(
            AisRow(412345678, "COSCO HOPE", 31.20, 122.10, 12.4, 95.0, 95),
            AisRow(338112233, "USCG EAGLE", 31.25, 122.20, 8.0, 270.0, 268),
        )
    }
    // 自船 GPS + 罗经
    var lat by remember { mutableStateOf("31.2300") }
    var lon by remember { mutableStateOf("122.1500") }
    var sog by remember { mutableStateOf("12.4") }
    var cog by remember { mutableStateOf("87.0") }
    var hdg by remember { mutableStateOf("87.0") }
    // 风 / 流 / 机舱
    var windAngle by remember { mutableStateOf("45.0") }
    var windSpeed by remember { mutableStateOf("14.0") }
    var curSet by remember { mutableStateOf("210.0") }
    var curDrift by remember { mutableStateOf("1.2") }
    var rpm by remember { mutableStateOf("110.0") }
    // 输出
    var serialPort by remember { mutableStateOf("COM3") }
    var baud by remember { mutableStateOf("4800") }
    var format by remember { mutableStateOf("NMEA-0183") }
    var udpHost by remember { mutableStateOf("127.0.0.1") }
    var udpPort by remember { mutableStateOf("10110") }
    var running by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val sock = withContext(Dispatchers.IO) { DatagramSocket() }
        val dst = runCatching { InetSocketAddress(udpHost, udpPort.toIntOrNull() ?: 10110) }.getOrNull()
        try {
            while (running) {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val now = UtcTime(
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND).toDouble(),
                    cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR),
                )
                val lines = buildList {
                    addAll(GnssSensor(now, lat.d(), lon.d(), sog.d(), cog.d()).toSentences())
                    addAll(HeadingSensor(hdg.d()).toSentences())
                    addAll(WindSensor(windAngle.d(), windSpeed.d()).toSentences())
                    addAll(WaterSpeedSensor(0.0, currentSetDeg = curSet.d(), currentDriftKn = curDrift.d()).toSentences())
                    addAll(EngineSensor(rpm.d()).toSentences())
                    ais.forEach { addAll(AisEncoder.encodeTarget(it.toTarget())) }
                }
                for (line in lines) {
                    log.add(0, line)
                    if (dst != null) withContext(Dispatchers.IO) {
                        runCatching {
                            val b = (line + "\r\n").toByteArray(Charsets.US_ASCII)
                            sock.send(DatagramPacket(b, b.size, dst))
                        }
                    }
                }
                while (log.size > 300) log.removeAt(log.size - 1)
                delay(1000)
            }
        } finally {
            withContext(Dispatchers.IO) { runCatching { sock.close() } }
        }
    }

    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 左:配置(滚动)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("传感器数据模拟器", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("输出设置", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        F("串口号", serialPort, Modifier.weight(1f)) { serialPort = it }
                        F("波特率", baud, Modifier.weight(1f)) { baud = it }
                        F("格式", format, Modifier.weight(1f)) { format = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        F("UDP 主机", udpHost, Modifier.weight(2f)) { udpHost = it }
                        F("端口", udpPort, Modifier.weight(1f)) { udpPort = it }
                    }
                    Button(onClick = { running = !running }) { Text(if (running) "停止 ■" else "运行 ▶") }
                    Text(
                        "活动输出=UDP(可指向雷达 61162-450 端口);串口为 USB-serial 输出预留。",
                        fontSize = 10.sp, color = Color(0xFF888888),
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("AIS 目标(${ais.size})", fontWeight = FontWeight.Bold)
                        Button(onClick = { ais.add(AisRow(412000000 + ais.size, "TARGET ${ais.size + 1}", 31.2, 122.1, 10.0, 90.0, 90)) }) { Text("+ 加 AIS") }
                    }
                    ais.forEachIndexed { i, r ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                F("船名", r.name, Modifier.weight(2f)) { r.name = it }
                                F("MMSI(9位)", r.mmsi, Modifier.weight(2f)) { r.mmsi = it }
                                Text("国籍 ${countryOf(r.mmsiInt) ?: "?"}", Modifier.weight(1f).padding(top = 16.dp), fontSize = 11.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                F("纬度", r.lat, Modifier.weight(1f)) { r.lat = it }
                                F("经度", r.lon, Modifier.weight(1f)) { r.lon = it }
                                F("航速kn", r.sog, Modifier.weight(1f)) { r.sog = it }
                                F("航向°", r.cog, Modifier.weight(1f)) { r.cog = it }
                                Button(onClick = { ais.removeAt(i) }) { Text("删") }
                            }
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("自船 GPS + 罗经", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        F("纬度", lat, Modifier.weight(1f)) { lat = it }
                        F("经度", lon, Modifier.weight(1f)) { lon = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        F("航速kn", sog, Modifier.weight(1f)) { sog = it }
                        F("航向°", cog, Modifier.weight(1f)) { cog = it }
                        F("艏向°", hdg, Modifier.weight(1f)) { hdg = it }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("风 / 流 / 机舱", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        F("风角°", windAngle, Modifier.weight(1f)) { windAngle = it }
                        F("风速kn", windSpeed, Modifier.weight(1f)) { windSpeed = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        F("流向°", curSet, Modifier.weight(1f)) { curSet = it }
                        F("流速kn", curDrift, Modifier.weight(1f)) { curDrift = it }
                        F("主机RPM", rpm, Modifier.weight(1f)) { rpm = it }
                    }
                }
            }
        }

        // 右:语句实时显示(监视)
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("语句监视 (${log.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0B1418)).padding(6.dp)) {
                items(log) { line ->
                    Text(
                        line,
                        color = if (line.startsWith("!AI")) Color(0xFF7FE6A0) else Color(0xFFB8D2DA),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

private fun String.d(): Double = toDoubleOrNull() ?: 0.0

@Composable
private fun F(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 10.sp) },
        singleLine = true, modifier = modifier,
    )
}
