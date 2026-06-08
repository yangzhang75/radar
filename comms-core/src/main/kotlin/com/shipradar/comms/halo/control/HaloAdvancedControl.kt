package com.shipradar.comms.halo.control

import com.shipradar.constants.HaloOpcodes
import com.shipradar.util.HaloFixedPoint

/**
 * T1.3a — advanced 00CB Q12-float control commands (协议文档 §雷达高级控制).
 *
 * These STC / SNR / aperture tuning parameters are NOT yet modelled in the frozen contract
 * `RadarCommand` (shared/, which T1.3 must not modify). They are exposed here as typed helpers so
 * the encoder is complete and testable against the doc; when the contract gains command types for
 * them, [HaloControlEncoder.encode] can dispatch here. Compliance: HALO-02.
 *
 * Frame: opcode 00CB + 4-byte sub-command (Fx00 0000) + 4-byte Q12 little-endian dB value + 12 zero
 * bytes. Worked example (设置最小信噪比 98.351):
 *   00CB F100 0000 9E25 0600 0000 0000 0000 0000 0000 0000
 */
object HaloAdvancedControl {

    /** 设置最小信噪比 [00CB F100 0000] — float dB, range -100.0..+100.0. */
    fun encodeMinSnr(db: Double) = float00cb(SUB_MIN_SNR, db)

    /** 设置视频光圈 [00CB 0B00 0000] — float dB, range 0.0..100.0. */
    fun encodeVideoAperture(db: Double) = float00cb(SUB_VIDEO_APERTURE, db)

    /** 设置量程STC 削减 [00CB F800 0000] — float dB, range -100.0..+100.0. */
    fun encodeRangeStcCut(db: Double) = float00cb(SUB_RANGE_STC_CUT, db)

    /** 设置量程STC 速率 [00CB F200 0000] — float dB/decade (user 1..100 -> 0.1..10.0). */
    fun encodeRangeStcRate(dbPerDec: Double) = float00cb(SUB_RANGE_STC_RATE, dbPerDec)

    /** 设置海浪STC 削减 [00CB F900 0000] — float dB, range -100.0..+100.0. */
    fun encodeSeaStcCut(db: Double) = float00cb(SUB_SEA_STC_CUT, db)

    /** 设置海浪STC 速率1 [00CB F500 0000] — float dB/decade. */
    fun encodeSeaStcRate1(dbPerDec: Double) = float00cb(SUB_SEA_STC_RATE1, dbPerDec)

    /** 设置海浪STC 速率2 [00CB F600 0000] — float dB/decade. */
    fun encodeSeaStcRate2(dbPerDec: Double) = float00cb(SUB_SEA_STC_RATE2, dbPerDec)

    /** 设置雨雪STC 削减 [00CB F700 0000] — float dB, range -100.0..+100.0. */
    fun encodeRainStcCut(db: Double) = float00cb(SUB_RAIN_STC_CUT, db)

    /** 设置雨雪STC 速率 [00CB FA00 0000] — float dB/decade. */
    fun encodeRainStcRate(dbPerDec: Double) = float00cb(SUB_RAIN_STC_RATE, dbPerDec)

    private fun float00cb(sub: Int, value: Double): ByteArray =
        HaloControlEncoder.advancedFrame(
            HaloOpcodes.ADVANCED_00CB, sub,
            valueBytes = HaloFixedPoint.encodeQ12LeBytes(value),
        )

    // 00CB sub-commands (4-byte little-endian; doc writes the low byte then 00 00 00)
    private const val SUB_MIN_SNR = 0xF1
    private const val SUB_VIDEO_APERTURE = 0x0B
    private const val SUB_RANGE_STC_CUT = 0xF8
    private const val SUB_RANGE_STC_RATE = 0xF2
    private const val SUB_SEA_STC_CUT = 0xF9
    private const val SUB_SEA_STC_RATE1 = 0xF5
    private const val SUB_SEA_STC_RATE2 = 0xF6
    private const val SUB_RAIN_STC_CUT = 0xF7
    private const val SUB_RAIN_STC_RATE = 0xFA
}
