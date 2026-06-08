package com.shipradar.comms.sync

import com.shipradar.contract.EchoSpoke

/**
 * Organises echo spokes into one revolution's worth of azimuth slots for the PPI renderer.
 *
 * The renderer wants "the latest spoke for each bearing slice". Spokes arrive by sequence number,
 * but the picture is addressed by *azimuth*; this ring keeps the most recent spoke per azimuth slot
 * and reports how complete the current sweep is. Coverage / largest-angular-gap drive the fade and
 * the "thinned picture" degradation cue under a lossy link, complementing [SeqTracker]'s
 * sequence-level loss rate.
 *
 * Pure logic, no clock. A revolution is considered complete when the azimuth crosses the 0° boundary
 * in the forward direction; [offer] then returns a [RevolutionSnapshot] and the ring resets.
 *
 * @param slots number of azimuth buckets around the circle (HALO native is 4096; the renderer often
 *   wants fewer — any divisor works). Each slot spans 360/slots degrees.
 */
class SpokeRing(val slots: Int = 4096) {
    init { require(slots in 2..4096) { "slots out of range" } }

    private val ring = arrayOfNulls<EchoSpoke>(slots)
    private var filled = 0
    private var lastSlot = -1
    private var sawForwardMotion = false

    fun slotOf(azimuthDeg: Double): Int {
        val norm = ((azimuthDeg % 360.0) + 360.0) % 360.0
        // Multiply before dividing: (deg * slots / 360) keeps integer degrees exact, whereas
        // (deg / 360 * slots) can yield e.g. 1.999 for deg=2,slots=360 and break slot monotonicity.
        return (norm * slots / 360.0).toInt().coerceIn(0, slots - 1)
    }

    /**
     * Place a spoke into its azimuth slot. Returns a [RevolutionSnapshot] on the spoke that first
     * crosses back over 0° (sweep wrap), otherwise null. The snapshot reflects the revolution that
     * just *completed*; the crossing spoke seeds the next revolution.
     */
    fun offer(spoke: EchoSpoke): RevolutionSnapshot? {
        val slot = slotOf(spoke.azimuthDeg)
        var completed: RevolutionSnapshot? = null

        // Detect wrap: forward motion that crosses the 0 boundary (slot decreases after we have moved).
        if (lastSlot >= 0 && sawForwardMotion && slot < lastSlot) {
            completed = snapshot()
            clear()
        }

        if (ring[slot] == null) filled++
        ring[slot] = spoke
        if (lastSlot >= 0 && slot > lastSlot) sawForwardMotion = true
        lastSlot = slot
        return completed
    }

    /** Coverage of the current (in-progress) revolution, 0.0..1.0. */
    fun coverage(): Double = filled.toDouble() / slots

    /** Snapshot of the current ring without resetting it (e.g. to render a partial sweep). */
    fun snapshot(): RevolutionSnapshot {
        val largestGap = largestEmptyRun()
        return RevolutionSnapshot(
            slots = slots,
            filledSlots = filled,
            largestGapSlots = largestGap,
            spokes = ring.copyOf(),
        )
    }

    fun clear() {
        ring.fill(null)
        filled = 0
        lastSlot = -1
        sawForwardMotion = false
    }

    /** Longest run of consecutive empty slots, treating the ring as circular. */
    private fun largestEmptyRun(): Int {
        if (filled == 0) return slots
        if (filled == slots) return 0
        // Find any filled slot to anchor the circular scan.
        var anchor = 0
        while (ring[anchor] == null) anchor++
        var best = 0
        var run = 0
        for (i in 1..slots) {
            val idx = (anchor + i) % slots
            if (ring[idx] == null) { run++; if (run > best) best = run } else run = 0
        }
        return best
    }
}

/**
 * The state of one revolution's azimuth ring. [spokes] is indexed by azimuth slot; null entries are
 * bearings with no data this sweep. [largestGapSlots] is the worst contiguous hole (for deciding
 * whether the sweep is too sparse to trust).
 */
data class RevolutionSnapshot(
    val slots: Int,
    val filledSlots: Int,
    val largestGapSlots: Int,
    val spokes: Array<EchoSpoke?>,
) {
    val coverage: Double get() = filledSlots.toDouble() / slots
    val largestGapDeg: Double get() = largestGapSlots.toDouble() / slots * 360.0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RevolutionSnapshot) return false
        return slots == other.slots && filledSlots == other.filledSlots &&
            largestGapSlots == other.largestGapSlots && spokes.contentEquals(other.spokes)
    }

    override fun hashCode(): Int {
        var r = slots
        r = 31 * r + filledSlots
        r = 31 * r + largestGapSlots
        r = 31 * r + spokes.contentHashCode()
        return r
    }
}
