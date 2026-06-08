package com.shipradar.comms.iec450

import com.shipradar.constants.Endpoint
import com.shipradar.constants.Iec450Groups

/**
 * IEC 61162-450 ED3 transmission groups consumed by this radar HMI — the subset of the Table 4
 * groups (§6.2.2) that the navigation/target/alarm pipelines join.
 *
 * The communication module (Android `:comms` service, out of scope for T1.4) knows which multicast
 * group a UDP datagram arrived on and passes the matching value into [Iec450FrameParser]; the parser
 * itself is socket-free. Endpoints are taken directly from [Iec450Groups] in `:shared` — the address
 * table is **not** duplicated here.
 *
 * Reference: IEC 61162-450 ED3 §6.2.2, Table 4 – Destination multicast addresses and port numbers.
 */
enum class Iec450Group(val endpoint: Endpoint) {
    /** Target data: AIS targets + radar tracked-target messages. §6.2.2 Table 4. */
    TGTD(Iec450Groups.TGTD),

    /** High update rate, e.g. ship heading / attitude data. §6.2.2 Table 4. */
    SATD(Iec450Groups.SATD),

    /** Navigational output other than TGTD/SATD. §6.2.2 Table 4. */
    NAVD(Iec450Groups.NAVD),

    /** BAM-compliant alert source reporting to CAM group 1. §6.2.2 Table 4. */
    BAM1(Iec450Groups.BAM1),

    /** BAM-compliant alert source reporting to CAM group 2. §6.2.2 Table 4. */
    BAM2(Iec450Groups.BAM2),

    /** Centralized alert management of BAM group 1. §6.2.2 Table 4. */
    CAM1(Iec450Groups.CAM1),

    /** Centralized alert management of BAM group 2. §6.2.2 Table 4. */
    CAM2(Iec450Groups.CAM2),
}
