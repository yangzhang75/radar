package com.shipradar.uicore.target

import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.ppi.ScreenPoint

/**
 * Platform-independent **draw model** for the target/track overlay (T2.3r). The pure projector
 * ([OverlayProjector]) turns `(targets, own ship, PPI projection, config)` into a [TargetScene] of plain
 * screen-space primitives (pixels + packed ARGB `Int`s, exactly like [ColorMapper]); the Compose layer in
 * `com.shipradar.app.target` only iterates these and issues Canvas draw calls. No Android/Compose types
 * here, so the whole projection + symbology + colour + LOD logic unit-tests on pure JVM.
 *
 * Symbology follows IEC 62288 Ed.2 Annex A (Table A.2 "Radar and AIS symbols") and IMO MSC.191(79):
 * the *behavioural* rules (shape family, red = danger, AIS orientation = heading, vectors, past
 * positions, lost-target cross) are cited at each use site; the exact glyph geometry (line weights, mm
 * sizes) is graphical in Annex A and not text-extractable — see the delivery-report TODO(待标准).
 */

/** Glyph family for a target symbol (IEC 62288 Annex A Table A.2). */
enum class SymbolShape {
    /** Tracked radar target — a circle (IEC 62288 Annex A; def 3.47 "tracked radar target"). */
    CIRCLE,
    /** AIS target — an (isosceles) triangle oriented to heading/COG (MSC191/6.4.5.5). */
    TRIANGLE,
}

/**
 * One placed target symbol. Booleans are presentation modifiers the Compose layer renders per Annex A:
 * @property orientationScreenDeg Heading/COG as a **screen angle** (deg clockwise from screen-up) for the
 *   triangle's apex; null when course is unknown (MSC191/6.4.5.5: orientation indicates heading).
 * @property dangerous Drives the red colour + flashing (IEC 62288 §5.6.3 / §4.7.2.1; def 3.11).
 * @property acquiring Radar target still in acquisition state (§5.6.3 test c) — broken/segmented glyph.
 * @property lost Last position of a lost target — overdrawn with a cross (§5.6.4; def 3.29).
 * @property selected Operator-selected target — selection marker (def 3.40).
 * @property trialManeuver Part of a trial-manoeuvre simulation (A.823 §3.7) — "T" styling.
 */
data class TargetSymbol(
    val id: String,
    val at: ScreenPoint,
    val shape: SymbolShape,
    val argb: Int,
    val radiusPx: Double,
    val filled: Boolean = false,
    val orientationScreenDeg: Double? = null,
    val dangerous: Boolean = false,
    val acquiring: Boolean = false,
    val lost: Boolean = false,
    val selected: Boolean = false,
    val trialManeuver: Boolean = false,
)

/** A course/speed vector (A.823 §3.4.6). [trueMode] = true vector, else relative vector. */
data class VectorGraphic(
    val id: String,
    val from: ScreenPoint,
    val to: ScreenPoint,
    val argb: Int,
    val trueMode: Boolean,
)

/** Past-position track (IEC 62288 def 3.33 / MSC191/6.4.5.8): time-spaced marks, oldest → newest. */
data class TrailGraphic(
    val id: String,
    val points: List<ScreenPoint>,
    val argb: Int,
)

/** Alphanumeric label anchored near a target (A.823 §3.6.2: range/bearing/CPA/TCPA/course/speed). */
data class TargetLabel(
    val id: String,
    val anchor: ScreenPoint,
    val lines: List<String>,
    val argb: Int,
)

/**
 * The full overlay draw list for one frame, plus diagnostics.
 * @property drawnCount Targets actually placed on screen.
 * @property culledOffArea Targets dropped because they lie outside the operational display circle.
 * @property unplaceable Targets that couldn't be positioned (e.g. true-bearing target with no heading).
 * @property capacity Capacity report (CAP-01) for the incoming set — drives over/near-limit alerts.
 */
data class TargetScene(
    val symbols: List<TargetSymbol>,
    val vectors: List<VectorGraphic>,
    val trails: List<TrailGraphic>,
    val labels: List<TargetLabel>,
    val drawnCount: Int,
    val culledOffArea: Int,
    val unplaceable: Int,
    val capacity: CapacityReport,
)

