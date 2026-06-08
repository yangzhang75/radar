package com.shipradar.comms.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackpressureTest {

    @Test fun drop_oldest_keeps_newest_under_overflow() {
        val b = BoundedBuffer<Int>(capacity = 3, policy = DropPolicy.DROP_OLDEST)
        assertIs<OfferResult.Accepted>(b.offer(1))
        b.offer(2); b.offer(3)
        val r = b.offer(4) // full -> evict 1
        assertIs<OfferResult.DroppedOldest<Int>>(r)
        assertEquals(1, (r as OfferResult.DroppedOldest).evicted)
        assertEquals(listOf(2, 3, 4), b.drain())
        assertEquals(1, b.droppedCount)
    }

    @Test fun drop_newest_rejects_incoming_under_overflow() {
        val b = BoundedBuffer<Int>(capacity = 2, policy = DropPolicy.DROP_NEWEST)
        b.offer(1); b.offer(2)
        val r = b.offer(3)
        assertIs<OfferResult.RejectedNewest<Int>>(r)
        assertEquals(listOf(1, 2), b.drain())
    }

    @Test fun conflate_keeps_only_latest() {
        val b = BoundedBuffer<String>(capacity = 1, policy = DropPolicy.CONFLATE)
        assertIs<OfferResult.Accepted>(b.offer("v1"))
        val r = b.offer("v2")
        assertIs<OfferResult.Replaced<String>>(r)
        assertEquals("v1", (r as OfferResult.Replaced).previous)
        assertEquals(1, b.size)
        assertEquals("v2", b.peek())
    }

    @Test fun never_drop_accepts_past_capacity_and_signals_overflow() {
        val b = BoundedBuffer<Int>(capacity = 2, policy = DropPolicy.NEVER_DROP)
        b.offer(1); b.offer(2)
        val r = b.offer(3) // past soft capacity
        assertIs<OfferResult.OverflowKept>(r)
        assertEquals(3, (r as OfferResult.OverflowKept).size)
        assertEquals(0, b.droppedCount) // nothing dropped — alarms are never lost
        assertEquals(listOf(1, 2, 3), b.drain())
    }

    @Test fun drain_respects_max_and_fifo_order() {
        val b = BoundedBuffer<Int>(capacity = 10, policy = DropPolicy.DROP_OLDEST)
        for (i in 1..5) b.offer(i)
        assertEquals(listOf(1, 2), b.drain(max = 2))
        assertEquals(listOf(3, 4, 5), b.drain())
        assertTrue(b.isEmpty)
    }

    // --- channel-policy assignment: the safety ordering the spec mandates ---

    @Test fun echo_channel_drops_old_spokes() {
        val buf = ChannelBuffers.default(DataChannel.ECHO)
        assertEquals(DropPolicy.DROP_OLDEST, buf.policy)
    }

    @Test fun status_and_nav_channels_conflate() {
        assertEquals(DropPolicy.CONFLATE, ChannelBuffers.default(DataChannel.STATUS).policy)
        assertEquals(DropPolicy.CONFLATE, ChannelBuffers.default(DataChannel.OWN_SHIP).policy)
        assertEquals(DropPolicy.CONFLATE, ChannelBuffers.default(DataChannel.TARGET).policy)
    }

    @Test fun alarm_channel_never_drops() {
        val buf = ChannelBuffers.default(DataChannel.ALARM)
        assertEquals(DropPolicy.NEVER_DROP, buf.policy)
    }

    @Test fun low_bandwidth_scenario_preserves_alarms_while_thinning_echo() {
        // Simulate a burst that the consumer can't keep up with: echo overflows and sheds old spokes,
        // but the alarm buffer keeps every event.
        val echo = BoundedBuffer<Int>(capacity = 4, policy = DropPolicy.DROP_OLDEST)
        val alarms = BoundedBuffer<Int>(capacity = 2, policy = DropPolicy.NEVER_DROP)
        for (i in 1..100) echo.offer(i)
        for (i in 1..10) alarms.offer(i)
        assertEquals(4, echo.size)                 // bounded
        assertEquals(96, echo.droppedCount)        // old spokes shed
        assertEquals(10, alarms.size)              // every alarm retained
        assertEquals(0, alarms.droppedCount)
    }
}
