package com.shipradar.app.demo

import com.shipradar.comms.halo.handshake.LinkEvent
import com.shipradar.comms.iec450.Iec450Group
import com.shipradar.comms.service.CommsRouter
import com.shipradar.contract.RadarPowerState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * On-device demo data source — **the real pipeline, no radar/network needed**.
 *
 * Unlike the in-app `FakeSpokes`/`FakeTargets` (which inject already-parsed objects), this builds
 * **real HALO wire bytes** + **real IEC 61162 sentences** and feeds them through the actual
 * [CommsRouter] (real SpokeParser / Iec61162Parser / 450 transport) → its RadarDataBus flows → UI.
 * So what you see on screen has gone through the genuine decode path. The same router/flows are what
 * the production [com.shipradar.comms.service.RadarCommsService] uses with a real multicast transport;
 * here we just feed the router directly instead of over a socket.
 */
object DemoFeed {
    private const val SPOKES_PER_REV = 2048
    private const val N = 1024 // samples/spoke
    private const val OVER_SCAN = 1.8

    // 数据包帧头(doc §辐条分配:Spoke[32] 数组前的 8 字节 `0100 0000 0020 0002`)。与真线缆 + halofeed
    // (tools/halofeed SpokePacket.FRAME_PREAMBLE)一致;SpokeParser 会探测并跳过它。语义待张建确认。
    private val FRAME_PREAMBLE = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x02)
    private const val PRE = 8 // FRAME_PREAMBLE.size

    /** Drive [router] forever: a rotating echo picture + a slowly-changing own-ship fix. */
    suspend fun run(router: CommsRouter) {
        // 模拟已建链 → linkState=CONNECTED(BITE / 链路监视显示健康,而非误报 FAULT)。
        router.applyLinkEvent(LinkEvent.AllowReceived)
        var spoke = 0
        var seq = 0
        var tick = 0L
        while (true) {
            // 用墙钟做 now:链路时延 / BITE 年龄计算才正确(合成 tick 与 UI 墙钟不同域会算出天文数字)。
            val now = System.currentTimeMillis()
            router.onHaloImage(spokePacket(spoke, seq), now = now)
            // 双量程: 同步喂 Radar B 一幅近距景象 (独立流, 见 onHaloImageB)。dual-range 画面展示用。
            router.onHaloImageB(spokePacketB(spoke, seq), now = now)
            // 状态通道 01C4 ~每2s 一帧(发射态),让 SIM 也走真状态解码(链路监视可见 STATUS 活跃)。
            if (spoke % 333 == 0) router.onHaloStatus(statusPacket(), now = now)
            // Periodic own-ship fix. Heading gently yaws ±3° around 087° (realistic — NOT a continuous
            // spin), so the data bar shows live values without the whole picture rotating.
            if (spoke % 64 == 0) {
                val headingDeg = 87.0 + 3.0 * sin(tick / 2500.0)
                val sogKn = 12.4 + 0.4 * sin(tick / 1700.0)
                router.on450(Iec450Group.SATD, frame450("HEHDT,${"%05.1f".format(headingDeg)},T"), now = now)
                router.on450(
                    Iec450Group.NAVD,
                    frame450("GPRMC,123519,A,3113.80,N,12209.00,E,${"%04.1f".format(sogKn)},${"%05.1f".format(headingDeg)},090625,,"),
                    now = now,
                )
            }
            spoke = (spoke + 1) % SPOKES_PER_REV
            seq = (seq + 1) and 0x0FFF
            tick += 6
            delay(6) // ~12 s/rev at 2048 spokes
        }
    }

    // --- HALO image spoke: pack one spoke into the real 24-byte header + 4-bit samples ---------

    private fun spokePacket(spokeIndex: Int, seq: Int): ByteArray {
        val azimuthDeg = 360.0 * spokeIndex / SPOKES_PER_REV
        val s = ByteArray(N) // 0 = no echo (clean dark background; no synthetic clutter)
        if (azimuthDeg in 30.0..85.0) for (i in frac(0.65)..frac(0.72)) s[i] = 14 // coastline arc
        if (abs(azimuthDeg - 135.0) < 1.5) for (i in frac(0.39)..frac(0.41)) s[i] = 15 // point target
        val doppler = abs(azimuthDeg - 210.0) < 1.0
        if (doppler) for (i in frac(0.54)..frac(0.56)) s[i] = 15 // approaching (Doppler)

        val enc = if (doppler) 1 else 0
        val azRaw = (4096.0 * spokeIndex / SPOKES_PER_REV).toInt() and 0x1FFF
        val out = ByteArray(PRE + 24 + N / 2)
        FRAME_PREAMBLE.copyInto(out, 0) // 8 字节包帧头(辐条数组之前)
        // word0: spokeLength(12) | seq(16..27) | encoding(28..29)
        putLe(out, PRE + 0, (536 and 0xFFF) or ((seq and 0xFFF) shl 16) or ((enc and 0x3) shl 28))
        // word1: nOfSamples(0..11) | bitsPerSample(12..15) | rangeCellSize_mm(16..31)
        putLe(out, PRE + 4, (N and 0xFFF) or ((4 and 0xF) shl 12) or ((1500 and 0xFFFF) shl 16))
        // word2: azimuth(0..12) | compassInvalid(bit31)=1 (demo has no heading sensor on the spoke)
        putLe(out, PRE + 8, azRaw or (1 shl 31))
        // word3: rangeCellsDiv2(0..15); words4,5 reserved=0
        putLe(out, PRE + 12, 512 and 0xFFFF)
        var p = PRE + 24
        var i = 0
        while (i < N) { // 4-bit pack, low index = low nibble (matches SpokeParser)
            out[p++] = ((s[i].toInt() and 0xF) or ((s[i + 1].toInt() and 0xF) shl 4)).toByte()
            i += 2
        }
        return out
    }

    /**
     * Radar B 近距景象 — 几个近距点目标 + 一段近岸弧,集中在低距离样本 (小 i),所以在短量程
     * (典型 1.5 NM) 上清晰可见,与 Radar A 的远景形成"双量程"对比。
     */
    private fun spokePacketB(spokeIndex: Int, seq: Int): ByteArray {
        val azimuthDeg = 360.0 * spokeIndex / SPOKES_PER_REV
        val s = ByteArray(N)
        if (azimuthDeg in 300.0..340.0) for (i in frac(0.18)..frac(0.22)) s[i] = 13 // 近岸弧
        if (abs(azimuthDeg - 45.0) < 1.5) for (i in frac(0.12)..frac(0.13)) s[i] = 15  // 近距点目标
        if (abs(azimuthDeg - 160.0) < 1.5) for (i in frac(0.28)..frac(0.29)) s[i] = 15 // 近距点目标
        val azRaw = (4096.0 * spokeIndex / SPOKES_PER_REV).toInt() and 0x1FFF
        val out = ByteArray(PRE + 24 + N / 2)
        FRAME_PREAMBLE.copyInto(out, 0)
        putLe(out, PRE + 0, (536 and 0xFFF) or ((seq and 0xFFF) shl 16))
        putLe(out, PRE + 4, (N and 0xFFF) or ((4 and 0xF) shl 12) or ((1500 and 0xFFFF) shl 16))
        putLe(out, PRE + 8, azRaw or (1 shl 31))
        putLe(out, PRE + 12, 512 and 0xFFFF)
        var p = PRE + 24
        var i = 0
        while (i < N) {
            out[p++] = ((s[i].toInt() and 0xF) or ((s[i + 1].toInt() and 0xF) shl 4)).toByte()
            i += 2
        }
        return out
    }

    /** HALO 模式状态 01C4(2字节头 01 C4 + 4×uint32 LE:状态/定时/预热/定时计数)。与 halofeed StatusPacket 同格式。 */
    private fun statusPacket(): ByteArray {
        val out = ByteArray(2 + 16)
        out[0] = 0x01; out[1] = 0xC4.toByte()
        putLe(out, 2, RadarPowerState.TRANSMIT.ordinal) // 状态=发射;6/10/14 偏移保持 0
        return out
    }

    private fun putLe(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v ushr 8) and 0xFF).toByte()
        a[off + 2] = ((v ushr 16) and 0xFF).toByte()
        a[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun frac(f: Double): Int = (f * N / OVER_SCAN).roundToInt().coerceIn(0, N - 1)

    // --- 61162-450 framing: wrap a sentence BODY in a sourced UdPbC datagram (real transport) ---

    private fun frame450(body: String): ByteArray {
        fun xor(t: String) = t.fold(0) { a, c -> a xor c.code } and 0xFF
        val src = "s:RA0001" // conformant 450 source id (talker + 4 digits)
        val line = "\\$src*${"%02X".format(xor(src))}\\" + "\$$body*${"%02X".format(xor(body))}" + "\r\n"
        return "UdPbC".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + line.toByteArray(Charsets.ISO_8859_1)
    }
}
