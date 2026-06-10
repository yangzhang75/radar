package com.shipradar.app.infopanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.app.control.RadarDisplaySettings
import com.shipradar.app.input.InteractionModel
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SeaMode

/**
 * On-PPI data boxes — standard IMO layout (cf. Furuno FAR-2xx8 §1.4): sensitivity boxes (GAIN/SEA/
 * RAIN) at the top of the operational area, EBL/VRM read-outs at the bottom. Non-interactive overlay
 * text drawn over the PPI; the interaction layer sits above it and still receives all gestures.
 */
@Composable
fun BoxScope.PpiDataBoxes(
    status: RadarStatus,
    display: RadarDisplaySettings,
    model: InteractionModel,
) {
    // Sensitivity boxes (top-left of the operational area).
    Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
        box("GAIN", if (status.gainAuto) "AUTO" else status.gain.toString())
        box("SEA", "${seaLabel(status.seaMode)} ${status.seaLevel}")
        box("RAIN", status.rainLevel.toString())
    }
    // EBL / VRM read-outs (bottom-left), per §1.32/§1.33 — always shown, "---" when off.
    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
        box("EBL1", eblText(model.ebls.getOrNull(0)))
        box("EBL2", eblText(model.ebls.getOrNull(1)))
        box("VRM1", vrmText(model.vrms.getOrNull(0)))
        box("VRM2", vrmText(model.vrms.getOrNull(1)))
    }
    // Range read-out (bottom-right of the operational area).
    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
        box("RANGE", "%.2f NM".format(display.rangeScaleNm))
    }
}

@Composable
private fun box(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.background(Color(0x990B1418)).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, color = Color(0xFF7FA6B3), fontSize = 10.sp)
        Text(
            "  $value",
            color = Color(0xFFE6F2F5),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun seaLabel(m: SeaMode) = when (m) {
    SeaMode.MANUAL -> "MAN"; SeaMode.HARBOUR -> "HBR"; SeaMode.OFFSHORE -> "OFF"
}

private fun eblText(e: com.shipradar.app.input.Ebl?): String =
    if (e?.enabled == true) "%05.1f° %s".format(e.bearingDeg, if (e.reference.name == "TRUE") "T" else "R") else "--- OFF"

private fun vrmText(v: com.shipradar.app.input.Vrm?): String =
    if (v?.enabled == true) "%.2f NM".format(v.rangeNm) else "--- OFF"
