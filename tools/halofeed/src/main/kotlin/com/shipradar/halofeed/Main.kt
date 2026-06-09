package com.shipradar.halofeed

/**
 * T0.3 / W5-D: HALO fake-data generator. Emits valid HALO image (Spoke) packets plus 01C4 status,
 * placeholder targets and own-ship NMEA as multicast UDP, so the comms stack can be tested offline
 * before a real radar is available. Supports record (capture a session) and replay (re-send a
 * recording) for repeatable tests. See README.md for usage, reception, and protocol open questions.
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

    when (cfg.mode) {
        FeedMode.REPLAY -> runReplayMode(cfg)
        FeedMode.RECORD, FeedMode.LIVE -> runLiveMode(cfg)
    }
}

private fun runLiveMode(cfg: FeedConfig) {
    val net = MulticastTransport(ttl = cfg.ttl, iface = cfg.iface)
    val transport: Transport = if (cfg.mode == FeedMode.RECORD) {
        val path = cfg.file ?: run { System.err.println("[halofeed] --record requires a file path"); return }
        println("[halofeed] RECORD -> $path")
        RecordingTransport(net, RecordWriter.toFile(path))
    } else {
        net
    }
    Runtime.getRuntime().addShutdownHook(Thread { transport.close() })

    val channels = listOfNotNull(
        if (cfg.emitImage) "image" else null,
        if (cfg.emitStatus) "status" else null,
        if (cfg.emitTarget) "target(占位)" else null,
        if (cfg.emitOwnship) "ownship(NMEA)" else null,
    ).joinToString(",")
    println(
        "[halofeed] LIVE channels=[$channels] rpm=${cfg.rpm} samples=${cfg.nOfSamples} " +
            "range=${"%.0f".format(cfg.rangeMetersFull)}m (${"%.2f".format(cfg.rangeMetersFull / 1852.0)} NM) " +
            "encoding=${if (cfg.doppler) "DOPPLER" else "AMPLITUDE"} imgPkt=${cfg.packetBytes}B " +
            "ttl=${cfg.ttl} iface=${cfg.iface ?: "default"} scans=${if (cfg.scans == 0) "∞" else cfg.scans}",
    )

    val start = System.nanoTime()
    val emitters = emittersFor(cfg) { scan -> println("[halofeed] scan #$scan complete") }
    try {
        runLive(emitters, transport, primary = emitters.firstOrNull { it.label == "image" })
    } finally {
        transport.close()
    }
    println("[halofeed] done in ${"%.1f".format((System.nanoTime() - start) / 1e9)}s")
}

private fun runReplayMode(cfg: FeedConfig) {
    val path = cfg.file ?: run { System.err.println("[halofeed] --replay requires a file path"); return }
    val transport = MulticastTransport(ttl = cfg.ttl, iface = cfg.iface)
    Runtime.getRuntime().addShutdownHook(Thread { transport.close() })
    println("[halofeed] REPLAY <- $path (paced=${cfg.paced})")
    val n = RecordReader.fromFile(path).use { replay(it, transport, paced = cfg.paced) }
    transport.close()
    println("[halofeed] replayed $n datagram(s)")
}
