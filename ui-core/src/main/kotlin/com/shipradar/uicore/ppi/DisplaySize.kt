package com.shipradar.uicore.ppi

/**
 * Physical display-size geometry for the **320 mm equivalent** requirement (DISP-01).
 *
 * IEC 62388 §4.4, Table 1 — Performance requirements for categories of ship/craft for SOLAS V:
 * for **CAT 1** (all ships/craft ≥ 10 000 gross tonnage), the *minimum operational display area
 * diameter* is **320 mm**. This module converts between pixels and millimetres given the panel's
 * physical pixel density, checks compliance, and supports multi-resolution adaptation by computing
 * the pixel radius/diameter needed to realise a ≥ 320 mm equivalent operational area.
 *
 * Pure math; physical panel metrics (DPI / physical size) are supplied by the caller because the
 * target device model is not yet decided — see delivery report (待张建 input: device DPI & physical
 * screen size). All "display area" measurements refer to the circular operational area diameter.
 */
object DisplaySize {

    /** CAT 1 minimum operational display area diameter, mm. IEC 62388 §4.4 Table 1. DISP-01. */
    const val MIN_OPERATIONAL_DISPLAY_DIAMETER_MM = 320.0

    /** Exact mm per inch. */
    const val MM_PER_INCH = 25.4

    /** Pixels → millimetres for a panel of the given pixel density (dpi = pixels per inch). */
    fun pxToMm(px: Double, dpi: Double): Double {
        require(dpi > 0.0) { "dpi must be > 0" }
        return px / dpi * MM_PER_INCH
    }

    /** Millimetres → pixels for a panel of the given pixel density. */
    fun mmToPx(mm: Double, dpi: Double): Double {
        require(dpi > 0.0) { "dpi must be > 0" }
        return mm / MM_PER_INCH * dpi
    }

    /** The physical diameter (mm) of an operational area drawn [diameterPx] pixels wide at [dpi]. */
    fun effectiveDisplayDiameterMm(diameterPx: Double, dpi: Double): Double = pxToMm(diameterPx, dpi)

    /**
     * True if an operational area of [diameterPx] pixels at [dpi] meets the CAT 1 ≥ 320 mm
     * requirement (DISP-01). A tiny tolerance absorbs floating-point rounding only.
     */
    fun meetsMinimumDisplayArea(diameterPx: Double, dpi: Double): Boolean =
        effectiveDisplayDiameterMm(diameterPx, dpi) >= MIN_OPERATIONAL_DISPLAY_DIAMETER_MM - 1e-9

    /**
     * The minimum operational-area **diameter in pixels** that realises the 320 mm equivalent at
     * [dpi]. The render layer must allocate at least this diameter for the PPI circle (DISP-01).
     */
    fun minDiameterPx(dpi: Double): Double = mmToPx(MIN_OPERATIONAL_DISPLAY_DIAMETER_MM, dpi)

    /** The minimum operational-area **radius in pixels** for the 320 mm equivalent at [dpi]. */
    fun minRadiusPx(dpi: Double): Double = minDiameterPx(dpi) / 2.0

    /**
     * Whether a physical panel can host the 320 mm equivalent at all: its smaller physical
     * dimension must be ≥ 320 mm. If false, CAT 1 compliance is physically impossible on this panel
     * regardless of resolution (flag to integrator — wrong panel for CAT 1).
     */
    fun panelCanHostMinimumArea(panelWidthMm: Double, panelHeightMm: Double): Boolean =
        minOf(panelWidthMm, panelHeightMm) >= MIN_OPERATIONAL_DISPLAY_DIAMETER_MM - 1e-9

    /**
     * Largest operational-area diameter (px) that fits a panel of [availableWidthPx] ×
     * [availableHeightPx], i.e. the inscribed circle of the available rectangle. Use this as the
     * candidate PPI diameter, then verify with [meetsMinimumDisplayArea].
     */
    fun fittedDiameterPx(availableWidthPx: Double, availableHeightPx: Double): Double =
        minOf(availableWidthPx, availableHeightPx)
}
