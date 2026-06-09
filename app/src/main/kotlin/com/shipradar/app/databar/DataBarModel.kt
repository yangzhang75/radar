package com.shipradar.app.databar

import com.shipradar.contract.MasterSlave
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SeaMode
import com.shipradar.contract.SensorKind
import com.shipradar.uicore.ppi.PpiOrientation
import kotlin.math.abs
import kotlin.math.floor

/**
 * T2.7 / 合规追溯 ALRM-02 — 数据栏「防撞要素永久显示」的 **纯逻辑模型**（无 Compose / 无 Android）。
 *
 * 这是合规可验证的核心：把 ([OwnShipData], [RadarStatus], [RadarDisplaySettings], 传感器有效性) 归约成一份
 * **固定、穷举、有序** 的永久显示项清单 [DataField]。Composable [DataBar] 只负责把它画出来；
 * 「逐项必显」由本模型 + 单元测试保证，与渲染解耦。
 *
 * 来源（IEC 62388 FDIS，逐条核对自 ~/Desktop/雷达开发资料/标准资料/IEC62388_FDIS.pdf）：
 *  - **Annex I（规范性）Table I.1** 雷达数据/控制功能逻辑分组——本清单的骨架：
 *      Own ship（Position / Heading-speed）、Range and mode（Range scale / Orientation / Stabilisation / Motion）、
 *      Radar signal（Gain / Sea / Rain / Processing）、Radar system（Master/slave …）。
 *  - §3.16 “permanent indication”：在设备运行的任何时刻持续可见的状态指示。
 *  - 各单项永久指示条款见 [DataKey.clause]。
 *  - 「不得遮挡」：§3.45 operational display area 明确 **不含** user dialogue area；§6326 数据区/图形
 *    “shall not mask, obscure or degrade the radar image”。⇒ 数据栏必须置于操作显示区之外的专用条带
 *    （RadarScreen 的 top 槽），不得压在 PPI 回波之上。布局在 [DataBar] 落实，本模型不产出几何。
 *  - 失效处理：§14.2.2.1 (MSC.192/8.2.1) 不得使用无效数据且须清晰标示；§16.2.1 (MSC.192/9) 须永久指示
 *    失效输入状态；§16.2.2 航向丢失回落非稳定 head-up；§16.1.9 传感器失效报警。
 *    ⇒ 传感器失效时显示占位符 + [FieldSeverity.FAIL]，绝不留空或显示陈旧值。
 */
object DataBarModel {

    /** 海里换算（1 NM = 1852 m）。 */
    private const val METERS_PER_NM = 1852.0

