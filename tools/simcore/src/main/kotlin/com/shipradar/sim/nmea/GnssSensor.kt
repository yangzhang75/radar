package com.shipradar.sim.nmea

/**
 * GPS/GNSS 接收机。生成 **GGA / RMC / VTG / GLL / ZDA**(IEC 61162-1 ED6 §8.3.42/81/122/43/130)。
 *
 * @property time UTC 时刻(含日期,供 RMC/ZDA)。
 * @property latitude  纬度,正=北 负=南(十进制度)。
 * @property longitude 经度,正=东 负=西(十进制度)。
 * @property sogKn 对地航速(节)。
 * @property cogTrueDeg 对地真航向(度)。
 * @property fixQuality GGA 定位质量指示(§8.3.42 注1):0=无效,1=GPS,2=DGPS,…,8=模拟器。
 * @property satellitesUsed 解算所用卫星数(GGA 字段7,两位)。
 * @property hdop 水平精度因子(GGA 字段8)。
 * @property altitudeM 天线海拔(GGA 字段9,单位 M)。
 * @property geoidSepM 大地水准面差距(GGA 字段11,单位 M)。
 * @property magVarDeg 磁差,正=东 负=西;null=空字段(RMC 字段10/11)。
 * @property modeIndicator 定位模式指示 A/D/E/…(VTG/GLL/RMC,§8.3.122 注2);非空字段。
 * @property navStatusValid RMC ED6 新增"导航状态"字段(§8.3.81 注4):true→'S'(safe),false→'V'(无效)。
 *   TODO(待核 ED6 §8.3.81):S/C/U 的细分(caution/unsafe)取决于完好性/精度,本模拟器仅给 S 或 V。
 * @property talker talker 标识,GNSS 组合默认 "GN";单 GPS 可设 "GP"。安装相关。
 */
data class GnssSensor(
    val time: UtcTime,
    val latitude: Double,
    val longitude: Double,
    val sogKn: Double,
    val cogTrueDeg: Double,
    val fixQuality: Int = 1,
    val satellitesUsed: Int = 8,
    val hdop: Double = 1.0,
    val altitudeM: Double = 0.0,
    val geoidSepM: Double = 0.0,
    val magVarDeg: Double? = null,
    val modeIndicator: Char = 'A',
    val navStatusValid: Boolean = true,
    val talker: String = "GN",
) : NmeaSource {

    override fun toSentences(): List<String> = listOf(gga(), gll(), rmc(), vtg(), zda())

    /**
     * GGA — GPS 定位数据(§8.3.42)。字段:时间、纬、经、质量、卫星数、HDOP、海拔 M、水准面差 M、
     * DGPS 数据龄期(空)、参考站 id(空)。
     */
    fun gga(): String {
        val sats = "%02d".format(satellitesUsed)
        val body = "GGA,${Nmea.hms(time)},${Nmea.lat(latitude)},${Nmea.lon(longitude)}," +
            "$fixQuality,$sats,${Nmea.fixed(hdop)},${Nmea.fixed(altitudeM)},M,${Nmea.fixed(geoidSepM)},M,,"
        return Nmea.sentence(talker, body)
    }

    /** GLL — 地理位置 经纬(§8.3.43)。字段:纬、经、时间、状态 A、模式指示。 */
    fun gll(): String {
        val body = "GLL,${Nmea.lat(latitude)},${Nmea.lon(longitude)},${Nmea.hms(time)},A,$modeIndicator"
        return Nmea.sentence(talker, body)
    }

    /**
     * RMC — 推荐最小定位数据(§8.3.81)。字段:时间、状态 A、纬、经、SOG(节)、COG(真)、日期、
     * 磁差、E/W、模式指示、导航状态(ED6 新增)。
     */
    fun rmc(): String {
        val (mv, mvHemi) = Nmea.signed(magVarDeg, 'E', 'W')
        val navStatus = if (navStatusValid) 'S' else 'V'
        val body = "RMC,${Nmea.hms(time)},A,${Nmea.lat(latitude)},${Nmea.lon(longitude)}," +
            "${Nmea.fixed(sogKn)},${Nmea.deg360(cogTrueDeg)},${Nmea.ddmmyy(time)},$mv,$mvHemi,$modeIndicator,$navStatus"
        return Nmea.sentence(talker, body)
    }

    /** VTG — 对地航向与航速(§8.3.122)。字段:COG 真 T、COG 磁 M(空)、SOG 节 N、SOG km/h K、模式指示。 */
    fun vtg(): String {
        val body = "VTG,${Nmea.deg360(cogTrueDeg)},T,,M," +
            "${Nmea.fixed(sogKn)},N,${Nmea.fixed(Nmea.knToKmh(sogKn))},K,$modeIndicator"
        return Nmea.sentence(talker, body)
    }

    /** ZDA — 时间与日期(§8.3.130)。字段:时间、日、月、年、本地时区时、本地时区分。默认时区 00,00(UTC)。 */
    fun zda(): String {
        val body = "ZDA,${Nmea.hms(time)},%02d,%02d,%04d,00,00".format(time.day, time.month, time.year)
        return Nmea.sentence(talker, body)
    }
}
