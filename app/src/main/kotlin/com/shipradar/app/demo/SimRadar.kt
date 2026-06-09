package com.shipradar.app.demo

import com.shipradar.contract.MasterSlave
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.TrackCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * On-device demo stand-in for the radar's control+status loop. A real radar echoes every control
 * command back as a status message (HALO 02C4/01C4); with no radar attached, this updates a local
 * [RadarStatus] so the control panel is actually responsive (power/transmit/gain/sea/rain/rpm visibly
 * change). Drop-in [RadarController]; replaced by the comms [RadarController] once a real radar/feed
 * is connected.
 */
class SimRadar : RadarController {
    private val _status = MutableStateFlow(
        RadarStatus(
            powerState = RadarPowerState.TRANSMIT,
            rangeMeters = 11112, gainAuto = false, gain = 142,
            seaLevel = 30, rainLevel = 10, rpmX10 = 240, masterSlave = MasterSlave.MASTER,
        ),
    )
    val status: StateFlow<RadarStatus> get() = _status.asStateFlow()

    override fun send(cmd: RadarCommand) {
        _status.update { s ->
            when (cmd) {
                is RadarCommand.Power ->
                    s.copy(powerState = if (cmd.on) maxOf2(s.powerState, RadarPowerState.STANDBY) else RadarPowerState.OFF)
                is RadarCommand.Transmit ->
                    s.copy(powerState = if (cmd.on) RadarPowerState.TRANSMIT else RadarPowerState.STANDBY)
                is RadarCommand.SetRange -> s.copy(rangeMeters = cmd.meters)
                is RadarCommand.Gain -> s.copy(gainAuto = cmd.auto, gain = cmd.level)
                is RadarCommand.Sea -> s.copy(seaMode = cmd.mode, seaLevel = cmd.level)
                is RadarCommand.Rain -> s.copy(rainLevel = cmd.level)
                is RadarCommand.Ftc -> s.copy(ftcLevel = cmd.level)
                is RadarCommand.InterferenceRejection -> s.copy(interferenceRejection = cmd.level)
                is RadarCommand.SetRpm -> s.copy(rpmX10 = cmd.rpm * 10)
                is RadarCommand.TargetExpansion -> s.copy(targetExpansion = cmd.on)
                is RadarCommand.TargetBoost -> s.copy(targetBoost = cmd.level)
                else -> s
            }
        }
    }

    override fun send(cmd: TrackCommand) {}

    /** OFF < STANDBY < everything else — turning power on from OFF goes to STANDBY, else keeps state. */
    private fun maxOf2(a: RadarPowerState, b: RadarPowerState): RadarPowerState =
        if (a == RadarPowerState.OFF) b else a
}
