package com.shipradar.app.target

import com.shipradar.contract.OwnShipData
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget

/**
 * Preview/demo fake target sets so the overlay develops independently of the T1.1 comms service
 * (the wave-3 contract: workers consume [com.shipradar.contract] + preview data). Not used in production.
 */
object FakeTargets {

    val ownShip = OwnShipData(headingDeg = 0.0, cogDeg = 0.0, sogKn = 12.0, latitude = 0.0, longitude = 0.0)

    /** A small varied scene: dangerous crossing TT, safe overtaken TT, AIS, sleeping AIS, acquiring, lost, selected. */
    fun mixedScene(): List<TrackedTarget> = listOf(
        // Dangerous radar target crossing from starboard (red, flashing).
        TrackedTarget("TT-7", TargetSource.RADAR_TT, rangeNm = 4.0, bearingDeg = 60.0, trueBearing = true,
            courseDeg = 290.0, speedKn = 16.0, cpaNm = 0.3, tcpaSec = 240.0, status = TargetStatus.TRACKED, dangerous = true),
        // Safe radar target being overtaken, dead ahead.
        TrackedTarget("TT-3", TargetSource.RADAR_TT, rangeNm = 6.0, bearingDeg = 5.0, trueBearing = true,
            courseDeg = 0.0, speedKn = 6.0, cpaNm = 1.8, tcpaSec = 900.0, status = TargetStatus.TRACKED),
        // Activated AIS target (filled triangle, oriented to COG) with a vector.
        TrackedTarget("AIS-201", TargetSource.AIS_ACTIVE, rangeNm = 5.0, bearingDeg = 310.0, trueBearing = true,
            courseDeg = 120.0, speedKn = 11.0, cpaNm = 2.5, tcpaSec = 1200.0, status = TargetStatus.TRACKED),
        // Acquiring radar target (broken ring).
        TrackedTarget("TT-9", TargetSource.RADAR_TT, rangeNm = 7.5, bearingDeg = 200.0, trueBearing = true,
            courseDeg = 30.0, speedKn = 9.0, status = TargetStatus.ACQUIRING),
        // Lost target (cross).
        TrackedTarget("TT-2", TargetSource.RADAR_TT, rangeNm = 8.0, bearingDeg = 150.0, trueBearing = true,
            status = TargetStatus.LOST),
        // Selected AIS sleeping target (box + label).
        TrackedTarget("AIS-455", TargetSource.AIS_SLEEPING, rangeNm = 3.0, bearingDeg = 270.0, trueBearing = true,
            courseDeg = 80.0, speedKn = 7.0, cpaNm = 1.2, tcpaSec = 700.0, status = TargetStatus.TRACKED),
        // A few sleeping AIS (bare triangles).
        TrackedTarget("AIS-460", TargetSource.AIS_SLEEPING, rangeNm = 9.0, bearingDeg = 30.0, trueBearing = true,
            courseDeg = 200.0, speedKn = 4.0, status = TargetStatus.TRACKED),
        TrackedTarget("AIS-461", TargetSource.AIS_SLEEPING, rangeNm = 10.0, bearingDeg = 95.0, trueBearing = true,
            courseDeg = 250.0, speedKn = 5.0, status = TargetStatus.TRACKED),
    )

    /** Full CAT 1 capacity stress: 40 radar TT + 40 activated AIS + 200 sleeping AIS = 240 AIS / 280 objects. */
    fun fullCapacityScene(): List<TrackedTarget> {
        val out = ArrayList<TrackedTarget>(280)
        fun spread(source: TargetSource, n: Int, base: String, dangerEvery: Int = 0) {
            for (i in 0 until n) {
                val danger = dangerEvery > 0 && i % dangerEvery == 0
                out += TrackedTarget(
                    id = "$base$i", source = source,
                    rangeNm = 1.0 + (i % 19) * 0.6, bearingDeg = (i * 47 % 360).toDouble(), trueBearing = true,
                    courseDeg = (i * 53 % 360).toDouble(), speedKn = 3.0 + (i % 12),
                    cpaNm = if (danger) 0.4 else 3.0, tcpaSec = if (danger) 200.0 else 1500.0,
                    status = TargetStatus.TRACKED, dangerous = danger,
                )
            }
        }
        spread(TargetSource.RADAR_TT, 40, "TT", dangerEvery = 13)
        spread(TargetSource.AIS_ACTIVE, 40, "AISa", dangerEvery = 0)
        spread(TargetSource.AIS_SLEEPING, 200, "AISs", dangerEvery = 0)
        return out
    }
}