/**
 * Overlay presentation options. Pure (no Compose). [palette] reuses the echo [ColorMapper.Palette] so the
 * overlay tracks the same day/dusk/night state as the radar video (IEC 62288 §4.5.1).
 *
 * @property vectorTimeMin Vector length in minutes (A.823 §3.4.6.3 time-adjustable; one value for all
 *   targets per MSC191/6.4.5 "vector time consistent for all targets").
 * @property trueVectors true → true vectors, false → relative vectors (A.823 §3.4.6.1 both offered).
 * @property showVectors / showTrails / showLabels feature toggles (A.823 §3.4.7 operator control).
 * @property selectedId The operator-selected target, if any (def 3.40).
 * @property maxTrailPoints Past-position count to project (A.823 §3.3.5: at least four).
 * @property sleepingDetailThreshold When the total target count exceeds this, sleeping-AIS targets are
 *   drawn as bare position glyphs (no vector/trail/label) to keep ≥240 targets fluid. Sleeping AIS carry
 *   no vector/CPA anyway (not activated), so this is lossless detail-reduction, not data loss.
 */
data class OverlayConfig(
    val palette: ColorMapper.Palette = ColorMapper.Palette.DAY,
    val vectorTimeMin: Double = 6.0,
    val trueVectors: Boolean = true,
    val showVectors: Boolean = true,
    val showTrails: Boolean = true,
    val showLabels: Boolean = true,
    val selectedId: String? = null,
    val maxTrailPoints: Int = 4,
    val sleepingDetailThreshold: Int = 80,
)

/**
 * Overlay colours as packed ARGB `Int` (0xAARRGGBB), same convention as [ColorMapper].
 *
 * Standards basis (IEC 62288 Ed.2): **dangerous targets shall be red** and red shall be reserved/
 * distinguishable for danger/alarm use (§4.7.2.1 MSC191/5.5.2; §5.6.3; def 3.11). Alert-coding red is
 * kept fully saturated and **palette-independent** because alert readability must not be reduced at lower
 * display brightness (§4.3.1). The non-danger synthetic-graphics colours are dimmed day→dusk→night to
 * preserve dark adaptation (§7.2.1) and are a basic colour distinct from the echo hues and from red.
 *
 * TODO(待标准): exact chromaticity/ARGB of the non-danger symbol colours is delegated by IEC 62288
 * §4.5.1/§4.6.2 to the **IHO S-52 presentation library** (and Annex A Table A.6 "example colour scheme"),
 * which is not in the project standards library — see [[disp03-iec62288-color-gap]]. Values below are
 * S-52-style placeholders: standard-*compliant* (red=danger, dark bg, day>dusk>night), not standard-*numeric*.
 */
object OverlayColors {

    /** Saturated alert red — dangerous target (IEC 62288 §4.7.2.1 / §5.6.3). Palette-independent. */
    const val DANGER: Int = 0xFFFF0000.toInt()

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** Normal (non-danger) target symbol colour per palette. TODO(待标准: S-52 / Annex A Table A.6). */
    fun target(palette: ColorMapper.Palette): Int = when (palette) {
        ColorMapper.Palette.DAY -> argb(0xFF, 0x30, 0xE0, 0x60)
        ColorMapper.Palette.DUSK -> argb(0xFF, 0x24, 0xA8, 0x48)
        ColorMapper.Palette.NIGHT -> argb(0xFF, 0x14, 0x70, 0x30)
    }

    /** Selection marker / highlight — brighter neutral so it reads against any palette (def 3.40). */
    fun selection(palette: ColorMapper.Palette): Int = when (palette) {
        ColorMapper.Palette.DAY -> argb(0xFF, 0xFF, 0xFF, 0xFF)
        ColorMapper.Palette.DUSK -> argb(0xFF, 0xD0, 0xD0, 0xD0)
        ColorMapper.Palette.NIGHT -> argb(0xFF, 0x90, 0x90, 0x90)
    }

    /** Vector line colour — follows the target colour (dangerous targets get [DANGER]). */
    fun vector(palette: ColorMapper.Palette, dangerous: Boolean): Int =
        if (dangerous) DANGER else target(palette)

    /** Past-position trail colour — a dimmer tone of the target colour (subordinate info). */
    fun trail(palette: ColorMapper.Palette): Int {
        val c = target(palette)
        // halve RGB, keep alpha — a darker tone of the same basic colour (IEC 62288 §5.4.1.1 "tones").
        val a = (c ushr 24) and 0xFF
        val r = ((c ushr 16) and 0xFF) / 2
        val g = ((c ushr 8) and 0xFF) / 2
        val b = (c and 0xFF) / 2
        return argb(a, r, g, b)
    }
}
