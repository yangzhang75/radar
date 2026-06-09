package com.shipradar.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shipradar.app.alarm.AlarmBar
import com.shipradar.app.alarm.AlarmPresentation
import com.shipradar.app.alarm.NoopAlarmController
import com.shipradar.app.control.ControlPanel
import com.shipradar.app.control.ModeControls
import com.shipradar.app.control.RadarDisplaySettings
import com.shipradar.app.control.MotionMode
import com.shipradar.app.databar.DataBar
import com.shipradar.app.databar.MotionMode as DbMotionMode
import com.shipradar.app.databar.RadarDisplaySettings as DataBarSettings
import com.shipradar.app.databar.Stabilisation as DbStabilisation
import com.shipradar.app.databar.VectorMode as DbVectorMode
import com.shipradar.app.framework.ObTheme
import com.shipradar.app.framework.OpenBridgeTheme
import com.shipradar.app.ppi.FakeSpokes
import com.shipradar.app.ppi.PpiConfig
import com.shipradar.app.ppi.PpiSurface
import com.shipradar.app.target.FakeTargets
import com.shipradar.app.target.TargetOverlay
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import com.shipradar.contract.MasterSlave
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SensorKind
import com.shipradar.contract.TrackCommand
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Top-level HMI assembly — **OWNED BY THE ORCHESTRATOR**. Wires each third-wave worker's self-contained
 * Composable into the [com.shipradar.app.framework.RadarScaffold] slots. Currently driven by per-module
 * preview/fake data; swap [PreviewController] + the fake flows for the comms [RadarCommsService]'s
 * RadarDataBus/RadarController once the service↔UI binding lands.
 *
 * Feature workers must NOT edit this file or [MainActivity].
 */
@Composable
fun RadarScreen() {
    // Hoisted display state so the control/mode panels actually drive the PPI + data bar.
    var display by remember { mutableStateOf(RadarDisplaySettings()) }

    // --- fake data sources (orchestrator-owned until T1.1 service binding) -----------------------
    val spokes = remember { FakeSpokes.continuousSweep() }
    val targets = remember { MutableStateFlow(FakeTargets.mixedScene()) }
    val ownShipFlow = remember { MutableStateFlow(FakeTargets.ownShip) }
    val ownShip = remember {
        OwnShipData(
            latitude = 34.4217, longitude = -119.7017,
            headingDeg = 87.0, headingTrue = true, cogDeg = 90.0, sogKn = 12.4,
            sourceValidity = mapOf(
                SensorKind.HEADING to true, SensorKind.POSITION to true,
                SensorKind.COG_SOG to true, SensorKind.RADAR_LINK to true,
            ),
        )
    }
    val status = remember {
        RadarStatus(
            powerState = RadarPowerState.TRANSMIT,
            rangeMeters = 11112, gainAuto = false, gain = 142,
            seaLevel = 30, rainLevel = 10, masterSlave = MasterSlave.MASTER,
        )
    }
    val alarms = remember {
        AlarmPresentation.uiStateOf(
            listOf(
                AlarmEvent(3048, AlarmPriority.WARNING, AlarmState.ACTIVE_UNACK, "New target in guard zone", "RADAR"),
            ),
        )
    }

    OpenBridgeTheme(ObTheme.DAY) {
        com.shipradar.app.framework.RadarScaffold(
            // TODO(reconcile): control.RadarDisplaySettings and databar.RadarDisplaySettings are
            // duplicate DTOs from two workers — unify into one shared type (top follow-up). Adapter for now:
            top = {
                DataBar(
                    ownShip = ownShip,
                    status = status,
                    settings = DataBarSettings(
                        orientation = display.orientation,
                        motionMode = if (display.motion == MotionMode.TRUE) DbMotionMode.TRUE_MOTION else DbMotionMode.RELATIVE_MOTION,
                        vectorMode = DbVectorMode.RELATIVE,
                        vectorTimeMin = 6,
                        stabilisation = DbStabilisation.GROUND,
                    ),
                )
            },
            // ControlPanel already bundles ModeControls (range/motion/orientation) + its own
            // width(360) + internal vertical scroll, so it goes straight into the side slot.
            side = {
                ControlPanel(
                    status = status,
                    controller = PreviewController,
                    display = display,
                    onDisplayChange = { display = it },
                )
            },
            // modes slot intentionally empty — controls all live in the side panel; nothing floats over the PPI.
            center = { PpiSurface(spokes = spokes, config = PpiConfig(rangeScaleNm = display.rangeScaleNm)) },
            overlay = { TargetOverlay(targets = targets, ownShip = ownShipFlow, rangeScaleNm = display.rangeScaleNm) },
            alarms = { AlarmBar(uiState = alarms, controller = NoopAlarmController) },
            // input slot: T2.5 RadarInputLayer needs measured centre/radius — wired in next pass.
        )
    }
}

/** No-op command sink for the fake-data assembly; replaced by the comms RadarController. */
private object PreviewController : RadarController {
    override fun send(cmd: RadarCommand) {}
    override fun send(cmd: TrackCommand) {}
}
