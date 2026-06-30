package com.shipradar.haloprobe

import com.shipradar.comms.halo.control.HaloControlEncoder
import com.shipradar.comms.halo.handshake.HaloHandshake
import com.shipradar.comms.halo.image.SpokeParser
import com.shipradar.comms.halo.status.HaloStatusParser
import com.shipradar.comms.halo.target.TargetParser
import com.shipradar.constants.Endpoint
import com.shipradar.constants.HaloEndpoints
import com.shipradar.contract.RadarCommand
import com.shipradar.halofeed.RecordEntry
import com.shipradar.halofeed.RecordWriter
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * HALO 真雷达探针 —— **在本机(Mac)直连真雷达,不经安卓/模拟器**。
 *
 * 安卓模拟器是 NAT 虚拟网,收不到 LAN/VPN 组播;本工具用 JVM 自己的网络栈(走蒲公英 X5 所在网卡)直接
 * join 雷达组播,复用 comms-core 的**真解析器/握手**解码并打印,可选存抓包(halofeed 录制格式)。
 *
 * 用途:① 验证"接真雷达解得对"(对真实字节,不再自证自);② 产出 pcap 式抓包供离线回放;
 * ③ 第一手十六进制,用来核对/修正协议(像之前那个帧头)。
 *
 * 用法:
 *   haloprobe [--iface <网卡名>] [--manual-ip <雷达IP>] [--no-handshake]
 *             [--duration <秒, 默认60>] [--record <文件>] [--hex <字节数, 默认48>]
 *   --iface     指定走哪块网卡(蒲公英虚拟网卡);不填则自动挑一块 up/非回环/支持组播的。
 *   --manual-ip 跳过组播握手,按手动 IP 走(蒲公英组播不通时用)。
 *   --record    把收到的每个数据报存成抓包,之后可在本机离线回放验证。
 */
fun main(rawArgs: Array<String>) {
    // 离线模式:解码一份抓包文本(真雷达录像)→ 真 SpokeParser,验证"符合协议没"。
    val decodeFile = rawArgs.toList().zipWithNext().firstOrNull { it.first == "--decode-file" }?.second
        ?: rawArgs.firstOrNull { it.startsWith("--decode-file=") }?.substringAfter('=')
    if (decodeFile != null) {
        val unitArg = rawArgs.firstOrNull { it.startsWith("--range-unit=") }?.substringAfter('=')?.lowercase()
        val unit = when (unitArg) {
            "dm" -> SpokeParser.RANGE_UNIT_DM
            "cm" -> SpokeParser.RANGE_UNIT_CM
            else -> SpokeParser.RANGE_UNIT_MM
        }
        decodeCapture(decodeFile, unit); return
    }

    val args = ProbeArgs.parse(rawArgs)
    println("=== HALO 探针 (本机直连) ===")

    val nif = resolveInterface(args.iface)
    println("网卡: ${nif?.name ?: "默认(未指定)"}")

    // 数据接口端点:默认法定端口;握手成功则用协商端点;手动 IP 则用回退端点。
    var image = HaloEndpoints.IMAGE
    var status = HaloEndpoints.STATUS
    var target = HaloEndpoints.TARGET
    var control = HaloEndpoints.CONTROL
    var radarIp: String? = args.manualIp

    if (args.manualIp != null) {
        val info = HaloHandshake.manualFallback(args.manualIp)
        println("握手: 跳过(手动 IP ${args.manualIp}) → ${info.groups.size} 组通道")
    } else if (!args.noHandshake) {
        val info = tryHandshake(nif)
        if (info != null) {
            radarIp = info.radarIp
            info.image?.let { image = it }
            info.status?.let { status = it }
            info.target?.let { target = it }
            info.control?.let { control = it }
            println("握手: 成功 ✓ 雷达=${info.radarIp ?: "?"} serial=${info.serial}")
        } else {
            println("握手: 超时/未收到 01B2。继续按默认端点监听;如需双向请加 --manual-ip <雷达IP>。")
        }
    } else {
        println("握手: 已禁用(--no-handshake)")
    }
    println("监听: IMAGE=$image  STATUS=$status  TARGET=$target")
    println("看门狗: → $control (每 8s, 防止雷达回待机)")
    println("时长: ${args.durationSec}s${args.record?.let { "  抓包→$it" } ?: ""}")
    println("------------------------------------------------------------")

    val recorder = args.record?.let { RecordWriter(FileOutputStream(it)) }
    val startNanos = System.nanoTime()
    @Synchronized fun record(ep: Endpoint, payload: ByteArray) {
        recorder?.append(RecordEntry((System.nanoTime() - startNanos) / 1_000, ep, payload))
    }

    val chImage = ImageChannel(args.hex)
    val chStatus = StatusChannel(args.hex)
    val chTarget = TargetChannel(args.hex)

    running = true
    val threads = listOf(
        receiver("IMAGE", image, nif) { record(image, it); chImage.onPacket(it) },
        receiver("STATUS", status, nif) { record(status, it); chStatus.onPacket(it) },
        receiver("TARGET", target, nif) { record(target, it); chTarget.onPacket(it) },
    )

    // 看门狗:周期发 A1C1,否则雷达 ~30s 后回待机。
    val watchdog = thread(isDaemon = true) {
        val sock = DatagramSocket()
        val wd = HaloControlEncoder.encode(RadarCommand.Watchdog)
        val dst = InetSocketAddress(InetAddress.getByName(radarIp ?: control.address), control.port)
        while (running) {
            runCatching { sock.send(DatagramPacket(wd, wd.size, dst)) }
            sleep(8_000)
        }
        runCatching { sock.close() }
    }

    // 周期打印 + 计时结束。
    val deadline = System.currentTimeMillis() + args.durationSec * 1000L
    while (System.currentTimeMillis() < deadline) {
        sleep(2_000)
        println("[t+%2ds] IMAGE %s | STATUS %s | TARGET %s".format(
            (args.durationSec - (deadline - System.currentTimeMillis()) / 1000),
            chImage.line(), chStatus.line(), chTarget.line(),
        ))
    }

    running = false
    threads.forEach { it.join(1500) }
    watchdog.join(100)
    recorder?.close()

    println("------------------------------------------------------------")
    println("=== 汇总 ===")
    chImage.summary(); chStatus.summary(); chTarget.summary()
    if (chImage.packets == 0L && chStatus.packets == 0L && chTarget.packets == 0L) {
        println("⚠ 一个包都没收到。排查:① 本机是否在雷达网段(蒲公英 X5)② --iface 选对网卡了吗 " +
            "③ 雷达是否在发射 ④ 组播是否被 VPN 过滤(试 --manual-ip 走单播)。")
    }
    args.record?.let { println("抓包已存: $it (可离线回放/逐字段核对)") }
}

