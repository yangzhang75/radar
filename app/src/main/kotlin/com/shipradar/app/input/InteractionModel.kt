package com.shipradar.app.input

/**
 * Immutable snapshot of the operator-interaction state for the PPI: off-centring (pan), the
 * electronic bearing lines (EBL), variable range markers (VRM), parallel index lines (PI), the
 * currently-selected target, and which tool the next drag/keyboard-adjust acts on.
 *
 * This type is **pure Kotlin** — no Android / Compose dependency — so the whole interaction logic
 * ([RadarInteractionController]) is deterministic and unit-testable, and the touch / keyboard /
 * mouse adapters all converge on the same state transitions (the CAT 1 "three input classes,
 * one behaviour" requirement; see [InputClass]).
 *
 * Standards basis (IEC 62388 §9, IMO MSC.192(79)):
 *  - EBL §9.6  (MSC.192/5.15): at least two, true/relative reference, ±0.5° settable.
 *  - VRM §9.5  (MSC.192/5.12): at least two, 0.01 NM resolution, retained on range change.
 *  - PI  §9.9  (MSC.192/5.16): at least four, range constant on scale change, bearing constant
 *               on heading change, on/off individually and as a group.
 *  - Off-centring §10.4 (MSC.192/5.21): manual to at least 50 % of the operational radius.
 */
data class InteractionModel(
    /** Off-centre of the CCRP as a fraction of the operational radius (+x right, +y down). */
    val offCenter: OffCenter = OffCenter.CENTERED,
    /** Electronic bearing lines. Index 0/1 are EBL-1/EBL-2 (§9.6.2.1 requires ≥2). */
    val ebls: List<Ebl> = listOf(Ebl(), Ebl()),
    /** Variable range markers. Index 0/1 are VRM-1/VRM-2 (§9.5.2.1 requires ≥2). */
    val vrms: List<Vrm> = listOf(Vrm(), Vrm()),
    /** Parallel index lines. Indices 0..3 are PI-1..PI-4 (§9.9.2.1 requires ≥4). */
    val piLines: List<PiLine> = List(PI_LINE_COUNT) { PiLine() },
    /** Group on/off for all PI lines (§9.9.2.1: "turn on/off all PI lines as a group"). */
    val piGroupVisible: Boolean = true,
    /** Currently selected target id, or null (§9.7.3.1 cursor select/de-select). */
    val selectedTargetId: String? = null,
    /** Which tool a drag / keyboard adjustment currently acts on. */
    val activeTool: ActiveTool = ActiveTool.None,
    /** Last cursor read-out (range/bearing), for the numerical readout (§9.3.1, §9.7.2). */
    val cursorReadout: CursorReadout? = null,
) {
    companion object {
        /** §9.9.2.1 (MSC.192/5.16.1): a minimum of four independent parallel index lines. */
        const val PI_LINE_COUNT = 4
    }
}

/** Off-centre position of the CCRP, in fractions of the operational radius. */
data class OffCenter(val xFraction: Double, val yFraction: Double) {
    companion object {
        val CENTERED = OffCenter(0.0, 0.0)

        /**
         * §10.4.2.1 (MSC.192/5.21.1): manual off-centring shall locate the antenna position to at
         * least 50 % of the radius. We bound manual pan to this guaranteed minimum extent.
         */
        const val MAX_MANUAL_OFFSET_FRACTION = 0.5
    }
}

/** Bearing reference for an EBL / readout (IEC 62388 §9.6.2.1, MSC.192/5.15.2). */
enum class BearingReference { TRUE, RELATIVE }

/**
 * An electronic bearing line. [bearingDeg] is in the frame given by [reference]; [originAtCcrp]
 * false means the origin has been moved off the CCRP (§9.6.3, EBL origin position).
 */
data class Ebl(
    val enabled: Boolean = false,
    val bearingDeg: Double = 0.0,
    val reference: BearingReference = BearingReference.RELATIVE,
    val originAtCcrp: Boolean = true,
    /** Off-centre origin (fraction of radius) when [originAtCcrp] is false. */
    val origin: OffCenter = OffCenter.CENTERED,
)

/** A variable range marker at [rangeNm] from own ship (IEC 62388 §9.5.2.1). */
data class Vrm(
    val enabled: Boolean = false,
    val rangeNm: Double = 0.0,
)

/**
 * A parallel index line: a straight line at a fixed perpendicular [rangeNm] from own ship, set to
 * a **true** [bearingDeg]. Range stays constant across range-scale changes and bearing stays
 * constant across heading changes (IEC 62388 §9.9.1 / §9.9.2.1).
 */
data class PiLine(
    val enabled: Boolean = false,
    /** True bearing of the line's direction (§9.9.1: "set to a true bearing"). */
    val bearingDeg: Double = 0.0,
    /** Perpendicular distance from own ship, NM (§9.9.1). */
    val rangeNm: Double = 0.0,
    /** §9.9.2.1: "means to truncate ... individual lines". null = full length. */
    val truncateNm: Double? = null,
)

/** Numerical cursor readout (§9.7.2.1 continuous range/bearing readout). */
data class CursorReadout(
    val rangeNm: Double,
    val bearingDeg: Double,
    val reference: BearingReference,
)

/** What the next drag / keyboard adjustment manipulates. */
sealed interface ActiveTool {
    data object None : ActiveTool
    data class Pan(val unused: Boolean = true) : ActiveTool
    data class EblTool(val index: Int) : ActiveTool
    data class VrmTool(val index: Int) : ActiveTool
    data class PiTool(val index: Int) : ActiveTool
}

/**
 * The three operator input classes CAT 1 requires to be equivalent. The adapters
 * ([RadarInputLayer]) translate device events of each class into the SAME
 * [RadarInteractionController] operations, so behaviour is identical regardless of class.
 *
 * Ergonomic basis: IEC 62288 §5.5 (input devices) / §5.7 (general user input guidelines),
 * via IMO MSC/Circ.982. Carried on each [InteractionEvent] purely for traceability/telemetry.
 */
enum class InputClass { TOUCH, KEYBOARD, MOUSE }
