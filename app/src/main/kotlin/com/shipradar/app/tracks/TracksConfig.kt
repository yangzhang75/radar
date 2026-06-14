package com.shipradar.app.tracks

/**
 * W7-C — past-tracks (past-position) configuration.
 *
 * Standards basis: **IEC 62388 §11.2 "Target trails and past positions"** (the task's "§11.5/5.27" is
 * imprecise — the governing clause is §11.2, MSC.192/5.23). §11.2.2.1 (MSC.192/5.23.1): *variable
 * length (time)* past positions with an **indication of total plot time and mode**, selectable on/off.
 * The defined term *past positions* = "equally time-spaced past position marks of a tracked or
 * reported target" → equal-interval sampling (see [TracksConfig.sampleIntervalMs]).
 */
enum class TrackLength(val totalMillis: Long, val label: String) {
    OFF(0L, "OFF"),
    MIN_1(60_000L, "1 min"),
    MIN_3(180_000L, "3 min"),
    MIN_6(360_000L, "6 min");

    val enabled: Boolean get() = this != OFF
}

/**
 * Past-tracks settings. [marks] is the number of equally time-spaced past-position marks plotted over
 * the selected [length]; the sampling interval is derived so the marks are evenly spaced.
 *
 * @param length total plot time (OFF/1/3/6 min).
 * @param marks number of equally spaced past-position dots over [length] (default [DEFAULT_MARKS]).
 */
data class TracksConfig(
    val length: TrackLength = TrackLength.OFF,
    val marks: Int = DEFAULT_MARKS,
) {
    val enabled: Boolean get() = length.enabled && marks > 0

    /** Total plot time in ms (the age beyond which marks are dropped). */
    val totalMillis: Long get() = length.totalMillis

    /** Equal sampling interval (ms) = total / marks; 0 when disabled. e.g. 1 min/6 = 10 s. */
    val sampleIntervalMs: Long get() = if (enabled) totalMillis / marks else 0L

    /** Ring-buffer capacity per target: [marks] + 1 (margin for the newest in-progress sample). */
    val capacityPerTarget: Int get() = if (enabled) marks + 1 else 0

    /** Human-readable "total plot time + mode" indication required by §11.2.2.1. */
    val indication: String get() = if (enabled) "${length.label} · Relative" else "OFF"

    companion object {
        const val DEFAULT_MARKS: Int = 6
    }
}
