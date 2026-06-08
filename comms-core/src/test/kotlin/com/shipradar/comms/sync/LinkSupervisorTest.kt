package com.shipradar.comms.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackoffTest {

    @Test fun exponential_growth_then_cap() {
        val b = Backoff(firstDelayMs = 1_000, multiplier = 2.0, maxDelayMs = 30_000)
        assertEquals(1_000, b.delayForAttempt(0))
        assertEquals(2_000, b.delayForAttempt(1))
        assertEquals(4_000, b.delayForAttempt(2))
        assertEquals(8_000, b.delayForAttempt(3))
        assertEquals(16_000, b.delayForAttempt(4))
        assertEquals(30_000, b.delayForAttempt(5)) // 32_000 capped
        assertEquals(30_000, b.delayForAttempt(100))
    }
}

class LinkSupervisorTest {

    // Large default grace so channels never fed in a test stay INIT and don't pollute assertions;
    // the never-received test sets grace explicitly.
    private fun supervisor(staleness: Long = 5_000, grace: Long = 1_000_000) = LinkSupervisor(
        configs = mapOf(
            DataChannel.ECHO to ChannelConfig(staleness, grace, Backoff(1_000, 2.0, 30_000)),
            DataChannel.STATUS to ChannelConfig(staleness, grace, Backoff(1_000, 2.0, 30_000)),
            DataChannel.TARGET to ChannelConfig(staleness, grace, Backoff(1_000, 2.0, 30_000)),
        ),
        commsAlarmChannels = setOf(DataChannel.ECHO, DataChannel.STATUS),
    )

    @Test fun packet_brings_channel_live() {
        val s = supervisor()
        val a = s.onPacket(DataChannel.ECHO, 1_000)
        assertTrue(a.contains(LinkAction.ChannelUp(DataChannel.ECHO)))
        assertEquals(ChannelLink.LIVE, s.linkOf(DataChannel.ECHO))
    }

    @Test fun staying_within_timeout_stays_live() {
        val s = supervisor()
        s.onPacket(DataChannel.ECHO, 1_000)
        assertTrue(s.onTick(3_000).isEmpty())
        assertEquals(ChannelLink.LIVE, s.linkOf(DataChannel.ECHO))
    }

    @Test fun silence_past_timeout_marks_lost_and_raises_3002() {
        val s = supervisor()
        s.onPacket(DataChannel.ECHO, 1_000)
        val a = s.onTick(6_500) // silent 5_500 > 5_000
        assertEquals(ChannelLink.LOST, s.linkOf(DataChannel.ECHO))
        assertTrue(a.contains(LinkAction.ChannelDown(DataChannel.ECHO)))
        assertTrue(a.any { it is LinkAction.RaiseCommsAlarm && it.channels == setOf(DataChannel.ECHO) })
        assertTrue(s.isCommsAlarmActive())
    }

    @Test fun reconnect_attempts_follow_exponential_backoff() {
        val s = supervisor()
        s.onPacket(DataChannel.ECHO, 1_000)
        s.onTick(6_500) // -> LOST, nextReconnect at 7_500 (first delay 1_000)

        assertTrue(s.onTick(7_000).none { it is LinkAction.Reconnect }) // not due yet
        assertEquals(LinkAction.Reconnect(DataChannel.ECHO, 0), s.onTick(7_500).first { it is LinkAction.Reconnect })
        // next delay 2_000 -> 9_500
        assertTrue(s.onTick(9_000).none { it is LinkAction.Reconnect })
        assertEquals(LinkAction.Reconnect(DataChannel.ECHO, 1), s.onTick(9_500).first { it is LinkAction.Reconnect })
        // next delay 4_000 -> 13_500
        assertEquals(LinkAction.Reconnect(DataChannel.ECHO, 2), s.onTick(13_500).first { it is LinkAction.Reconnect })
    }

    @Test fun packet_after_loss_restores_and_clears_3002() {
        val s = supervisor()
        s.onPacket(DataChannel.ECHO, 1_000)
        s.onTick(6_500) // LOST + alarm
        assertTrue(s.isCommsAlarmActive())
        val a = s.onPacket(DataChannel.ECHO, 10_000)
        assertEquals(ChannelLink.LIVE, s.linkOf(DataChannel.ECHO))
        assertTrue(a.contains(LinkAction.ChannelUp(DataChannel.ECHO)))
        assertTrue(a.any { it is LinkAction.ClearCommsAlarm })
        assertFalse(s.isCommsAlarmActive())
        // backoff reset: after re-loss, first reconnect delay is the initial 1_000 again
        s.onTick(16_000) // silent again -> LOST, next at 17_000
        assertEquals(LinkAction.Reconnect(DataChannel.ECHO, 0), s.onTick(17_000).first { it is LinkAction.Reconnect })
    }

    @Test fun never_received_channel_lost_after_grace() {
        val s = supervisor(grace = 5_000)
        assertTrue(s.onTick(4_000).none { it is LinkAction.ChannelDown }) // still in grace (INIT)
        assertEquals(ChannelLink.INIT, s.linkOf(DataChannel.ECHO))
        val a = s.onTick(5_000)
        assertTrue(a.contains(LinkAction.ChannelDown(DataChannel.ECHO)))
        assertEquals(ChannelLink.LOST, s.linkOf(DataChannel.ECHO))
    }

    @Test fun non_alarm_channel_loss_does_not_raise_3002() {
        val s = supervisor()
        s.onPacket(DataChannel.TARGET, 1_000)
        val a = s.onTick(6_500) // TARGET lost, but TARGET not in commsAlarmChannels
        assertTrue(a.contains(LinkAction.ChannelDown(DataChannel.TARGET)))
        assertFalse(a.any { it is LinkAction.RaiseCommsAlarm })
        assertFalse(s.isCommsAlarmActive())
    }

    @Test fun alarm_clears_only_when_all_alarm_channels_recover() {
        val s = supervisor()
        s.onPacket(DataChannel.ECHO, 1_000)
        s.onPacket(DataChannel.STATUS, 1_000)
        s.onTick(6_500) // both lost -> raise once
        assertTrue(s.isCommsAlarmActive())
        // ECHO recovers, STATUS still lost -> alarm stays
        val a1 = s.onPacket(DataChannel.ECHO, 7_000)
        assertFalse(a1.any { it is LinkAction.ClearCommsAlarm })
        assertTrue(s.isCommsAlarmActive())
        // STATUS recovers -> now clears
        val a2 = s.onPacket(DataChannel.STATUS, 7_100)
        assertTrue(a2.any { it is LinkAction.ClearCommsAlarm })
        assertFalse(s.isCommsAlarmActive())
    }
}
