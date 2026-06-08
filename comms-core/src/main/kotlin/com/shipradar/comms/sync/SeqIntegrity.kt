package com.shipradar.comms.sync

/**
 * Sequence-number integrity tracking for the HALO echo stream.
 *
 * Spokes carry `sequenceNumber` in 0..4095 that increments per spoke and *wraps* back to 0
 * (see [com.shipradar.contract.EchoSpoke.sequenceNumber]). Over the 蒲公英 VPN (1–3 s latency,
 * lossy, low bandwidth) spokes arrive lost / reordered / duplicated (UDP, possibly retransmitted),
 * so we cannot assume monotonic arrival. This classifier consumes the raw sequence stream and
 * reports per-spoke classification plus rolling statistics that drive degradation decisions
 * (e.g. fall back to a coarser sweep, or flag the image channel as unreliable).
 *
 * Pure logic: no clock, no threads. Feed [observe] one sequence number at a time in *arrival* order.
 *
 * @param mod sequence modulus (HALO: 4096).
 * @param maxForwardGap a forward jump larger than this (but smaller than a near-full wrap) is treated
 *   as a [SeqClass.RESYNC] (stream restart / massive burst loss) rather than inflating the loss count.
 * @param reorderWindow how far *behind* the high-water mark a sequence may arrive and still count as a
 *   late/reordered spoke (recovering a previously-missed one) instead of a resync.
 */
class SeqTracker(
    private val mod: Int = 4096,
    private val maxForwardGap: Int = 512,
    private val reorderWindow: Int = 256,
) {
    init {
        require(mod > 1) { "mod must be > 1" }
        require(maxForwardGap in 1 until mod) { "maxForwardGap out of range" }
        require(reorderWindow in 0 until mod) { "reorderWindow out of range" }
        require(maxForwardGap + reorderWindow < mod) {
            "forward and reorder windows must not overlap (ambiguous wrap)"
        }
    }

    private var started = false
    /** Highest sequence advanced to in the forward direction (the "high-water mark"). */
    private var highWater = 0

    /** Sequences we expected (forward gaps) but have not yet seen — may still arrive reordered. */
    private val pendingMissing = LinkedHashSet<Int>()

    /** Recently delivered sequences, for duplicate detection across retransmits. Bounded sliding window. */
    private val seen = LinkedHashSet<Int>()
    private val seenCapacity = (maxForwardGap + reorderWindow).coerceAtLeast(64)

    // ---- rolling counters (since construction or last reset) ----
    private var received = 0L
    private var inOrder = 0L
    private var gapEvents = 0L
    private var missingTotal = 0L      // spokes inferred missing (sum of forward gap-1)
    private var recovered = 0L         // missing spokes later filled by a reordered arrival
    private var duplicates = 0L
    private var reordered = 0L
    private var resyncs = 0L

    /** Forward distance from→to in modular space, i.e. how many steps ahead `to` is (0..mod-1). */
    private fun forwardDist(from: Int, to: Int): Int = ((to - from) % mod + mod) % mod

    private fun remember(seq: Int) {
        if (seen.add(seq) && seen.size > seenCapacity) {
            val it = seen.iterator(); it.next(); it.remove()
        }
    }

    /** Classify one arriving sequence number and update statistics. */
    fun observe(seq: Int): SeqClass {
        require(seq in 0 until mod) { "seq $seq out of 0..${mod - 1}" }
        received++

        if (!started) {
            started = true
            highWater = seq
            remember(seq)
            inOrder++
            return SeqClass.IN_ORDER
        }

        // Exact / recent duplicate (covers retransmits of the current or a recent spoke).
        if (seq in seen) {
            duplicates++
            return SeqClass.DUPLICATE
        }

        val ahead = forwardDist(highWater, seq)            // steps strictly ahead of high-water
        val behind = mod - ahead                            // steps behind high-water

        return when {
            ahead in 1..maxForwardGap -> {
                if (ahead > 1) {
                    // Mark the skipped sequences as pending-missing (they may arrive reordered later).
                    gapEvents++
                    var s = (highWater + 1) % mod
                    while (s != seq) {
                        if (pendingMissing.add(s)) missingTotal++
                        s = (s + 1) % mod
                    }
                }
                highWater = seq
                remember(seq)
                inOrder++
                if (ahead == 1) SeqClass.IN_ORDER else SeqClass.GAP
            }

            behind in 1..reorderWindow -> {
                // Arrived behind the high-water mark: a late/reordered spoke.
                reordered++
                remember(seq)
                if (pendingMissing.remove(seq)) {
                    recovered++
                    SeqClass.REORDERED_RECOVERED
                } else {
                    SeqClass.REORDERED
                }
            }

            else -> {
                // Far beyond either window: stream restart or burst so large it is meaningless as "loss".
                resyncs++
                highWater = seq
                pendingMissing.clear()
                remember(seq)
                SeqClass.RESYNC
            }
        }
    }

    /** Immutable snapshot of the rolling statistics — safe to hand to a degradation/UI decision. */
    fun stats(): SeqStats = SeqStats(
        received = received,
        inOrder = inOrder,
        gapEvents = gapEvents,
        missing = (missingTotal - recovered).coerceAtLeast(0),
        recovered = recovered,
        duplicates = duplicates,
        reordered = reordered,
        resyncs = resyncs,
    )

    /** Reset counters (e.g. per revolution or per reconnect) while preserving the dedup window. */
    fun resetStats() {
        received = 0; inOrder = 0; gapEvents = 0; missingTotal = 0
        recovered = 0; duplicates = 0; reordered = 0; resyncs = 0
        // pendingMissing intentionally retained so in-flight gaps can still be recovered.
    }
}

/** Per-spoke classification produced by [SeqTracker.observe]. */
enum class SeqClass {
    /** Exactly one ahead of the previous high-water mark (the normal case). */
    IN_ORDER,
    /** Jumped forward by >1: one or more spokes were skipped (possibly lost). */
    GAP,
    /** Arrived behind the high-water mark and was not previously expected as missing. */
    REORDERED,
    /** Arrived behind the high-water mark and filled a previously-missing slot — net loss reduced. */
    REORDERED_RECOVERED,
    /** Already delivered recently — a retransmit duplicate; should be ignored downstream. */
    DUPLICATE,
    /** Discontinuity too large for either window — treated as a stream resync, not as loss. */
    RESYNC,
}

/**
 * Rolling integrity statistics. [lossRate] / [duplicateRate] are the headline numbers the
 * degradation logic watches; e.g. sustained loss above a threshold can drop the image channel to
 * DEGRADED and inform the operator that the picture is thinned by the link.
 */
data class SeqStats(
    val received: Long,
    val inOrder: Long,
    val gapEvents: Long,
    /** Net spokes still considered missing (forward gaps minus those recovered by reordered arrivals). */
    val missing: Long,
    val recovered: Long,
    val duplicates: Long,
    val reordered: Long,
    val resyncs: Long,
) {
    /** Total spokes that should have arrived = delivered (received minus dups) + still-missing. */
    val expected: Long get() = (received - duplicates) + missing

    /** Fraction of expected spokes that are missing, 0.0..1.0. */
    val lossRate: Double get() = if (expected <= 0) 0.0 else missing.toDouble() / expected

    /** Fraction of arrivals that were duplicates, 0.0..1.0. */
    val duplicateRate: Double get() = if (received <= 0) 0.0 else duplicates.toDouble() / received
}
