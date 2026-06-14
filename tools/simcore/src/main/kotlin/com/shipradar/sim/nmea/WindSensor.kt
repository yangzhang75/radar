package com.shipradar.sim.nmea

/**
 * 风传感器。生成 **MWV(相对 R / 真 T)** 与 **MWD**(IEC 61162-1 ED6 §8.3.69/68)。
 *
 * @property relativeAngleDeg MWV 相对风角(度,相对船首/中线 0–359.9)。
 * @property relativeSpeed MWV 相对(视)风速,单位见 [speedUnit]。
 * @property theoreticalAngleDeg MWV 真(理论/计算)风角;null 则不发 MWV(T)。
 * @property theoreticalSpeed MWV 真风速;与 [theoreticalAngleDeg] 同时给出。
 * @property speedUnit MWV 风速单位:K km/h、M m/s、N 节、S 法定英里/时(§8.3.69)。
 * @property trueDirectionDeg MWD 真风向(风从该方向吹来,相对北,度);null 则不发 MWD。
 * @property trueSpeedKn MWD 真风速(节);与 [trueDirectionDeg] 同时给出。MWD 同时输出节(N)与 m/s(M)。
 * @property magDirectionDeg MWD 磁风向(度);null=空字段。
 * @property talker talker 标识,气象仪默认 "WI"。
 */
data class WindSensor(
    val relativeAngleDeg: Double,
    val relativeSpeed: Double,
    val theoreticalAngleDeg: Double? = null,
    val theoreticalSpeed: Double? = null,
    val speedUnit: Char = 'N',
    val trueDirectionDeg: Double? = null,
    val trueSpeedKn: Double? = null,
    val magDirectionDeg: Double? = null,
    val talker: String = "WI",
) : NmeaSource {

    override fun toSentences(): List<String> = buildList {
        add(mwvRelative())
        if (theoreticalAngleDeg != null && theoreticalSpeed != null) add(mwvTheoretical())
        if (trueDirectionDeg != null && trueSpeedKn != null) add(mwd())
    }

    /** MWV — 风速风角(§8.3.69)。字段:风角、参考(R/T)、风速、单位、状态 A。 */
    fun mwvRelative(): String =
        Nmea.sentence(talker, "MWV,${Nmea.deg360(relativeAngleDeg)},R,${Nmea.fixed(relativeSpeed)},$speedUnit,A")

    fun mwvTheoretical(): String =
        Nmea.sentence(talker, "MWV,${Nmea.deg360(theoreticalAngleDeg ?: 0.0)},T,${Nmea.fixed(theoreticalSpeed ?: 0.0)},$speedUnit,A")

    /**
     * MWD — 真风向与风速(§8.3.68)。字段:真风向、T、磁风向、M、风速节、N、风速 m/s、M。
     */
    fun mwd(): String {
        val kn = trueSpeedKn ?: 0.0
        val magDir = if (magDirectionDeg == null) "" else Nmea.deg360(magDirectionDeg)
        val body = "MWD,${Nmea.deg360(trueDirectionDeg ?: 0.0)},T,$magDir,M," +
            "${Nmea.fixed(kn)},N,${Nmea.fixed(Nmea.knToMs(kn))},M"
        return Nmea.sentence(talker, body)
    }
}
