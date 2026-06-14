package com.shipradar.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.shipradar.app.framework.ObTheme
import com.shipradar.uicore.color.ColorMapper

/**
 * W6-B — the three certifiable ambient conditions (IEC 62288 §4.4.1.1 Table 1: day 200 / dusk 10 cd/m²
 * / night darkness). Maps 1:1 onto the framework [ObTheme] and the PPI [ColorMapper.Palette] so chrome,
 * panel and radar video share one colour table per condition.
 */
enum class ThemeMode {
    DAY, DUSK, NIGHT;

    /** Framework chrome theme for this mode (the orchestrator passes this to `OpenBridgeTheme`). */
    fun toObTheme(): ObTheme = when (this) {
        DAY -> ObTheme.DAY
        DUSK -> ObTheme.DUSK
        NIGHT -> ObTheme.NIGHT
    }

    /** PPI echo palette for this mode (keeps the radar video in lockstep with the chrome). */
    fun toEchoPalette(): ColorMapper.Palette = when (this) {
        DAY -> ColorMapper.Palette.DAY
        DUSK -> ColorMapper.Palette.DUSK
        NIGHT -> ColorMapper.Palette.NIGHT
    }
}

/**
 * Hoistable theme + brilliance state for the HMI.
 *
 * Holds the user-selected [mode] (day/dusk/night) and continuous [brilliance] back-light (0..1,
 * §7.2.1). Both are Compose state, so any reader recomposes on change. The orchestrator typically
 * owns one instance near the top of the tree (see "How the orchestrator applies this" below) and
 * passes its fields down to [ThemePanel].
 *
 * ### How the orchestrator applies this (W6-B is HMI-agnostic by contract)
 * This task only produces the palette + control + state; wiring it to the global theme is the
 * orchestrator's job. The intended hook-up:
 * ```
 * val ts = rememberThemeState()                       // hoist once near the root
 * OpenBridgeTheme(theme = ts.mode.toObTheme()) {      // 1) mode → chrome colour table
 *     // 2) brilliance → overall back-light. Two equivalent options:
 *     //    a) wrap the whole HMI in a dimmed surface, or
 *     //    b) pass radarPalette(ts.mode).atBrilliance(ts.brilliance) to drawing code
 *     //       and ts.mode.toEchoPalette() to the PPI renderer.
 *     RadarScaffold(...) { ThemePanel(ts.mode, ts.brilliance, ts::setMode, ts::changeBrilliance) }
 * }
 * ```
 * `mode → OpenBridgeTheme`, `brilliance → global alpha / back-light`. Brilliance is intentionally NOT
 * folded into [ObTheme] (which only encodes the discrete ambient step) — it is the continuous §7.2.1
 * axis applied on top.
 */
@Stable
class ThemeState(
    initialMode: ThemeMode = ThemeMode.NIGHT,
    initialBrilliance: Float = RadarPalette.defaultBrilliance(initialMode),
) {
    var mode: ThemeMode by mutableStateOf(initialMode)

    /** Back-light level, always kept in 0..1. */
    var brilliance: Float by mutableFloatStateOf(initialBrilliance.coerceIn(0f, 1f))
        private set

    /** Set brilliance, clamping to the valid 0..1 range. Pass to `ThemePanel(onBrillianceChange = ...)`. */
    fun changeBrilliance(value: Float) {
        brilliance = value.coerceIn(0f, 1f)
    }

    /**
     * Switch ambient mode. When [resetBrilliance] (default), the back-light is set to the mode's
     * sensible default (low at night, full by day) so dark adaptation is protected on the day→night
     * step; pass `false` to keep the user's current brilliance across the switch.
     */
    fun setMode(value: ThemeMode, resetBrilliance: Boolean = true) {
        mode = value
        if (resetBrilliance) brilliance = RadarPalette.defaultBrilliance(value)
    }

    companion object {
        /** Survives configuration changes (rotation) by saving (mode ordinal, brilliance). */
        val Saver: Saver<ThemeState, List<Any>> = Saver(
            save = { listOf(it.mode.ordinal, it.brilliance) },
            restore = { ThemeState(ThemeMode.entries[it[0] as Int], it[1] as Float) },
        )
    }
}

/** Create/remember a hoistable [ThemeState] that survives recomposition and configuration changes. */
@Composable
fun rememberThemeState(
    initialMode: ThemeMode = ThemeMode.NIGHT,
    initialBrilliance: Float = RadarPalette.defaultBrilliance(initialMode),
): ThemeState = rememberSaveable(saver = ThemeState.Saver) {
    ThemeState(initialMode, initialBrilliance)
}