    /**
     * 构造永久显示清单。返回顺序固定、内容穷举：无论入参如何，每个 [DataKey] 恰好出现一次
     * （ALRM-02「必显清单逐项」）。失效项以占位符 + [FieldSeverity.FAIL]/[FieldSeverity.DEGRADED] 表达。
     *
     * @param sensorValidity 各传感器有效性；缺省取 [OwnShipData.sourceValidity]。键缺失视为「未知=按有效处理」，
     *   仅当显式为 false 或对应数值为 null 时才判失效（避免误报）。
     */
    fun build(
        ownShip: OwnShipData,
        status: RadarStatus,
        settings: RadarDisplaySettings,
        sensorValidity: Map<SensorKind, Boolean> = ownShip.sourceValidity,
    ): List<DataField> {
        fun valid(kind: SensorKind) = sensorValidity[kind] != false
        val headingOk = valid(SensorKind.HEADING) && ownShip.headingDeg != null
        val positionOk = valid(SensorKind.POSITION) && ownShip.latitude != null && ownShip.longitude != null
        val cogSogOk = valid(SensorKind.COG_SOG) && ownShip.sogKn != null
        val radarLinkOk = valid(SensorKind.RADAR_LINK)
        // 雷达链路丢失时，雷达侧字段（量程/增益/抑制/主从）为陈旧值 -> 降级标示（§14.2.2.1）。
        val radarSev = if (radarLinkOk) FieldSeverity.OK else FieldSeverity.DEGRADED

        val fields = ArrayList<DataField>(13)

        // ---- Own ship information (Annex I) ----
        fields += DataField(
            DataKey.POSITION,
            if (positionOk) formatLatLon(ownShip.latitude!!, ownShip.longitude!!) else "POS ---",
            if (positionOk) FieldSeverity.OK else FieldSeverity.FAIL,
        )
        fields += DataField(
            DataKey.HEADING,
            if (headingOk) "${fmtDeg(ownShip.headingDeg!!)} ${if (ownShip.headingTrue) "T" else "M"}" else "HDG ---",
            if (headingOk) FieldSeverity.OK else FieldSeverity.FAIL,
        )
        fields += DataField(
            DataKey.SPEED,
            if (cogSogOk) "${trim1(ownShip.sogKn!!)} kn" + (ownShip.cogDeg?.let { " ${fmtDeg(it)}" } ?: "") else "SPD ---",
            if (cogSogOk) FieldSeverity.OK else FieldSeverity.FAIL,
        )

        // ---- Range and mode information (Annex I) ----
        fields += DataField(DataKey.RANGE_SCALE, formatRangeNm(status.rangeMeters), radarSev)
        // 方位定向模式。北向上/航向上为方位稳定，需有效航向；航向失效则按 §16.2.2 回落 head-up。
        val orientationDegraded = settings.orientation != PpiOrientation.HEAD_UP && !headingOk
        fields += DataField(
            DataKey.ORIENTATION,
            if (orientationDegraded) "${orientationLabel(settings.orientation)}→H UP*" else orientationLabel(settings.orientation),
            if (orientationDegraded) FieldSeverity.DEGRADED else FieldSeverity.OK,
        )
        fields += DataField(DataKey.MOTION, motionLabel(settings.motionMode), FieldSeverity.OK)
        // 稳定参考 sea/ground；ground 稳定需有效对地速度（COG/SOG）。
        val stabDegraded = settings.stabilisation == Stabilisation.GROUND && !cogSogOk
        fields += DataField(
            DataKey.STABILISATION,
            stabilisationLabel(settings.stabilisation),
            if (stabDegraded) FieldSeverity.DEGRADED else FieldSeverity.OK,
        )

        // ---- Target / vector information (Annex I) ----
        // §11.5.5 (MSC.192/5.27.3): 永久指示矢量模式(真/相对)、时间、稳定。
        fields += DataField(
            DataKey.VECTOR,
            "${vectorModeLabel(settings.vectorMode)} ${settings.vectorTimeMin}min",
            FieldSeverity.OK,
        )

        // ---- Radar signal information (Annex I) ----
        fields += DataField(
            DataKey.GAIN,
            if (status.gainAuto) "AUTO" else "MAN ${status.gain}",
            radarSev,
        )
        fields += DataField(DataKey.SEA, seaLabel(status.seaMode, status.seaLevel), radarSev)
        fields += DataField(DataKey.RAIN, "RAIN ${status.rainLevel}", radarSev)

        // ---- Radar system information (Annex I) ----
        fields += DataField(
            DataKey.MASTER_SLAVE,
            if (status.masterSlave == MasterSlave.MASTER) "MASTER" else "SLAVE",
            radarSev,
        )
        // 扫描器/链路状态——§16.2.1 (MSC.192/9) 永久指示失效输入状态。
        fields += DataField(
            DataKey.SCANNER,
            if (radarLinkOk) powerLabel(status.powerState) else "LINK LOST",
            if (radarLinkOk) FieldSeverity.OK else FieldSeverity.FAIL,
        )

        return fields
    }

    /** ALRM-02 必显项的完整键集（穷举性断言用）。 */
    val mandatoryKeys: Set<DataKey> get() = DataKey.entries.toSet()

    // ---------- 格式化 ----------

    private fun fmtDeg(deg: Double): String {
        val d = ((deg % 360.0) + 360.0) % 360.0
        return "%03d°".format(d.toInt())
    }

    private fun trim1(v: Double): String = (Math.round(v * 10.0) / 10.0).toString()

    internal fun formatRangeNm(rangeMeters: Int): String {
        val nm = rangeMeters / METERS_PER_NM
        val s = if (nm == floor(nm)) nm.toInt().toString() else trim2(nm)
        return "$s NM"
    }

    private fun trim2(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == floor(r)) r.toInt().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    internal fun formatLatLon(lat: Double, lon: Double): String =
        "${dm(abs(lat))}${if (lat >= 0) "N" else "S"} ${dm(abs(lon))}${if (lon >= 0) "E" else "W"}"

    private fun dm(absDeg: Double): String {
        val d = floor(absDeg).toInt()
        val m = (absDeg - d) * 60.0
        return "%d°%05.2f'".format(d, m)
    }

    private fun orientationLabel(o: PpiOrientation) = when (o) {
        PpiOrientation.HEAD_UP -> "H UP"
        PpiOrientation.NORTH_UP -> "N UP"
        PpiOrientation.COURSE_UP -> "C UP"
    }

    private fun motionLabel(m: MotionMode) = when (m) {
        MotionMode.TRUE_MOTION -> "TM"
        MotionMode.RELATIVE_MOTION -> "RM"
    }

    private fun vectorModeLabel(v: VectorMode) = when (v) {
        VectorMode.TRUE -> "T VECT"
        VectorMode.RELATIVE -> "R VECT"
    }

    private fun stabilisationLabel(s: Stabilisation) = when (s) {
        Stabilisation.SEA -> "SEA"
        Stabilisation.GROUND -> "GND"
    }

