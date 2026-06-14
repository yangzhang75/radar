package com.shipradar.sim.ais

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * W8-B — encodes [AisTarget]s into `!AIVDM` sentences.
 *
 * Two layers (kept separate so each can be cited against its own standard):
 *  - **Message bit layout** — ITU-R M.1371-5 §3.3: Message 1/3 (position report, 168 bit),
 *    Message 5 (static & voyage, 424 bit, 2 fragments), Message 24 A/B (Class B static, 168 bit each).
 *  - **Envelope** — IEC 61162-1 ED6 §7.3.4 / §8.2: six-bit armoring ([SixBit]), the
 *    `!AIVDM,<count>,<idx>,<seq>,<chan>,<payload>,<fill>*HH` frame and its XOR checksum (§7.2.4).
 *
 * Field "not available" sentinels are exactly those ITU-R M.1371-5 defines; values outside a field's
 * range are clamped to the sentinel rather than wrapped.
 */
object AisEncoder {

    // ITU-R M.1371-5 §3.3 "not available" sentinels.
    private const val LON_NA = 0x6791AC0   // 181° in 1/10000 min
    private const val LAT_NA = 0x3412140   // 91° in 1/10000 min
    private const val SOG_NA = 1023
    private const val COG_NA = 3600
    private const val HDG_NA = 511
    private const val ROT_NA = -128
    private const val MAX_PAYLOAD_CHARS = 60   // keeps each sentence within the 82-char §7.3.1 limit

    /** AIS channel designator for the AIVDM envelope. */
    enum class Channel(val code: Char) { A('A'), B('B') }

    /**
     * Everything needed to make a target visible to a receiver: its **static/voyage** report
     * (Message 5) followed by a **position** report (Message 3). This is the normal GUI entry —
     * add a target, then re-emit [positionReport] each tick as you nudge position/speed.
     *
     * @param seqId sequential message id 0..9 for the multi-fragment Message 5 (must differ between
     *        concurrently-interleaved multipart messages on the same channel).
     */
    fun encodeTarget(target: AisTarget, channel: Channel = Channel.A, seqId: Int = 0): List<String> =
        staticVoyage(target, channel, seqId) + positionReport(target, messageType = 3, channel = channel)

    /**
     * Message 1/3 position report (168 bit) → a single AIVDM sentence.
     * @param messageType 1 (scheduled) or 3 (response to interrogation).
     * @param timestamp UTC second of the report 0..59, or 60 = not available (default).
     */
    fun positionReport(
        target: AisTarget,
        messageType: Int = 1,
        channel: Channel = Channel.A,
        timestamp: Int = 60,
        repeatIndicator: Int = 0,
    ): String {
        require(messageType == 1 || messageType == 3) { "position report must be type 1 or 3" }
        val w = BitWriter()
        w.uint(messageType, 6)
        w.uint(repeatIndicator, 2)
        w.uint(target.mmsi.toLong(), 30)
        w.uint(target.navStatus.coerceIn(0, 15), 4)
        w.int(encodeRot(target.rotDegMin), 8)
        w.uint(encodeSog(target.sogKn), 10)
        w.uint(0, 1)                                   // position accuracy (low)
        w.int(encodeLon(target.longitude), 28)
        w.int(encodeLat(target.latitude), 27)
        w.uint(encodeCog(target.cogDeg), 12)
        w.uint(encodeHeading(target.headingDeg), 9)
        w.uint(timestamp.coerceIn(0, 63), 6)
        w.uint(0, 2)                                   // manoeuvre indicator: not available
        w.uint(0, 3)                                   // spare
        w.uint(0, 1)                                   // RAIM not in use
        w.uint(0, 19)                                  // radio status
        // 168 bits total.
        return singleSentence(w.toBooleanArray(), channel)
    }

    /**
     * Message 5 static & voyage related data (424 bit) → **two** AIVDM fragments (the payload exceeds
     * one sentence). [seqId] ties the fragments together.
     */
    fun staticVoyage(target: AisTarget, channel: Channel = Channel.A, seqId: Int = 0): List<String> {
        val w = BitWriter()
        w.uint(5, 6)
        w.uint(0, 2)                                   // repeat indicator
        w.uint(target.mmsi.toLong(), 30)
        w.uint(0, 2)                                   // AIS version (ITU-R M.1371)
        w.uint(target.imo.toLong().coerceIn(0, 0x3FFFFFFF), 30)
        w.text(target.callsign, 7)                     // 42 bit
        w.text(target.name, 20)                        // 120 bit
        w.uint(target.shipType.coerceIn(0, 255), 8)
        w.uint(target.dimToBow.coerceIn(0, 511), 9)
        w.uint(target.dimToStern.coerceIn(0, 511), 9)
        w.uint(target.dimToPort.coerceIn(0, 63), 6)
        w.uint(target.dimToStarboard.coerceIn(0, 63), 6)
        w.uint(0, 4)                                   // EPFD type: undefined
        w.uint(0, 4)                                   // ETA month
        w.uint(0, 5)                                   // ETA day
        w.uint(24, 5)                                  // ETA hour: not available
        w.uint(60, 6)                                  // ETA minute: not available
        w.uint((target.draughtMeters * 10).roundToInt().coerceIn(0, 255), 8)
        w.text(target.destination, 20)                 // 120 bit
        w.uint(1, 1)                                   // DTE: not ready
        w.uint(0, 1)                                   // spare
        // 424 bits total.
        return multiSentence(w.toBooleanArray(), channel, seqId)
    }

