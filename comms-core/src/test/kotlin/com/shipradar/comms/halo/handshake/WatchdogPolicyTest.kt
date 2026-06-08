package com.shipradar.comms.halo.handshake

import com.shipradar.constants.HALO_WATCHDOG_PERIOD_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchdogPolicyTest {

    private val wd = WatchdogPolicy() // 8s / 30s / 60s defaults

    @Test
    fun `default period is the shared constant`() {
        assertEquals(HALO_WATCHDOG_PERIOD_MS, wd.periodMs)
        assertEquals(8_000L, wd.periodMs)
    }

    @Test
    fun `next due is one period after last send`() {
        assertEquals(18_000L, wd.nextDueAt(10_000L))
    }

    @Test
    fun `not due before the period elapses`() {
        assertFalse(wd.isDue(lastSentAt = 0L, now = 7_999L))
        assertEquals(1L, wd.millisUntilDue(lastSentAt = 0L, now = 7_999L))
    }

    @Test
    fun `due exactly at and after the period`() {
        assertTrue(wd.isDue(lastSentAt = 0L, now = 8_000L))
        assertTrue(wd.isDue(lastSentAt = 0L, now = 12_000L))
        assertEquals(0L, wd.millisUntilDue(lastSentAt = 0L, now = 8_000L))
        assertEquals(0L, wd.millisUntilDue(lastSentAt = 0L, now = 20_000L))
    }

    @Test
    fun `health ok below standby threshold`() {
        assertEquals(WatchdogHealth.OK, wd.health(lastSentAt = 0L, now = 0L))
        assertEquals(WatchdogHealth.OK, wd.health(lastSentAt = 0L, now = 29_999L))
    }

    @Test
    fun `health standby at and beyond 30s`() {
        assertEquals(WatchdogHealth.STANDBY, wd.health(lastSentAt = 0L, now = 30_000L))
        assertEquals(WatchdogHealth.STANDBY, wd.health(lastSentAt = 0L, now = 59_999L))
    }

    @Test
    fun `health power off at and beyond 60s`() {
        assertEquals(WatchdogHealth.POWER_OFF, wd.health(lastSentAt = 0L, now = 60_000L))
        assertEquals(WatchdogHealth.POWER_OFF, wd.health(lastSentAt = 0L, now = 120_000L))
    }

    @Test
    fun `event mapping drives the state machine`() {
        assertNull(wd.eventFor(WatchdogHealth.OK))
        assertEquals(LinkEvent.Degraded, wd.eventFor(WatchdogHealth.STANDBY))
        assertEquals(LinkEvent.Lost, wd.eventFor(WatchdogHealth.POWER_OFF))
    }

    @Test
    fun `simulated tick loop sends on cadence`() {
        // Drive a virtual clock; count sends, updating lastSentAt only when due.
        var lastSentAt = 0L
        var sends = 0
        var now = 0L
        while (now <= 40_000L) {
            if (wd.isDue(lastSentAt, now)) {
                sends++
                lastSentAt = now
            }
            now += 1_000L
        }
        // lastSentAt seeded at t=0, so re-sends fall due at 8,16,24,32,40s -> 5 sends.
        assertEquals(5, sends)
    }

    @Test
    fun `custom thresholds honored`() {
        val fast = WatchdogPolicy(periodMs = 3_000L, standbyAfterMs = 10_000L, powerOffAfterMs = 20_000L)
        assertEquals(3_000L, fast.nextDueAt(0L))
        assertEquals(WatchdogHealth.STANDBY, fast.health(0L, 10_000L))
        assertEquals(WatchdogHealth.POWER_OFF, fast.health(0L, 20_000L))
    }

    @Test
    fun `invalid thresholds rejected`() {
        assertFailsWith<IllegalArgumentException> { WatchdogPolicy(periodMs = 0L) }
        // standby must be > period and < powerOff
        assertFailsWith<IllegalArgumentException> {
            WatchdogPolicy(periodMs = 8_000L, standbyAfterMs = 8_000L, powerOffAfterMs = 60_000L)
        }
        assertFailsWith<IllegalArgumentException> {
            WatchdogPolicy(periodMs = 8_000L, standbyAfterMs = 70_000L, powerOffAfterMs = 60_000L)
        }
    }
}