    private fun seaLabel(mode: SeaMode, level: Int) = when (mode) {
        SeaMode.MANUAL -> "SEA $level"
        SeaMode.HARBOUR -> "SEA AUTO-HBR"
        SeaMode.OFFSHORE -> "SEA AUTO-OFF"
    }

    private fun powerLabel(p: RadarPowerState) = when (p) {
        RadarPowerState.OFF -> "OFF"
        RadarPowerState.STANDBY -> "STBY"
        RadarPowerState.TRANSMIT -> "TX"
        RadarPowerState.WARMUP -> "WARMUP"
        RadarPowerState.NO_SCANNER -> "NO SCANNER"
        RadarPowerState.DETECTING_SCANNER -> "DETECTING"
    }
}

/**
 * 永久显示项标识 + 其 IEC 62388 条款锚点（合规追溯）。顺序即数据栏默认呈现顺序，按 Annex I 分组排列。
 */
enum class DataKey(val group: DataGroup, val label: String, val clause: String) {
    // Own ship information
    POSITION(DataGroup.OWN_SHIP, "POS", "62388 Annex I; §14.2.2.1(MSC.192/8.2.1); §16.2"),
    HEADING(DataGroup.OWN_SHIP, "HDG", "62388 Annex I; §10.4.4.1(5.20.3); §16.2.2"),
    SPEED(DataGroup.OWN_SHIP, "SPD", "62388 Annex I (own-ship heading/speed)"),

    // Range and mode information
    RANGE_SCALE(DataGroup.RANGE_MODE, "RANGE", "62388 §9.4.1.1 (MSC.192/5.10.2)"),
    ORIENTATION(DataGroup.RANGE_MODE, "ORIENT", "62388 §10.4.4.1 (MSC.192/5.20.3)"),
    MOTION(DataGroup.RANGE_MODE, "MOTION", "62388 §10.4.4.1 (MSC.192/5.20.3); Annex I"),
    STABILISATION(DataGroup.RANGE_MODE, "STAB", "62388 §11.5.5 (MSC.192/5.27.3); Annex I"),

    // Target / vector information
    VECTOR(DataGroup.TARGET, "VECT", "62388 §11.5.5 (MSC.192/5.27.3)"),

    // Radar signal information
    GAIN(DataGroup.RADAR_SIGNAL, "GAIN", "62388 §6.4.2.1 (MSC.192/5.3.2.2)"),
    SEA(DataGroup.RADAR_SIGNAL, "SEA", "62388 §6.4.3.1 (MSC.192/5.3.2.5)"),
    RAIN(DataGroup.RADAR_SIGNAL, "RAIN", "62388 §6.4.4.1"),

    // Radar system information
    MASTER_SLAVE(DataGroup.RADAR_SYSTEM, "SYS", "62388 §15.6.4.1 (MSC.192/5.35.3)"),
    SCANNER(DataGroup.RADAR_SYSTEM, "SCAN", "62388 §16.2.1 (MSC.192/9); §16.1.9");
}

/** Annex I Table I.1 顶层逻辑分组。 */
enum class DataGroup { OWN_SHIP, RANGE_MODE, TARGET, RADAR_SIGNAL, RADAR_SYSTEM }

/**
 * 单项严重度，驱动着色与失效标示：
 *  - [OK] 正常；
 *  - [DEGRADED] 降级/陈旧（如方位稳定模式航向失效已回落、对地稳定缺速度、雷达链路丢失致雷达字段陈旧）；
 *  - [FAIL] 数据无效/丢失，显示占位符（§14.2.2.1 须清晰标示，不得用于计算/不得留空）。
 */
enum class FieldSeverity { OK, DEGRADED, FAIL }

/** 一个已格式化的永久显示项。 */
data class DataField(val key: DataKey, val value: String, val severity: FieldSeverity) {
    val group: DataGroup get() = key.group
    val label: String get() = key.label
    val clause: String get() = key.clause
}

// --- 表示层显示设置类型 ---
// 注：MotionMode / VectorMode / Stabilisation 当前 contract（shared/）尚无，先在表示层定义。
// PpiOrientation 复用 ui-core 已有类型。这几项的归属（是否上提到 contract，或归 T2.6 模式开关 worker）
// 留待 orchestrator 统一——见交付报告「疑问」。

enum class MotionMode { TRUE_MOTION, RELATIVE_MOTION }

enum class VectorMode { TRUE, RELATIVE }

enum class Stabilisation { SEA, GROUND }

/** 数据栏所需的显示模式设置（来自模式开关/控制面板状态）。 */
data class RadarDisplaySettings(
    val orientation: PpiOrientation,
    val motionMode: MotionMode,
    val vectorMode: VectorMode,
    val vectorTimeMin: Int,
    val stabilisation: Stabilisation,
)
