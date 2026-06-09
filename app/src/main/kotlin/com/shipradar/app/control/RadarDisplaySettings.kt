package com.shipradar.app.control

import com.shipradar.uicore.ppi.PpiOrientation

/**
 * **Canonical** display-presentation state — the single source of truth shared across the HMI
 * (control panel mode controls, PPI/target render, and the permanent-display data bar).
 *
 * Unlike the radar's own settings (carried in `contract.RadarStatus`), these are *presentation*
 * choices that do not go to the antenna — except [rangeScaleNm], which additionally drives a
 * `RadarCommand.SetRange` (the radar must scan to the selected range) and is echoed back in
 * `RadarStatus.rangeMeters`.
 *
 * Hoisted by the orchestrator in `RadarScreen` so the same instance drives every slot. This type
 * replaces the former duplicate `databar.RadarDisplaySettings` (W4-A unification); the data bar
 * now reads this directly.
 *
 * @property rangeScaleNm selected range scale in NM — one of [com.shipradar.constants.MANDATORY_RANGE_SCALES_NM] (IEC 62388 §9.4.1.1).
 * @property orientation azimuth reference at screen-up; reuses ui-core [PpiOrientation] (IEC 62388 §10.4.4 / MSC.192/5.20).
 * @property motion true-motion vs relative-motion presentation (IEC 62388 §10.4.4 / MSC.192/5.20.3 — permanent motion-mode indication).
 * @property vectorMode true vs relative target vectors (IEC 62388 §11.5.5 / MSC.192/5.27.3).
 * @property vectorTimeMin predicted-motion vector time, minutes (IEC 62388 §11.5.5 / MSC.192/5.27.2-3).
 * @property stabilisation sea (water-referenced) vs ground (ground-referenced) velocity reference (IEC 62388 §11.5.5 / MSC.192/5.27.3).
 */
data class RadarDisplaySettings(
    val rangeScaleNm: Double = 6.0,
    val orientation: PpiOrientation = PpiOrientation.HEAD_UP,
    val motion: MotionMode = MotionMode.RELATIVE_MOTION,
    val vectorMode: VectorMode = VectorMode.RELATIVE,
    val vectorTimeMin: Int = 6,
    val stabilisation: Stabilisation = Stabilisation.SEA,
)

/**
 * Picture motion mode (IEC 62388 §10.4.4 / MSC.192/5.20 — true/relative motion, mandatory CAT 1).
 *  - [RELATIVE_MOTION]: own ship fixed on screen, the world moves past it ("RM").
 *  - [TRUE_MOTION]: the sea is stabilised and own ship moves across the picture ("TM").
 *
 * Self-documenting `_MOTION` suffix chosen over bare TRUE/RELATIVE to avoid confusion with the
 * analogous [VectorMode] constants.
 */
enum class MotionMode { RELATIVE_MOTION, TRUE_MOTION }

/**
 * Target predicted-motion vector reference (IEC 62388 §11.5.5 / MSC.192/5.27.3).
 *  - [TRUE]: vector shows the target's true course and speed over the stabilisation reference.
 *  - [RELATIVE]: vector shows motion relative to own ship.
 */
enum class VectorMode { TRUE, RELATIVE }

/**
 * Velocity stabilisation reference (IEC 62388 §11.5.5 / MSC.192/5.27.3; §10.4.x ground/sea modes).
 *  - [SEA]: water-referenced (STW).
 *  - [GROUND]: ground-referenced (SOG/COG); requires valid ground speed.
 */
enum class Stabilisation { SEA, GROUND }