@Volatile private var running = false
private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

// ----------------------------------------------------------------- 离线抓包解码(真数据验证)
/**
 * 解码一份"文本 hex 抓包"(每包: `(UDP)src->dst ,N Bytes` + 整 IP 报文的两位十六进制),
 * 去 IP/UDP 头取 HALO 载荷 → 真 [SpokeParser] 解析,打印结果。用于对**真雷达字节**验证协议一致性。
 */
private fun decodeCapture(path: String, rangeUnitToMm: Int = SpokeParser.RANGE_UNIT_MM) {
    val text = java.io.File(path).readText(Charsets.ISO_8859_1)
    val blocks = text.split("(UDP)").drop(1)
    println("=== 离线解码: $path ===")
    val unitName = when (rangeUnitToMm) { SpokeParser.RANGE_UNIT_DM -> "dm(×100)"; SpokeParser.RANGE_UNIT_CM -> "cm(×10)"; else -> "mm(×1)" }
    println("UDP 包数: ${blocks.size}  | rangeCellSize 单位假设: $unitName")
    var imgPk = 0; var spokesTotal = 0; var skippedTotal = 0
    val allSpokes = ArrayList<com.shipradar.contract.EchoSpoke>()
    val preamble = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x02)
    for ((i, blk) in blocks.withIndex()) {
        val header = blk.substringBefore('\n').trim()
        val dst = Regex("->([0-9.]+:[0-9]+)").find(header)?.groupValues?.get(1) ?: "?"
        val hex = blk.substringAfter('\n')
        val bytes = Regex("\\b[0-9A-Fa-f]{2}\\b").findAll(hex).map { it.value.toInt(16).toByte() }.toList().toByteArray()
        if (bytes.size < 28 || (bytes[0].toInt() ushr 4 and 0xF) != 4) { println("[$i] 非 IP 包,跳过"); continue }
        val ihl = (bytes[0].toInt() and 0x0F) * 4
        val payload = bytes.copyOfRange(ihl + 8, bytes.size)
        val isImage = dst.endsWith(":6678") || dst.endsWith(":6656")
        println("\n[$i] $header")
        println("    dst=$dst  载荷=${payload.size}B  首8=${payload.take(8).joinToString(" ") { "%02X".format(it) }}")
        println("    以 8字节帧头开头? ${payload.size >= 8 && payload.copyOfRange(0, 8).contentEquals(preamble)}")
        if (isImage) {
            val r = SpokeParser.parseDetailed(payload, rangeUnitToMm)
            imgPk++; spokesTotal += r.spokes.size; skippedTotal += r.skipped
            allSpokes.addAll(r.spokes)
            println("    SpokeParser → 解出 ${r.spokes.size} 辐条, 跳过 ${r.skipped}")
            r.spokes.firstOrNull()?.let { println("    首辐条: 方位=%.2f° 采样=${it.samples.size} seq=${it.sequenceNumber} 编码=${it.encoding} | rangeCellSize=${it.rangeCellSizeMm}mm cellsDiv2=${it.rangeCellsDiv2} 满量程=%.0fm".format(it.azimuthDeg, it.rangeMetersFull)) }
            r.spokes.lastOrNull()?.let { println("    末辐条: 方位=%.2f° 采样=${it.samples.size}".format(it.azimuthDeg)) }
            val nonZero = r.spokes.sumOf { s -> s.samples.count { it.toInt() != 0 } }
            println("    非零采样总数: $nonZero (有回波)")
        }
    }
    println("\n=== 汇总: 图像包 $imgPk, 共解出 $spokesTotal 辐条, 跳过 $skippedTotal ===")
    if (spokesTotal > 0 && skippedTotal == 0) println("✅ 真雷达图像数据 100% 解析通过 —— 协议一致性验证成功")

    // step1 点迹提取跑真实回波(目标从真实雷达图像里抓出来)。
    if (allSpokes.isNotEmpty()) {
        val az = allSpokes.map { it.azimuthDeg }
        val span = (az.maxOrNull()!! - az.minOrNull()!!)
        val plots = com.shipradar.uicore.target.PlotExtractor.extract(allSpokes)
        println("\n=== 跟踪管线 step1(点迹提取)跑真实回波 ===")
        println("输入: ${allSpokes.size} 辐条, 方位跨度 %.1f°".format(span))
        println("提取点迹: ${plots.size}")
        plots.take(12).forEach {
            println("  ${it.id}: 距离=%.3f NM  方位=%.1f°  峰值=${it.amplitudePeak?.toInt()}  单元=${it.cellCount}"
                .format(it.rangeNm, it.trueBearingDeg))
        }
        val full = allSpokes.first().rangeMetersFull
        println("注:本抓包约 %.0f° 扇区(<1 圈),足以验证点迹提取+方位;完整跟踪(step2/3)需多圈真实数据。".format(span))
        if (full < 500.0) {
            println("⚠ 满量程仅 %.0fm —— 距离偏小,疑似 rangeCellSize 单位为分米(dm)而非毫米(mm)。".format(full))
            println("  检测/方位链路正确(点迹落在扫描扇区内);绝对距离标定待厂商确认量程单位(见协议歧义清单)。")
        }
    }
}

