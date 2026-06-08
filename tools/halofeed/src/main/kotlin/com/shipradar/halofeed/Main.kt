package com.shipradar.halofeed

import com.shipradar.constants.HaloEndpoints
import com.shipradar.util.Angles
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * T0.3: HALO fake-data generator. Emits valid HALO image (Spoke) packets as multicast UDP to
 * [HaloEndpoints.IMAGE] (236.6.7.8:6678) so the comms stack can be tested offline before a real
 * radar is available. See README.md for usage and the protocol notes / open questions.
 */
fun main(args: Array<String>) {
    val cfg = try {
        FeedConfig.parse(args)
    } catch (e: FeedConfig.HelpRequested) {
        println(FeedConfig.USAGE)
        return
    } catch (e: IllegalArgumentException) {
        System.err.println("[halofeed] ${e.message}\n")
        System.err.println(FeedConfig.USAGE)
        return
    }

    val ep = HaloEndpoints.IMAGE
    val group = InetAddress.getByName(ep.address)
    val socket = MulticastSocket()
    socket.timeToLive = cfg.ttl
    cfg.iface?.let { name ->
        val nif = NetworkInterface.getByName(name)
            ?: error("network interface '$name' not found")
        socket.networkInterface = nif
    }
    Runtime.getRuntime().addShutdownHook(Thread { socket.close() })

    println(
        "[halofeed] -> ${ep.address}:${ep.port}  rpm=${cfg.rpm} " +
            "samples=${cfg.nOfSamples} range=${"%.0f".format(cfg.rangeMetersFull)}m " +
            "(${"%.2f".format(cfg.rangeMetersFull / 1852.0)} NM) " +
            "encoding=${if (cfg.doppler) "DOPPLER" else "AMPLITUDE"} " +
            "spokes/pkt=${cfg.spokesPerPacket} pkt=${cfg.packetBytes}B ttl=${cfg.ttl} " +
            "iface=${cfg.iface ?: "default"}",
    )

    val source = SpokeSource(cfg)
    val dest = InetSocketAddress(group, ep.port)
    val nanosPerSpoke = (1_000_000_000.0 / cfg.spokesPerSecond)
    val startNanos = System.nanoTime()

    var spokeCounter = 0L           // total spokes emitted (drives sequenceNumber + pacing)
    var azimuth = 0                 // raw azimuth 0..4095, wraps each scan
    var scan = 0L
    val maxSpokes = if (cfg.scans > 0) cfg.scans.toLong() * 4096 else Long.MAX_VALUE

    val batch = ArrayList<Spoke>(cfg.spokesPerPacket)
    while (spokeCounter < maxSpokes) {
        batch.clear()
        repeat(cfg.spokesPerPacket) {
            if (spokeCounter >= maxSpokes) return@repeat
            batch.add(source.spokeAt(azimuth, (spokeCounter and 0x0FFF).toInt()))
            spokeCounter++
            azimuth++
            if (azimuth > 4095) {
                azimuth = 0
                scan++
                println("[halofeed] scan #$scan complete ($spokeCounter spokes sent)")
            }
        }
        if (batch.isEmpty()) break

        val payload = SpokePacket.build(batch)
        socket.send(DatagramPacket(payload, payload.size, dest))

        // Pace to the requested RPM using an absolute schedule so jitter doesn't accumulate.
        val targetNanos = startNanos + (spokeCounter * nanosPerSpoke).toLong()
        val sleepNanos = targetNanos - System.nanoTime()
        if (sleepNanos > 0) {
            Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
        }
    }

    val elapsed = (System.nanoTime() - startNanos) / 1e9
    println(
        "[halofeed] done: $spokeCounter spokes over ${"%.1f".format(elapsed)}s " +
            "(last bearing ${"%.1f".format(Angles.rawAzimuthToDeg(azimuth))}°)",
    )
    socket.close()
}
