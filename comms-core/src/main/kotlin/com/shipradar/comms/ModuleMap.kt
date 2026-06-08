package com.shipradar.comms

/**
 * comms-core: pure-JVM protocol logic (no Android dependency, fully unit-testable on the dev Mac).
 * Worker landing zones (one package per task — DO NOT cross into another worker's package):
 *
 *   com.shipradar.comms.halo.image    T1.2  Spoke parser -> EchoSpoke
 *   com.shipradar.comms.halo.control  T1.3a control-command encoder (RadarCommand/TrackCommand -> bytes)
 *   com.shipradar.comms.halo.status   T1.3b status-message parser -> RadarStatus
 *   com.shipradar.comms.halo.handshake T1.1a handshake (01B1/01B2) + watchdog scheduling logic
 *   com.shipradar.comms.iec450        T1.4  61162-450 frame parsing
 *   com.shipradar.comms.iec61162      T1.5  61162-1/2 sentence parsing (HDT/GGA/RMC/VTG/VDM/TTM/ALF...)
 *   com.shipradar.comms.sync          T1.6  multiplex/buffer/reconnect/time-sync logic
 *
 * The Android Foreground Service (:comms module) wires these to real sockets/serial + lifecycle.
 */
internal const val MODULE = "comms-core"
