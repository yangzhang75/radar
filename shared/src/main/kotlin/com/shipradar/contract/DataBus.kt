package com.shipradar.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * THE decoupling boundary. comms (Foreground Service) implements both interfaces; the UI only
 * subscribes to RadarDataBus and issues RadarController commands. The UI never touches sockets,
 * serial ports, or protocol bytes.
 */
interface RadarDataBus {
    /** Hot stream of parsed echo spokes for the PPI renderer. */
    val echoSpokes: Flow<EchoSpoke>
    val targets: StateFlow<List<TrackedTarget>>
    val ownShip: StateFlow<OwnShipData>
    val radarStatus: StateFlow<RadarStatus>
    val alarms: Flow<AlarmEvent>
    val linkState: StateFlow<LinkState>
}

interface RadarController {
    fun send(cmd: RadarCommand)
    fun send(cmd: TrackCommand)
}

/** Connection / handshake state (HALO 01B1 request -> 01B2 allow, then watchdog-maintained). */
enum class LinkState { DISCONNECTED, NEGOTIATING, CONNECTED, DEGRADED }
