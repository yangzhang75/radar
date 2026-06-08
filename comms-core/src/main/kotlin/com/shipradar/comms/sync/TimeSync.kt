package com.shipradar.comms.sync

/**
 * Estimates the offset between a remote source's clock (e.g. UTC stamped into a 61162 sentence, or a
 * HALO sample time) and our local monotonic clock, so samples from different sources can be placed on
 * one timeline despite the 蒲公英 VPN's 1–3 s, jittery latency.
 *
 * Method: the *minimum* observed (localRecv − sourceTs) over a sliding window approximates the true
 * offset, because the minimum-latency packet has the least transport delay added on top of the real
 * clock skew (the classic NTP/Cristian filtering insight). Jitter only ever inflates the difference,
 * never deflates it, so the running minimum is a robust lower-noise estimate.
 *
 * Pure logic: all times are caller-supplied Longs (millis). No real clock.
 *
 * @param windowSize number of recent samples the minimum is taken over; older samples expire so the
 *   estimate tracks slow clock drift instead of being pinned forever by one lucky low-latency packet.
 */
class ClockOffsetEstimator(private val windowSize: Int = 64) {
    init { require(windowSize >= 1) { "windowSize must be >= 1" } }

    private val deltas = ArrayDeque<Long>(windowSize)
    private var hasEstimate = false

    /** Feed one observation: a packet stamped [sourceTs] arrived at local time [localRecv]. */
    fun observe(sourceTs: Long, localRecv: Long) {
        if (deltas.size == windowSize) deltas.removeFirst()
        deltas.addLast(localRecv - sourceTs)
        hasEstimate = true
    }

    fun hasEstimate(): Boolean = hasEstimate

    /** Estimated offset such that localTime ≈ sourceTs + offset. 0 until the first observation. */
    fun offsetMillis(): Long = if (!hasEstimate) 0 else deltas.min()

    /** Project a source timestamp onto the local timeline. */
    fun toLocal(sourceTs: Long): Long = sourceTs + offsetMillis()

    /** Project a local timestamp back onto the source clock. */
    fun toSource(localTs: Long): Long = localTs - offsetMillis()
}

/**
 * Aligns several independently-updating data streams onto a single render instant.
 *
 * Own-ship updates fast, targets slower, status slower still; the UI must render a *consistent*
 * frame — every layer "as of" the same moment — rather than mixing a fresh own-ship position with a
 * stale heading. This holds the latest sample per key (zero-order hold) tagged with the local time it
 * became valid, and produces an aligned snapshot at a requested render tick, marking any key whose
 * sample is older than its freshness budget as stale.
 *
 * High-latency strategy: rendering is done at `tick − displayLagMillis`, a deliberate lag that lets
 * the slower/laggier streams catch up so the frame is internally consistent rather than tearing
 * between a just-arrived fast stream and an in-flight slow one. With `displayLagMillis = 0` it simply
 * holds the latest of each.
 *
 * Pure logic, generic over the key and value types; no clock.
 */
class MultiRateAligner<K>(
    private val freshnessBudgetMillis: Map<K, Long>,
    private val defaultFreshnessMillis: Long = Long.MAX_VALUE,
    private val displayLagMillis: Long = 0,
    /** Per-key samples retained so a *past* render instant can be reconstructed (zero-order hold). */
    private val historyDepth: Int = 16,
) {
    init { require(historyDepth >= 1) { "historyDepth must be >= 1" } }

    private data class Sample(val value: Any?, val validAtLocal: Long)

    /** Newest-last deque of recent samples per key. */
    private val history = HashMap<K, ArrayDeque<Sample>>()

    /**
     * Record that [value] for [key] is valid as of local time [validAtLocal]. Updates strictly older
     * than the newest retained sample are ignored (we never backfill the past), keeping "latest wins".
     */
    fun update(key: K, value: Any?, validAtLocal: Long) {
        val dq = history.getOrPut(key) { ArrayDeque() }
        val newest = dq.lastOrNull()
        if (newest != null && validAtLocal < newest.validAtLocal) return
        dq.addLast(Sample(value, validAtLocal))
        while (dq.size > historyDepth) dq.removeFirst()
    }

    /**
     * Build the aligned frame for render instant [tick] (local timeline). Each key yields the most
     * recent sample valid at-or-before `tick − displayLagMillis` (zero-order hold), its age, and
     * whether it has exceeded its freshness budget. A key with no sample at-or-before that instant
     * (never seen, or its history doesn't reach that far back) is absent from the result map.
     */
    fun snapshotAt(tick: Long): AlignedFrame<K> {
        val alignAt = tick - displayLagMillis
        val out = HashMap<K, Aligned<Any?>>(history.size)
        for ((k, dq) in history) {
            val s = dq.lastOrNull { it.validAtLocal <= alignAt } ?: continue
            val age = alignAt - s.validAtLocal
            val budget = freshnessBudgetMillis[k] ?: defaultFreshnessMillis
            out[k] = Aligned(s.value, age, age > budget)
        }
        return AlignedFrame(alignAt, out)
    }

    @Suppress("UNCHECKED_CAST")
    fun <V> valueAt(key: K, tick: Long): Aligned<V>? = snapshotAt(tick).values[key] as Aligned<V>?
}

/** One key's aligned value with its [ageMillis] at the render instant and whether it is [stale]. */
data class Aligned<V>(val value: V, val ageMillis: Long, val stale: Boolean)

/** All keys aligned to one render instant [alignAtLocal]. */
data class AlignedFrame<K>(val alignAtLocal: Long, val values: Map<K, Aligned<Any?>>) {
    fun anyStale(): Boolean = values.values.any { it.stale }
}
