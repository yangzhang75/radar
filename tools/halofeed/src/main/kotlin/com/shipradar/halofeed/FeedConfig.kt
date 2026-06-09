package com.shipradar.halofeed

/** What the process does: generate live, generate+record to a file, or replay a recorded file. */
enum class FeedMode { LIVE, RECORD, REPLAY }

/**
 * Generator configuration. All values are overridable from the command line as `--key=value`
 * (and `--doppler` as a bare flag). See [parse] for the accepted keys.
 */
data class FeedConfig(
    /** Antenna rotation speed, RPM. Drives spoke timing: spokes/s = rpm/60 × 4096. */
    val rpm: Double = 24.0,
    /** Samples per spoke (nOfSamples). Typically 1024. */
    val nOfSamples: Int = 1024,
    /** Bits per sample. 4 = 16 colours. */
    val bitsPerSample: Int = 4,
    /** range-cells / 2. Default nOfSamples/2 so range-cells == nOfSamples. */
    val rangeCellsDiv2: Int = 512,
    /** Distance per range cell, mm. Default ≈ 3 NM full range at the defaults above. */
    val rangeCellSizeMm: Int = 5426,
    /** Use Doppler sample encoding (approaching=15 / receding=14) instead of plain amplitude. */
    val doppler: Boolean = false,
    /** Spokes packed into one UDP datagram. 2 → 8 + 2×536 = 1080 B ≤ 1400 (蒲公英 VPN MTU). */
    val spokesPerPacket: Int = 2,
    /** Multicast TTL. 1 = link-local; raise for routed/SD-WAN (蒲公英) segments. */
    val ttl: Int = 1,
    /** Optional outgoing interface name (e.g. "en0"); null = OS default route. */
    val iface: String? = null,
    /** Number of full 4096-spoke scans to emit; 0 = run forever. */
    val scans: Int = 0,
    // --- W5-D: multi-channel + record/replay ---
    /** LIVE (default), RECORD (live + write [file]), or REPLAY ([file]). */
    val mode: FeedMode = FeedMode.LIVE,
    /** Recording file path; required for RECORD/REPLAY. */
    val file: String? = null,
    /** Emit image (Spoke) packets to 236.6.7.8:6678. */
    val emitImage: Boolean = true,
    /** Emit 01C4 mode-status packets to 236.6.7.9:6679. */
    val emitStatus: Boolean = true,
    /** Emit placeholder target packets to 236.6.7.18:6688 (TODO 待协议). */
    val emitTarget: Boolean = true,
    /** Emit own-ship NMEA-0183 to the 61162-450 groups. */
    val emitOwnship: Boolean = true,
    /** Status packet period (s). */
    val statusPeriodSec: Double = 2.0,
    /** Target packet period (s). */
    val targetPeriodSec: Double = 1.0,
    /** Own-ship update period (s). */
    val ownshipPeriodSec: Double = 1.0,
    /** Number of synthetic targets. */
    val targetCount: Int = 3,
    /** REPLAY: reproduce original inter-packet timing (false = as fast as possible). */
    val paced: Boolean = true,
) {
    init {
        require(rpm > 0) { "rpm must be > 0" }
        require(nOfSamples in 1..4095 && nOfSamples % 2 == 0) { "nOfSamples must be even, 1..4095" }
        require(spokesPerPacket >= 1) { "spokesPerPacket must be >= 1" }
        require(packetBytes <= MAX_PACKET_BYTES) {
            "packet of $packetBytes B exceeds MTU budget $MAX_PACKET_BYTES B — lower spokesPerPacket or nOfSamples"
        }
    }

    /** Bytes per spoke = 24-byte header + ceil(nOfSamples/2) of 4-bit data. */
    val spokeBytes: Int get() = SpokeHeader.HEADER_BYTES + (nOfSamples + 1) / 2

    /** Total UDP payload size for one datagram. */
    val packetBytes: Int get() = SpokePacket.FRAME_PREAMBLE.size + spokesPerPacket * spokeBytes

    /** Spokes emitted per second from [rpm]. */
    val spokesPerSecond: Double get() = rpm / 60.0 * 4096.0

    /** Full range covered, metres = rangeCellSize_mm × 2 × rangeCellsDiv2 / 1000. */
    val rangeMetersFull: Double get() = rangeCellSizeMm.toLong() * 2 * rangeCellsDiv2 / 1000.0

    companion object {
        /** ≤1400 keeps us inside the 蒲公英 X5 SD-WAN VPN MTU (see orchestration decision). */
        const val MAX_PACKET_BYTES = 1400

        /**
         * Parse `--key=value` / `--flag` arguments over a base config. Unknown keys throw so typos
         * surface immediately. `--rangeMeters=N` is a convenience that derives rangeCellSizeMm.
         */
        fun parse(args: Array<String>, base: FeedConfig = FeedConfig()): FeedConfig {
            var cfg = base
            for (arg in args) {
                val key: String
                val value: String?
                if (arg.startsWith("--") && arg.contains('=')) {
                    val eq = arg.indexOf('=')
                    key = arg.substring(2, eq)
                    value = arg.substring(eq + 1)
                } else if (arg.startsWith("--")) {
                    key = arg.substring(2)
                    value = null
                } else {
                    throw IllegalArgumentException("unrecognized argument: $arg")
                }
                cfg = when (key) {
                    "rpm" -> cfg.copy(rpm = value!!.toDouble())
                    "samples", "nOfSamples" -> cfg.copy(nOfSamples = value!!.toInt())
                    "rangeCellsDiv2" -> cfg.copy(rangeCellsDiv2 = value!!.toInt())
                    "rangeCellSizeMm" -> cfg.copy(rangeCellSizeMm = value!!.toInt())
                    "rangeMeters" -> cfg.copy(
                        rangeCellSizeMm = (value!!.toDouble() * 1000.0 / (2.0 * cfg.rangeCellsDiv2)).toInt(),
                    )
                    "doppler" -> cfg.copy(doppler = value?.toBooleanStrict() ?: true)
                    "spokesPerPacket" -> cfg.copy(spokesPerPacket = value!!.toInt())
                    "ttl" -> cfg.copy(ttl = value!!.toInt())
                    "iface" -> cfg.copy(iface = value)
                    "scans" -> cfg.copy(scans = value!!.toInt())
                    "mode" -> cfg.copy(mode = FeedMode.valueOf(value!!.uppercase()))
                    "file" -> cfg.copy(file = value)
                    "record" -> cfg.copy(mode = FeedMode.RECORD, file = value ?: cfg.file)
                    "replay" -> cfg.copy(mode = FeedMode.REPLAY, file = value ?: cfg.file)
                    "no-image" -> cfg.copy(emitImage = false)
                    "no-status" -> cfg.copy(emitStatus = false)
                    "no-target" -> cfg.copy(emitTarget = false)
                    "no-ownship" -> cfg.copy(emitOwnship = false)
                    "targets" -> cfg.copy(targetCount = value!!.toInt())
                    "statusPeriod" -> cfg.copy(statusPeriodSec = value!!.toDouble())
                    "targetPeriod" -> cfg.copy(targetPeriodSec = value!!.toDouble())
                    "ownshipPeriod" -> cfg.copy(ownshipPeriodSec = value!!.toDouble())
                    "paced" -> cfg.copy(paced = value?.toBooleanStrict() ?: true)
                    "no-paced" -> cfg.copy(paced = false)
                    "help", "h" -> throw HelpRequested
                    else -> throw IllegalArgumentException("unknown option --$key")
                }
            }
            return cfg
        }

        const val USAGE = """halofeed — HALO fake-data generator (image spokes -> multicast UDP)
Options (--key=value):
  --rpm=24              antenna RPM (spoke timing)
  --samples=1024        samples per spoke (nOfSamples, even)
  --rangeMeters=5556    full range in metres (derives rangeCellSizeMm)
  --rangeCellsDiv2=512  range-cells / 2
  --rangeCellSizeMm=N   distance per cell (mm); overrides --rangeMeters
  --doppler             Doppler encoding (approaching=15/receding=14)
  --spokesPerPacket=2   spokes per UDP datagram (packet must stay <= 1400 B)
  --ttl=1               multicast TTL (raise for routed/SD-WAN segments)
  --iface=en0           outgoing network interface (default: OS route)
  --scans=0             number of full 4096-spoke scans (0 = forever)
Channels (default all on):
  --no-image            do not emit image spokes
  --no-status           do not emit 01C4 status
  --no-target           do not emit placeholder targets (TODO 待协议)
  --no-ownship          do not emit own-ship NMEA-0183
  --targets=3           number of synthetic targets
  --statusPeriod=2.0    status period (s)   --targetPeriod=1.0   --ownshipPeriod=1.0
Record / replay:
  --record=feed.bin     run live AND record every datagram to feed.bin
  --replay=feed.bin     replay a recording (any channel); --no-paced = as fast as possible
  --help"""
    }

    /** Sentinel thrown by [parse] when --help is requested. */
    object HelpRequested : RuntimeException()
}
