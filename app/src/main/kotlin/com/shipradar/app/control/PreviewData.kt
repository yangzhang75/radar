package com.shipradar.app.control

import com.shipradar.contract.GuardZoneAlarmType
import com.shipradar.contract.GuardZoneStatus
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.SeaMode
import com.shipradar.contract.TrackCommand

/** No-op controller for @Preview / design-time use (sends nowhere). */
internal object NoOpController : RadarController {
    override fun send(cmd: RadarCommand) {}
    override fun send(cmd: TrackCommand) {}
}

/** Representative radar status for previews (transmitting, manual gain, one active guard zone). */
internal val PreviewStatus = RadarStatus(
    powerState = RadarPowerState.TRANSMIT,
    rangeMeters = 11_112, // 6 NM
    gainAuto = false,
    gain = 180,
    seaMode = SeaMode.HARBOUR,
    seaLevel = 90,
    rainLevel = 40,
    interferenceRejection = 2,
    rpmX10 = 240,
    guardZones = listOf(
        GuardZoneStatus(
            zone = 0, enabled = true, trueBearing = false,
            startRangeMeters = 500, endRangeMeters = 2000,
            bearingDeg = 45.0, widthDeg = 30.0,
            alarmType = GuardZoneAlarmType.ENTERING, triggered = true,
        ),
        GuardZoneStatus(
            zone = 1, enabled = false, trueBearing = true,
            startRangeMeters = 0, endRangeMeters = 0,
            bearingDeg = 0.0, widthDeg = 0.0,
            alarmType = GuardZoneAlarmType.BOTH, triggered = false,
        ),
    ),
)
