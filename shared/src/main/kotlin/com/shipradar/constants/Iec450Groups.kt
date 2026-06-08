package com.shipradar.constants

/**
 * IEC 61162-450 ED3 transport-group multicast endpoints. The software joins the relevant groups
 * to receive sensor data. Source: project spec §3.3 (cross-check against 80-1094-IEC 61162-450 PDF
 * when implementing T1.4). UDP payload <= 1472 B; verify checksum, drop bad frames.
 */
object Iec450Groups {
    val TGTD = Endpoint("239.192.0.2", 60002)   // AIS targets + radar tracked targets
    val SATD = Endpoint("239.192.0.3", 60003)   // high update rate: heading / attitude
    val NAVD = Endpoint("239.192.0.4", 60004)   // other navigation data
    val BAM1 = Endpoint("239.192.0.17", 60017)  // alarm source reporting
    val BAM2 = Endpoint("239.192.0.18", 60018)
    val CAM1 = Endpoint("239.192.0.19", 60019)  // centralized alarm management
    val CAM2 = Endpoint("239.192.0.20", 60020)
}

/** Mandatory radar range scale steps, nautical miles (IEC 62388). All must be provided. */
val MANDATORY_RANGE_SCALES_NM = listOf(0.25, 0.5, 0.75, 1.5, 3.0, 6.0, 12.0, 24.0)
