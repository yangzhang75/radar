package com.shipradar.app.control

import com.shipradar.uicore.ppi.PpiOrientation

/**
 * Display-presentation state owned by the operator via the control panel's mode controls
 * (T2.4, merged into T2.6). Unlike the radar's own settings (carried in `contract.RadarStatus`),
 * these are *presentation* choices that do not go to the antenna — the PPI/target render layers
 * read them to orient and scale the picture.
 *
 * Hoisted by the orchestrator in `RadarScreen` so the same instance drives both this control panel
 * and the PPI/overlay views. Range scale additionally drives a `RadarCommand.SetRange` to the radar
 * (the radar must scan to the selected range), so it lives here *and* is echoed by the radar in
 * `RadarStatus.rangeMeters`.
 *
 * @property rangeScaleNm selected range scale in NM — one of [com.shipradar.constants.MANDATORY_RANGE_SCALES_NM].
 * @property motion true-motion vs relative-motion presentation (IEC 62388 §6 / MSC.192(79)).
 * @property orientation azimuth reference at screen-up (reuses ui-core [PpiOrientation]).
 */
data class RadarDisplaySettings(
    val rangeScaleNm: Double = 6.0,
    val motion: MotionMode = MotionMode.RELATIVE,
    val orientation: PpiOrientation = PpiOrientation.HEAD_UP,
)

/**
 * Picture motion mode.
 *  - [RELATIVE]: own ship fixed on screen, the world moves past it (relative motion, "RM").
 *  - [TRUE]: the sea is stabilised and own ship moves across the picture (true motion, "TM").
 *
 * Defined here (not in shared/ui-core, which T2.6 must not modify) because T2.4 — which owns the
 * motion control — was merged into this task. The PPI render layer needs this type; flagged in the
 * delivery report so the orchestrator can promote it to a shared display-state model if desired.
 * IEC 62388 §6 (true/relative motion) — mandatory CAT 1 capability.
 */
enum class MotionMode { RELATIVE, TRUE }