// ----------------------------------------------------------------- 握手
private fun tryHandshake(nif: NetworkInterface?): com.shipradar.comms.halo.handshake.RadarLinkInfo? {
    val neg = HaloHandshake.NEGOTIATION_ENDPOINT
    val sock = mcastReceiver(neg.address, neg.port, nif)
    sock.soTimeout = 5_000
    return try {
        DatagramSocket().use { tx ->
            val req = HaloHandshake.buildLinkRequest()
            tx.send(DatagramPacket(req, req.size, InetSocketAddress(InetAddress.getByName(neg.address), neg.port)))
        }
        val buf = ByteArray(2048)
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            val p = DatagramPacket(buf, buf.size)
            try { sock.receive(p) } catch (e: SocketTimeoutException) { break }
            val payload = p.data.copyOf(p.length)
            runCatching { HaloHandshake.parseLinkAllow(payload) }.getOrNull()?.let { return it }
        }
        null
    } catch (e: Exception) {
        System.err.println("握手异常: ${e.message}")
        null
    } finally {
        runCatching { sock.close() }
    }
}

// ----------------------------------------------------------------- 网络
private fun resolveInterface(name: String?): NetworkInterface? {
    if (name != null) return NetworkInterface.getByName(name) ?: error("找不到网卡 '$name'")
    // 自动挑一块:up、非回环、支持组播、有 IPv4。
    return NetworkInterface.getNetworkInterfaces().toList().firstOrNull { ni ->
        ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
            ni.inetAddresses.toList().any { it.address.size == 4 }
    }
}

private fun mcastReceiver(group: String, port: Int, nif: NetworkInterface?): MulticastSocket {
    val s = MulticastSocket(null as SocketAddress?)
    s.reuseAddress = true
    s.bind(InetSocketAddress(port))
    val ga = InetSocketAddress(InetAddress.getByName(group), port)
    if (nif != null) s.joinGroup(ga, nif) else @Suppress("DEPRECATION") s.joinGroup(InetAddress.getByName(group))
    s.soTimeout = 1_000
    return s
}

