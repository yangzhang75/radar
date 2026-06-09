package com.shipradar.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import com.shipradar.app.alarm.AlarmBar
import com.shipradar.app.alarm.AlarmPresentation
import com.shipradar.app.alarm.NoopAlarmController
import com.shipradar.app.control.ControlPanel
import com.shipradar.app.control.RadarDisplaySettings
import com.shipradar.app.input.RadarInputLayer
import com.shipradar.app.databar.DataBar
import com.shipradar.app.demo.DemoFeed
import com.shipradar.app.framework.ObTheme
import com.shipradar.app.framework.OpenBridgeTheme
import com.shipradar.app.ppi.PpiConfig
import com.shipradar.app.ppi.PpiSurface
import com.shipradar.app.target.FakeTargets
import com.shipradar.app.target.TargetOverlay
import com.shipradar.comms.service.CommsConfig
import com.shipradar.comms.service.CommsRouter
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import com.shipradar.contract.MasterSlave
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
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

    // --- REAL decode pipeline (on-device, no radar/network) -------------------------------------
    // DemoFeed builds real HALO wire bytes + 61162 sentences and feeds them through the actual
    // CommsRouter (real SpokeParser / Iec61162Parser / 450 transport); the router's bus flows drive
    // the UI. Echoes + own-ship are live via real decode. (Radar targets stay demo objects until the
    // HALO target wire format is captured from a real device — see docs/认证缺口清单.md.)
    val router = remember { CommsRouter(CommsConfig()) }
    LaunchedEffect(router) { DemoFeed.run(router) }
    val ownShipState by router.ownShip.collectAsState() // live, decoded from RMC/HDT
    val targets = remember { MutableStateFlow(FakeTargets.mixedScene()) }
    val targetList by targets.collectAsState()
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
            // W4-A: control + databar now share one canonical RadarDisplaySettings — pass it straight through.
            top = {
                DataBar(
                    ownShip = ownShipState,
                    status = status,
                    settings = display,
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
            center = { PpiSurface(spokes = router.echoSpokes, config = PpiConfig(rangeScaleNm = display.rangeScaleNm)) },
            overlay = { TargetOverlay(targets = targets, ownShip = router.ownShip, rangeScaleNm = display.rangeScaleNm) },
            alarms = { AlarmBar(uiState = alarms, controller = NoopAlarmController) },
            // T2.5 interaction layer over the PPI: measure the operational area so touch/key/mouse
            // hit-testing (select/EBL/VRM) aligns with the rendered echoes/targets.
            input = {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val d = LocalDensity.current
                    val wPx = with(d) { maxWidth.toPx() }
                    val hPx = with(d) { maxHeight.toPx() }
                    RadarInputLayer(
                        center = Offset(wPx / 2f, hPx / 2f),
                        radiusPx = minOf(wPx, hPx) / 2f,
                        orientation = display.orientation,
                        rangeScaleNm = display.rangeScaleNm,
                        targets = targetList,
                        ownHeadingDeg = ownShipState.headingDeg,
                        ownCourseDeg = ownShipState.cogDeg,
                    )
                }
            },
            // input slot: T2.5 RadarInputLayer needs measured centre/radius — wired in next pass.
        )
    }
}

/** No-op command sink for the fake-data assembly; replaced by the comms RadarController. */
private object PreviewController : RadarController {
    override fun send(cmd: RadarCommand) {}
    override fun send(cmd: TrackCommand) {}
}
