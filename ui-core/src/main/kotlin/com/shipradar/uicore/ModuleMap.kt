package com.shipradar.uicore

/**
 * ui-core: pure-JVM display logic (no Android/Compose dependency, fully unit-testable).
 * The Android :app module renders these via Canvas/OpenGL ES + Compose; this module holds the math.
 * Worker landing zones (one package per task):
 *
 *   com.shipradar.uicore.ppi      T2.1a polar->screen transforms, range-ring/bearing-scale geometry
 *   com.shipradar.uicore.color    T2.2  ColorMapper: (sample, encoding) -> ARGB (Doppler 15=approach,14=recede)
 *   com.shipradar.uicore.target   T2.3a CPA/TCPA computation, radar+AIS fusion, vector/trail geometry
 *
 * Each function is pure: inputs from com.shipradar.contract, outputs plain values. No platform calls.
 */
internal const val MODULE = "ui-core"