private fun receiver(name: String, ep: Endpoint, nif: NetworkInterface?, onPacket: (ByteArray) -> Unit): Thread =
    thread(isDaemon = true, name = "rx-$name") {
        val sock = try {
            mcastReceiver(ep.address, ep.port, nif)
        } catch (e: Exception) {
            System.err.println("$name 加入组播失败: ${e.message}"); return@thread
        }
        val buf = ByteArray(65535)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                onPacket(p.data.copyOf(p.length))
            } catch (e: SocketTimeoutException) {
                // 周期超时,回到循环检查 running
            } catch (e: Exception) {
                if (running) System.err.println("$name 接收错误: ${e.message}")
            }
        }
        runCatching { sock.close() }
    }

// ----------------------------------------------------------------- 通道解码
private fun hex(b: ByteArray, n: Int): String =
    b.take(n).joinToString(" ") { "%02X".format(it) } + if (b.size > n) " …(${b.size}B)" else ""

private class ImageChannel(val hexN: Int) {
    var packets = 0L; var spokes = 0L; var skipped = 0L; private var first = true
    @Synchronized fun onPacket(b: ByteArray) {
        packets++
        val r = SpokeParser.parseDetailed(b)
        spokes += r.spokes.size; skipped += r.skipped
        if (first) {
            first = false
            println("IMAGE 首包(${b.size}B): ${hex(b, hexN)}")
            println("  → 解出 ${r.spokes.size} 辐条, 跳过 ${r.skipped}" +
                (r.spokes.firstOrNull()?.let { "; 首辐条 方位=%.1f° 采样=${it.samples.size}".format(it.azimuthDeg) } ?: ""))
        }
    }
    @Synchronized fun line() = "包$packets/辐条$spokes" + if (skipped > 0) "/跳$skipped" else ""
    @Synchronized fun summary() = println("IMAGE: 收 $packets 包, 解出 $spokes 辐条, 跳过 $skipped" +
        if (packets > 0 && spokes == 0L) "  ⚠ 收到包但解出 0 辐条 → 格式可能不符(查首包十六进制)" else "")
}

private class StatusChannel(val hexN: Int) {
    var packets = 0L; private var first = true; private var last = ""
    @Synchronized fun onPacket(b: ByteArray) {
        packets++
        val u = runCatching { HaloStatusParser.parseStatus(b) }
        last = u.getOrNull()?.toString()?.take(120) ?: "解析失败:${u.exceptionOrNull()?.message}"
        if (first) { first = false; println("STATUS 首包(${b.size}B): ${hex(b, hexN)}"); println("  → $last") }
    }
    @Synchronized fun line() = "包$packets"
    @Synchronized fun summary() = println("STATUS: 收 $packets 包" + if (packets > 0) "; 最近=$last" else "")
}

private class TargetChannel(val hexN: Int) {
    var packets = 0L; private var first = true; private var lastCount = 0
    @Synchronized fun onPacket(b: ByteArray) {
        packets++
        val ts = runCatching { TargetParser.parseTargets(b) }.getOrNull() ?: emptyList()
        lastCount = ts.size
        if (first) { first = false; println("TARGET 首包(${b.size}B): ${hex(b, hexN)}"); println("  → 解出 ${ts.size} 个目标") }
    }
    @Synchronized fun line() = "包$packets/目标$lastCount"
    @Synchronized fun summary() = println("TARGET: 收 $packets 包; 最近 $lastCount 个目标")
}

// ----------------------------------------------------------------- 参数
private class ProbeArgs(
    val iface: String?,
    val manualIp: String?,
    val noHandshake: Boolean,
    val durationSec: Int,
    val record: String?,
    val hex: Int,
) {
    companion object {
        fun parse(a: Array<String>): ProbeArgs {
            val m = HashMap<String, String>()
            val flags = HashSet<String>()
            var i = 0
            while (i < a.size) {
                val tok = a[i]
                when {
                    tok == "--no-handshake" -> flags += tok
                    // 同时支持 --key=value 与 --key value 两种写法。
                    tok.startsWith("--") && tok.contains('=') -> {
                        val eq = tok.indexOf('='); m[tok.substring(0, eq)] = tok.substring(eq + 1)
                    }
                    tok.startsWith("--") && i + 1 < a.size -> { m[tok] = a[i + 1]; i++ }
                }
                i++
            }
            return ProbeArgs(
                iface = m["--iface"],
                manualIp = m["--manual-ip"],
                noHandshake = "--no-handshake" in flags,
                durationSec = m["--duration"]?.toIntOrNull() ?: 60,
                record = m["--record"],
                hex = m["--hex"]?.toIntOrNull() ?: 48,
            )
        }
    }
}
