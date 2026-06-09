package com.shipradar.halofeed

import com.shipradar.constants.HaloEndpoints
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget

/** A periodic source of datagrams for one channel. [emit] returns null once exhausted. */
interface Emitter {
    val label: String
    val periodNanos: Long
    fun emit(): List<Datagram>?
}

/** Image (Spoke) channel — the primary stream. Bounded by [FeedConfig.scans] (0 = forever). */
class ImageEmitter(private val cfg: FeedConfig, var onScan: (Long) -> Unit = {}) : Emitter {
    private val source = SpokeSource(cfg)
    override val label = "image"
    override val periodNanos = (1_000_000_000.0 * cfg.spokesPerPacket / cfg.spokesPerSecond).toLong().coerceAtLeast(1)
    private var azimuth = 0
    private var seq = 0L
    private var emitted = 0L
    private var scans = 0L
    private val max = if (cfg.scans > 0) cfg.scans.toLong() * 4096 else Long.MAX_VALUE

    override fun emit(): List<Datagram>? {
        if (emitted >= max) return null
        val batch = ArrayList<Spoke>(cfg.spokesPerPacket)
        repeat(cfg.spokesPerPacket) {
            if (emitted >= max) return@repeat
            batch.add(source.spokeAt(azimuth, (seq and 0x0FFF).toInt()))
            emitted++; seq++; azimuth++
            if (azimuth > 4095) { azimuth = 0; scans++; onScan(scans) }
        }
        return if (batch.isEmpty()) null else listOf(Datagram(HaloEndpoints.IMAGE, SpokePacket.build(batch)))
    }
}

/** 01C4 mode-status heartbeat (fixed TRANSMIT for the fake feed). */
class StatusEmitter(cfg: FeedConfig) : Emitter {
    override val label = "status"
    override val periodNanos = (cfg.statusPeriodSec * 1_000_000_000.0).toLong().coerceAtLeast(1)
    override fun emit(): List<Datagram> =
        listOf(Datagram(HaloEndpoints.STATUS, StatusPacket.mode(RadarPowerState.TRANSMIT)))
}

/** Placeholder targets (TODO 待协议) moving deterministically so a replay shows motion. */
class TargetEmitter(private val cfg: FeedConfig) : Emitter {
    override val label = "target"
    override val periodNanos = (cfg.targetPeriodSec * 1_000_000_000.0).toLong().coerceAtLeast(1)
    private var tick = 0

    override fun emit(): List<Datagram> {
        val targets = (0 until cfg.targetCount).map { i ->
            val bearing = ((i * 47.0) + tick * 2.0) % 360.0
            val range = 0.6 + i * 0.8                       // NM, spread out
            TrackedTarget(
                id = "T%02d".format(i + 1),
                source = TargetSource.RADAR_TT,
                rangeNm = range,
                bearingDeg = bearing,
                trueBearing = false,
                courseDeg = (bearing + 180.0) % 360.0,
                speedKn = 6.0 + i * 2.0,
                cpaNm = (i * 0.5),
                tcpaSec = 600.0 - tick * 5.0,
                status = TargetStatus.TRACKED,
                dangerous = i == 0,                         // T01 flagged dangerous for alarm test
            )
        }
        tick++
        return listOf(Datagram(HaloEndpoints.TARGET, TargetPacket.build(targets)))
    }
}

/** Own-ship via NMEA-0183 over 61162-450 groups; advances along its course each tick. */
class OwnShipEmitter(private val cfg: FeedConfig, startSecondsOfDay: Double = 43_200.0) : Emitter {
    override val label = "ownship"
    override val periodNanos = (cfg.ownshipPeriodSec * 1_000_000_000.0).toLong().coerceAtLeast(1)
    private var lat = 36.05          // ~off the coast, arbitrary fixed start
    private var lon = -5.40
    private val headingDeg = 75.0
    private val sogKn = 12.0
    private var t = startSecondsOfDay

    override fun emit(): List<Datagram> {
        val s = OwnShipData(
            latitude = lat, longitude = lon,
            headingDeg = headingDeg, headingTrue = true,
            cogDeg = headingDeg, sogKn = sogKn, rotDegMin = 0.0,
        )
        val dg = Nmea.ownShipDatagrams(s, t)
        // advance position along heading (rough flat-earth step; fine for a test feed)
        val distNm = sogKn * (cfg.ownshipPeriodSec / 3600.0)
        val rad = Math.toRadians(headingDeg)
        lat += (distNm / 60.0) * Math.cos(rad)
        lon += (distNm / 60.0) * Math.sin(rad) / Math.cos(Math.toRadians(lat))
        t += cfg.ownshipPeriodSec
        return dg
    }
}

/** Build the active emitters for [cfg], honouring the channel toggles. First element is the image (primary). */
fun emittersFor(cfg: FeedConfig, onScan: (Long) -> Unit = {}): List<Emitter> = buildList {
    if (cfg.emitImage) add(ImageEmitter(cfg, onScan))
    if (cfg.emitStatus) add(StatusEmitter(cfg))
    if (cfg.emitTarget) add(TargetEmitter(cfg))
    if (cfg.emitOwnship) add(OwnShipEmitter(cfg))
}

/**
 * Interleave [emitters] on an absolute schedule (no drift) and push to [transport]. Stops when all
 * emitters are exhausted, or immediately when [primary] (the image stream, if bounded by scans)
 * finishes. [clockNanos]/[sleeper] are injectable for tests.
 */
fun runLive(
    emitters: List<Emitter>,
    transport: Transport,
    primary: Emitter? = emitters.firstOrNull(),
    clockNanos: () -> Long = System::nanoTime,
    sleeper: (Long) -> Unit = ::sleepNanos,
) {
    if (emitters.isEmpty()) return
    val start = clockNanos()
    val due = LongArray(emitters.size) { 0L }
    val alive = BooleanArray(emitters.size) { true }
    var remaining = emitters.size

    while (remaining > 0) {
        var idx = -1
        for (i in emitters.indices) if (alive[i] && (idx == -1 || due[i] < due[idx])) idx = i
        val wait = (start + due[idx]) - clockNanos()
        if (wait > 0) sleeper(wait)
        val out = emitters[idx].emit()
        if (out == null) {
            alive[idx] = false; remaining--
            if (emitters[idx] === primary) break
            continue
        }
        out.forEach { transport.send(it) }
        due[idx] += emitters[idx].periodNanos
    }
}

internal fun sleepNanos(nanos: Long) {
    if (nanos <= 0) return
    Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
}