    /**
     * Message 24 Class B static — Part A (name) and Part B (type/call sign/dimensions), 168 bit each,
     * emitted as two single-fragment AIVDM sentences. Optional Class-B equivalent of Message 5.
     */
    fun classBStatic(target: AisTarget, channel: Channel = Channel.A): List<String> {
        // Part A
        val a = BitWriter()
        a.uint(24, 6); a.uint(0, 2); a.uint(target.mmsi.toLong(), 30)
        a.uint(0, 2)                                   // part number 0 = A
        a.text(target.name, 20)                        // 120 bit
        a.uint(0, 8)                                   // spare → 168 bit
        // Part B
        val b = BitWriter()
        b.uint(24, 6); b.uint(0, 2); b.uint(target.mmsi.toLong(), 30)
        b.uint(1, 2)                                   // part number 1 = B
        b.uint(target.shipType.coerceIn(0, 255), 8)
        b.text("", 3)                                  // vendor ID (18 bit)
        b.uint(0, 4)                                   // unit model code
        b.uint(0, 20)                                  // serial number
        b.text(target.callsign, 7)                     // 42 bit
        b.uint(target.dimToBow.coerceIn(0, 511), 9)
        b.uint(target.dimToStern.coerceIn(0, 511), 9)
        b.uint(target.dimToPort.coerceIn(0, 63), 6)
        b.uint(target.dimToStarboard.coerceIn(0, 63), 6)
        b.uint(0, 6)                                   // spare → 168 bit
        return listOf(
            singleSentence(a.toBooleanArray(), channel),
            singleSentence(b.toBooleanArray(), channel),
        )
    }

    // ---- envelope (IEC 61162-1 §7.3.4 / §8.2 / §7.2.4) ----

    private fun singleSentence(bits: BooleanArray, channel: Channel): String {
        val (payload, fill) = SixBit.armor(bits)
        return aivdmLine(1, 1, null, channel, payload, fill)
    }

    private fun multiSentence(bits: BooleanArray, channel: Channel, seqId: Int): List<String> {
        val (payload, fill) = SixBit.armor(bits)
        val chunks = payload.chunked(MAX_PAYLOAD_CHARS)
        val count = chunks.size
        return chunks.mapIndexed { i, chunk ->
            // Fill bits belong to the final fragment only (they pad the whole message's tail).
            val f = if (i == count - 1) fill else 0
            aivdmLine(count, i + 1, seqId, channel, chunk, f)
        }
    }

    /** Build one `!AIVDM,...*HH` line. [seqId] null ⇒ empty sequential-id field (single-fragment). */
    fun aivdmLine(count: Int, index: Int, seqId: Int?, channel: Channel, payload: String, fill: Int): String {
        val body = "AIVDM,$count,$index,${seqId ?: ""},${channel.code},$payload,$fill"
        return "!$body*${checksum(body)}"
    }

    /** NMEA/IEC 61162-1 §7.2.4 checksum: XOR of every char between `!` and `*`, as 2 hex digits. */
    fun checksum(body: String): String {
        var x = 0
        for (c in body) x = x xor c.code
        return x.toString(16).uppercase().padStart(2, '0')
    }

    // ---- field encoders (ITU-R M.1371-5 §3.3) ----

    private fun encodeLat(deg: Double): Int =
        if (abs(deg) > 90.0) LAT_NA else (deg * 600000.0).roundToInt()

    private fun encodeLon(deg: Double): Int =
        if (abs(deg) > 180.0) LON_NA else (deg * 600000.0).roundToInt()

    private fun encodeSog(kn: Double): Int =
        if (kn < 0) SOG_NA else (kn * 10.0).roundToLong().coerceAtMost(1022L).toInt()

    private fun encodeCog(deg: Double): Int {
        if (deg < 0) return COG_NA
        return ((deg % 360.0) * 10.0).roundToInt().coerceIn(0, 3599)
    }

    private fun encodeHeading(deg: Int?): Int =
        if (deg == null) HDG_NA else (deg % 360).coerceIn(0, 359)

    /** ROT: transmitted as `4.733·√(°/min)`, sign preserved, clamped to ±126; 0 = not turning. */
    private fun encodeRot(degMin: Double): Int {
        if (degMin == 0.0) return 0
        val mag = (4.733 * sqrt(abs(degMin))).roundToInt().coerceIn(0, 126)
        return if (degMin < 0) -mag else mag
    }
}
