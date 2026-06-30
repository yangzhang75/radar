package com.shipradar.uicore.target

/**
 * Maps the operator anti-clutter controls (GAIN / SEA / RAIN, IEC 62388 §6 / IMO MSC.192(79) §5.6) onto
 * the plot-extraction detection threshold, so the controls actually shape what the tracker detects:
 *  - **SEA clutter ↑** → raise the CFAR factor (suppress spiky near-range sea returns).
 *  - **RAIN ↑**        → raise the absolute amplitude floor (rain is a broad, low-level return).
 *  - **GAIN ↑**        → lower the CFAR factor (more sensitive; more/weaker echoes pass).
 *
 * Pure and monotonic (each control moves the threshold one way), unit-testable. The control scale is taken
 * as 0..[maxLevel] (default 100). The exact response curve is **not standard-fixed** — IEC mandates the
 * controls exist and are continuous, not their numeric mapping — so these coefficients are tunable.
 * `TODO(待标准/厂商): 确认控制档位量程与响应曲线 (HALO gain/sea/rain 标度)`.
 */
object ClutterControl {

    fun extractionConfig(
        base: PlotExtractionConfig,
        gain: Int,
        seaLevel: Int,
        rainLevel: Int,
        maxLevel: Int = 100,
    ): PlotExtractionConfig {
        require(maxLevel > 0) { "maxLevel must be > 0" }
        fun frac(x: Int) = x.coerceIn(0, maxLevel).toDouble() / maxLevel
        val seaF = frac(seaLevel)
        val rainF = frac(rainLevel)
        val gainF = frac(gain)
        // sea raises the threshold, gain lowers it; floor at 0.5 so detection never collapses.
        val factor = (base.cfarFactor * (1.0 + 0.8 * seaF) * (1.0 - 0.3 * gainF)).coerceAtLeast(0.5)
        // rain raises the absolute amplitude floor (0..15), up to +4 codes.
        val minAmp = (base.minAmplitude + (4.0 * rainF).toInt()).coerceIn(0, 15)
        return base.copy(cfarFactor = factor, minAmplitude = minAmp)
    }
}
