package com.shipradar.comms.sync

/**
 * Bounded buffering with an explicit overflow policy, for absorbing bursts on the low-bandwidth
 * (免费版 2 Mbps) link without unbounded memory growth and without dropping safety-critical data.
 *
 * The policy encodes the safety ordering required by the spec: echo spokes are the firehose and the
 * *oldest* may be dropped (a slightly thinned sweep is acceptable); status/own-ship/targets are
 * conflated to the latest value (intermediate states are worthless once superseded); **alarms must
 * never be dropped**. See [ChannelBuffers] for the per-channel assignment.
 *
 * Pure data structure: no clock, no threads, no blocking. The Service polls/drains on its own cadence.
 */
class BoundedBuffer<T>(val capacity: Int, val policy: DropPolicy) {
    init { require(capacity >= 1) { "capacity must be >= 1" } }

    private val q = ArrayDeque<T>(capacity)
    private var dropped = 0L

    val size: Int get() = q.size
    val isEmpty: Boolean get() = q.isEmpty()
    /** Total items discarded by the overflow policy since construction. */
    val droppedCount: Long get() = dropped

    /**
     * Offer an item. Returns the [OfferResult] describing what happened — including any item evicted
     * to make room, so the caller can account for it.
     */
    fun offer(item: T): OfferResult<T> {
        if (policy == DropPolicy.CONFLATE) {
            // Keep only the most recent value.
            val hadOld = q.isNotEmpty()
            val old = if (hadOld) q.last() else null
            if (hadOld) { q.clear(); dropped++ }
            q.addLast(item)
            return if (hadOld) OfferResult.Replaced(old) else OfferResult.Accepted
        }

        if (q.size < capacity) {
            q.addLast(item)
            return OfferResult.Accepted
        }

        // At capacity.
        return when (policy) {
            DropPolicy.DROP_OLDEST -> {
                val evicted = q.removeFirst()
                q.addLast(item)
                dropped++
                OfferResult.DroppedOldest(evicted)
            }
            DropPolicy.DROP_NEWEST -> {
                dropped++
                OfferResult.RejectedNewest(item)
            }
            DropPolicy.NEVER_DROP -> {
                // Safety-critical (alarms): refuse to silently drop. Signal overflow so the Service can
                // escalate (e.g. force-drain) rather than lose the item; we DO accept it (grow past the
                // soft capacity) because losing an alarm is never acceptable.
                q.addLast(item)
                OfferResult.OverflowKept(q.size)
            }
            DropPolicy.CONFLATE -> error("unreachable")
        }
    }

    fun poll(): T? = if (q.isEmpty()) null else q.removeFirst()

    /** Remove and return up to [max] items in FIFO order. */
    fun drain(max: Int = Int.MAX_VALUE): List<T> {
        val out = ArrayList<T>(minOf(max, q.size))
        while (out.size < max && q.isNotEmpty()) out.add(q.removeFirst())
        return out
    }

    fun peek(): T? = q.firstOrNull()
}

/** Overflow behaviour when a [BoundedBuffer] is full. */
enum class DropPolicy {
    /** Discard the oldest queued item to admit the new one (echo spokes). */
    DROP_OLDEST,
    /** Reject the incoming item, keep what is queued. */
    DROP_NEWEST,
    /** Keep only the most recent item — older ones are worthless once superseded (status/nav/targets). */
    CONFLATE,
    /** Never drop; accept past soft-capacity and report overflow (alarms — safety-critical). */
    NEVER_DROP,
}

/** Outcome of [BoundedBuffer.offer]. */
sealed interface OfferResult<out T> {
    /** Queued with room to spare. */
    data object Accepted : OfferResult<Nothing>
    /** Queued after evicting the oldest item ([evicted]). */
    data class DroppedOldest<T>(val evicted: T) : OfferResult<T>
    /** Not queued; the incoming item ([item]) was dropped. */
    data class RejectedNewest<T>(val item: T) : OfferResult<T>
    /** Conflating buffer replaced its previous value ([previous], null if it was empty). */
    data class Replaced<T>(val previous: T?) : OfferResult<T>
    /** NEVER_DROP buffer accepted the item past soft capacity; [size] is the new (over-capacity) size. */
    data class OverflowKept(val size: Int) : OfferResult<Nothing>
}

/**
 * The default per-channel buffer assignment matching the spec's drop ordering. The Service holds one
 * [BoundedBuffer] per channel built from this; capacities are starting points to tune against the
 * 2 Mbps ceiling.
 */
object ChannelBuffers {
    fun default(channel: DataChannel): BoundedBuffer<Any> = when (channel) {
        // ~one revolution of headroom; oldest spokes are expendable under load.
        DataChannel.ECHO -> BoundedBuffer(capacity = 4096, policy = DropPolicy.DROP_OLDEST)
        // Snapshots — only the latest matters.
        DataChannel.TARGET -> BoundedBuffer(capacity = 1, policy = DropPolicy.CONFLATE)
        DataChannel.OWN_SHIP -> BoundedBuffer(capacity = 1, policy = DropPolicy.CONFLATE)
        DataChannel.STATUS -> BoundedBuffer(capacity = 1, policy = DropPolicy.CONFLATE)
        // Safety-critical event log — never lose one.
        DataChannel.ALARM -> BoundedBuffer(capacity = 256, policy = DropPolicy.NEVER_DROP)
    }
}
