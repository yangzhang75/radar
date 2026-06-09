package com.shipradar.app.control

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SeaMode

/**
 * T2.6 — the radar control panel (the `side` slot of `RadarScreen`; merges former T2.4 modes).
 *
 * Entry Composable wired by the orchestrator. Part ① (transmit/standby, gain, sea, rain, interference
 * rejection, antenna speed, guard zones) produces typed [RadarCommand]s sent through [controller]
 * (encoded to HALO bytes by comms-core, the T1.3 work). Part ② (range scale, motion, orientation) is
 * presentation state hoisted via [onDisplayChange]; range additionally drives `SetRange`.
 *
 * The panel only ever *reads* [status] and *sends* commands — it never holds the authoritative radar
 * state (single source of truth = the radar, echoed in `RadarStatus`). Sliders track drag locally and
 * commit on release to avoid flooding the control channel.
 *
 * Naming/abbreviations follow IEC 62288 / IEC 62388 §6 (see [ControlVocabulary]); exact graphical
 * symbol artwork (IMO Res. A.278 / IEC 62288 symbols) is a separate symbol-modelling task.
 *
 * @param status latest radar status snapshot (orchestrator collects `RadarDataBus.radarStatus`).
 * @param controller command sink (`RadarDataBus`/service implements it).
 * @param display presentation state (range/motion/orientation); defaults make previews self-contained.
 * @param onDisplayChange hoist presentation changes up to the shared display state.
 */
@Composable
fun ControlPanel(
    status: RadarStatus,
    controller: RadarController,
    modifier: Modifier = Modifier,
    display: RadarDisplaySettings = RadarDisplaySettings(),
    onDisplayChange: (RadarDisplaySettings) -> Unit = {},
) {
    Column(
        modifier
            .width(360.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        TransmitSection(status, controller)
        GainSection(status, controller)
        SeaSection(status, controller)
        RainSection(status, controller)
        InterferenceRejectionSection(status, controller)
        AntennaSpeedSection(status, controller)
        GuardZoneControls(status, controller)
        ModeControls(display = display, controller = controller, onDisplayChange = onDisplayChange)
    }
}

@Composable
private fun TransmitSection(status: RadarStatus, controller: RadarController) {
    val powered = status.powerState != RadarPowerState.OFF
    val transmitting = status.powerState == RadarPowerState.TRANSMIT
    val trailing = ControlVocabulary.powerStateLabel(status.powerState) +
        (status.warmupRemainSec?.let { " ${it}s" } ?: "")
    ControlSection(ControlVocabulary.POWER, trailing = trailing) {
        SegmentedSelector(
            options = listOf(false to "OFF", true to "ON"),
            selected = powered,
            onSelect = { controller.send(RadarCommand.Power(it)) },
        )
        Column(Modifier.padding(top = 8.dp)) {
            SegmentedSelector(
                options = listOf(false to ControlVocabulary.STANDBY, true to ControlVocabulary.TRANSMIT),
                selected = transmitting,
                enabled = powered && status.powerState != RadarPowerState.WARMUP,
                onSelect = { controller.send(RadarCommand.Transmit(it)) },
            )
        }
    }
}

@Composable
private fun GainSection(status: RadarStatus, controller: RadarController) {
    ControlSection(ControlVocabulary.GAIN, trailing = if (status.gainAuto) ControlVocabulary.AUTO else null) {
        SegmentedSelector(
            options = listOf(false to ControlVocabulary.MANUAL, true to ControlVocabulary.AUTO),
            selected = status.gainAuto,
            onSelect = { controller.send(RadarCommand.Gain(auto = it, level = status.gain)) },
        )
        RadarLevelSlider(
            label = ControlVocabulary.GAIN,
            value = status.gain,
            valueRange = 0..255,
            enabled = !status.gainAuto,
            onCommit = { controller.send(RadarCommand.Gain(auto = false, level = it)) },
        )
    }
}

@Composable
private fun SeaSection(status: RadarStatus, controller: RadarController) {
    ControlSection(ControlVocabulary.SEA, trailing = ControlVocabulary.seaModeLabel(status.seaMode)) {
        SegmentedSelector(
            options = listOf(
                SeaMode.MANUAL to ControlVocabulary.MANUAL,
                SeaMode.HARBOUR to "HARBOUR",
                SeaMode.OFFSHORE to "OFFSHORE",
            ),
            selected = status.seaMode,
            onSelect = { controller.send(RadarCommand.Sea(mode = it, level = status.seaLevel)) },
        )
        RadarLevelSlider(
            label = ControlVocabulary.SEA,
            value = status.seaLevel,
            valueRange = 0..255,
            enabled = status.seaMode == SeaMode.MANUAL, // HARBOUR/OFFSHORE are auto modes
            onCommit = { controller.send(RadarCommand.Sea(mode = SeaMode.MANUAL, level = it)) },
        )
    }
}

@Composable
private fun RainSection(status: RadarStatus, controller: RadarController) {
    ControlSection(ControlVocabulary.RAIN) {
        // Rain clutter is manual only (协议文档 §调整雨雪).
        RadarLevelSlider(
            label = ControlVocabulary.RAIN,
            value = status.rainLevel,
            valueRange = 0..255,
            onCommit = { controller.send(RadarCommand.Rain(level = it)) },
        )
    }
}

@Composable
private fun InterferenceRejectionSection(status: RadarStatus, controller: RadarController) {
    ControlSection(ControlVocabulary.INTERFERENCE_REJECTION) {
        // IEC 62388 / HALO 08C1: 0 = off … 3 = max.
        SegmentedSelector(
            options = listOf(0 to "OFF", 1 to "1", 2 to "2", 3 to "3"),
            selected = status.interferenceRejection.coerceIn(0, 3),
            onSelect = { controller.send(RadarCommand.InterferenceRejection(it)) },
        )
    }
}

@Composable
private fun AntennaSpeedSection(status: RadarStatus, controller: RadarController) {
    val rpm = (status.rpmX10 / 10).coerceIn(10, 36)
    ControlSection(ControlVocabulary.ROTATION, trailing = "$rpm ${ControlVocabulary.RPM}") {
        // HALO 05CB rpm range 10..36; ~24 is normal.
        Stepper(
            label = ControlVocabulary.RPM,
            value = rpm,
            range = 10..36,
            step = 2,
            display = { "$it" },
            onChange = { controller.send(RadarCommand.SetRpm(it)) },
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 1200)
@Composable
private fun ControlPanelPreview() {
    ControlPanel(status = PreviewStatus, controller = NoOpController, display = RadarDisplaySettings(rangeScaleNm = 6.0))
}

@Preview(showBackground = true, widthDp = 380, heightDp = 1200)
@Composable
private fun ControlPanelOffPreview() {
    ControlPanel(
        status = RadarStatus(powerState = RadarPowerState.OFF),
        controller = NoOpController,
    )
}
