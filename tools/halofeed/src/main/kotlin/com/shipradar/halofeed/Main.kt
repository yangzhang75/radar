package com.shipradar.halofeed

/**
 * T0.3 (worker task): HALO fake-data generator. Emits valid HALO image (Spoke) packets — and later
 * target/status packets — as multicast UDP to com.shipradar.constants.HaloEndpoints, so comms can be
 * tested offline on the Mac before a real radar is available.
 *
 * This is a STUB so the base build is green. The assigned worker replaces it (see worker prompt T0.3).
 */
fun main() {
    println("[halofeed] stub — to be implemented by T0.3 worker. Will emit HALO Spoke packets to ${
        com.shipradar.constants.HaloEndpoints.IMAGE
    }")
}
