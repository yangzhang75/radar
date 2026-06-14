package com.shipradar.sim.nmea

/**
 * 舵角传感器。生成 **RSA**(IEC 61162-1 ED6 §8.3.86)。
 *
 * @property starboardRudderDeg 右舵(单舵船即主舵)角度;−=船首左转(§8.3.86 注1)。无单位的相对量。
 * @property portRudderDeg 左舵角度(双舵);null=单舵,左舵字段空、状态 V。
 * @property talker talker 标识,默认 "AG"(autopilot, general)。安装相关。
 */
data class RudderSensor(
    val starboardRudderDeg: Double,
    val portRudderDeg: Double? = null,
    val talker: String = "AG",
) : NmeaSource {

    override fun toSentences(): List<String> = listOf(rsa())

    /** RSA — 舵角传感器(§8.3.86)。字段:右/主舵角、状态 A、左舵角、状态。 */
    fun rsa(): String {
        val port = Nmea.opt(portRudderDeg)
        val portStatus = if (portRudderDeg != null) 'A' else 'V'
        return Nmea.sentence(talker, "RSA,${Nmea.fixed(starboardRudderDeg)},A,$port,$portStatus")
    }
}

/**
 * 自动舵 / 航向操纵。生成 **HSC**(航向操纵指令,§8.3.56)与 **HTC/HTD**(航向/航迹控制,§8.3.58)。
 *
 * @property commandedHeadingDeg 操纵指令航向(度)。
 * @property headingReference 航向基准:'T' 真 / 'M' 磁(HSC 字段 & HTC 字段"在用航向基准")。
 * @property isCommand 句状态:true→'C'(配置命令),false→'R'(当前状态回报)(§8.3.56/58 注:非空字段)。
 * @property selectedSteeringMode HTC 选定操舵模式 M/S/H/T/R(§8.3.58 注2)。
 * @property turnMode HTC 转向模式 'R' 半径 / 'T' 转率 / 'N' 不控制(§8.3.58 注3)。
 * @property override HTC 直接接管(override)A/V(§8.3.58 注1);'V'=不接管。
 * @property commandedRudderAngleDeg / [commandedRudderDirection] R 模式用(L/R 方向)。
 * @property commandedRudderLimitDeg / [commandedOffHeadingLimitDeg] 限制值。
 * @property commandedRadiusOfTurnNm / [commandedRateOfTurnDegMin] 按 [turnMode] 取一。
 * @property commandedOffTrackLimitNm / [commandedTrackDeg] 航迹控制(T 模式)用。
 * @property talker talker 标识,默认 "AG"。
 *
 * TODO(待核 ED6 §8.3.58):HTC/HTD 的精确字段表在 FDIS PDF 中为图框,未能机读提取。下方字段顺序按
 * 通行的 IEC 61162-1 HTC/HTD 布局实现(override,舵角,舵向,操舵模式,转向模式,舵限,偏航限,转向半径,
 * 转率,操纵航向,偏航迹限,指令航迹,在用航向基准,句状态)。HTD 作为回读用与 HTC 同构。送认证前请按
 * ED6 §8.3.58 字段框逐字段复核字段数与顺序,尤其 HTD 是否含额外的实际值/状态字段。
 */
data class Autopilot(
    val commandedHeadingDeg: Double,
    val headingReference: Char = 'T',
    val isCommand: Boolean = true,
    val selectedSteeringMode: Char = 'H',
    val turnMode: Char = 'N',
    val override: Boolean = false,
    val commandedRudderAngleDeg: Double? = null,
    val commandedRudderDirection: Char? = null,
    val commandedRudderLimitDeg: Double? = null,
    val commandedOffHeadingLimitDeg: Double? = null,
    val commandedRadiusOfTurnNm: Double? = null,
    val commandedRateOfTurnDegMin: Double? = null,
    val commandedOffTrackLimitNm: Double? = null,
    val commandedTrackDeg: Double? = null,
    val talker: String = "AG",
) : NmeaSource {

    private val status: Char get() = if (isCommand) 'C' else 'R'

    override fun toSentences(): List<String> = listOf(hsc(), htc())

    /**
     * HSC — 航向操纵指令(§8.3.56)。字段:操纵航向真、T、操纵航向磁、M、句状态(R/C)。
     * 指令航向放入 [headingReference] 指定的基准字段,另一字段留空。
     */
    fun hsc(): String {
        val h = Nmea.deg360(commandedHeadingDeg)
        val (htrue, hmag) = if (headingReference == 'M') "" to h else h to ""
        return Nmea.sentence(talker, "HSC,$htrue,T,$hmag,M,$status")
    }

    /** HTC — 航向/航迹控制命令(§8.3.58;字段顺序见类 KDoc 的 TODO)。 */
    fun htc(): String = Nmea.sentence(talker, "HTC,${htcBody()}")

    /** HTD — 航向/航迹控制数据(回读,§8.3.58);与 HTC 同构(见 TODO)。 */
    fun htd(): String = Nmea.sentence(talker, "HTD,${htcBody()}")

    private fun htcBody(): String = buildString {
        append(if (override) 'A' else 'V').append(',')                       // 1 override
        append(Nmea.opt(commandedRudderAngleDeg)).append(',')                // 2 commanded rudder angle
        append(commandedRudderDirection ?: "").append(',')                   // 3 rudder direction L/R
        append(selectedSteeringMode).append(',')                             // 4 selected steering mode
        append(turnMode).append(',')                                         // 5 turn mode
        append(Nmea.opt(commandedRudderLimitDeg)).append(',')                // 6 rudder limit
        append(Nmea.opt(commandedOffHeadingLimitDeg)).append(',')            // 7 off-heading limit
        append(Nmea.opt(commandedRadiusOfTurnNm)).append(',')                // 8 radius of turn
        append(Nmea.opt(commandedRateOfTurnDegMin)).append(',')              // 9 rate of turn
        append(Nmea.deg360(commandedHeadingDeg)).append(',')                 // 10 commanded heading-to-steer
        append(Nmea.opt(commandedOffTrackLimitNm)).append(',')               // 11 off-track limit
        append(if (commandedTrackDeg == null) "" else Nmea.deg360(commandedTrackDeg)).append(',') // 12 commanded track
        append(headingReference).append(',')                                 // 13 heading reference in use
        append(status)                                                       // 14 sentence status (R/C)
    }
}
