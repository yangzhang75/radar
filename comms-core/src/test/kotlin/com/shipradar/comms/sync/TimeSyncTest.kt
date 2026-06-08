package com.shipradar.comms.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockOffsetEstimatorTest {

    @Test fun no_estimate_before_first_sample() {
        val e = ClockOffsetEstimator()
        assertFalse(e.hasEstimate())
        assertEquals(0, e.offsetMillis())
    }

    @Test fun minimum_latency_sample_drives_offset() {
        val e = ClockOffsetEstimator()
        // true offset is 1000ms (local = source + 1000). Transport jitter adds 0..2000 on top.
        // source=0 arrives local=1000 (delay 0); source=10 arrives local=2010 (delay 1000); etc.
        e.observe(sourceTs = 0, localRecv = 1000)   // delta 1000  (lucky low-latency)
        e.observe(sourceTs = 10, localRecv = 2010)  // delta 2000
        e.observe(sourceTs = 20, localRecv = 1700)  // delta 1680
        assertTrue(e.hasEstimate())
        assertEquals(1000, e.offsetMillis()) // min delta == true offset
    }

    @Test fun projects_between_clocks() {
        val e = ClockOffsetEstimator()
        e.observe(sourceTs = 5_000, localRecv = 8_000) // offset 3000
        assertEquals(8_000, e.toLocal(5_000))
        assertEquals(5_000, e.toSource(8_000))
    }

    @Test fun window_expires_old_samples_to_track_drift() {
        val e = ClockOffsetEstimator(windowSize = 2)
        e.observe(0, 100)    // delta 100 (will expire)
        e.observe(10, 1010)  // delta 1000
        e.observe(20, 1520)  // delta 1500 -> window now {1000,1500}, min 1000
        assertEquals(1000, e.offsetMillis())
    }
}

class MultiRateAlignerTest {

    private enum class K { OWN_SHIP, TARGET, STATUS }

    @Test fun holds_latest_value_per_key() {
        val a = MultiRateAligner<K>(freshnessBudgetMillis = emptyMap())
        a.update(K.OWN_SHIP, "pos@100", 100)
        a.update(K.OWN_SHIP, "pos@200", 200)
        val v = a.valueAt<String>(K.OWN_SHIP, 250)!!
        assertEquals("pos@200", v.value)
        assertEquals(50, v.ageMillis)
    }

    @Test fun out_of_order_update_is_ignored() {
        val a = MultiRateAligner<K>(freshnessBudgetMillis = emptyMap())
        a.update(K.OWN_SHIP, "new", 200)
        a.update(K.OWN_SHIP, "stale", 100) // older -> ignored
        assertEquals("new", a.valueAt<String>(K.OWN_SHIP, 300)!!.value)
    }

    @Test fun marks_stale_past_freshness_budget() {
        val a = MultiRateAligner<K>(freshnessBudgetMillis = mapOf(K.STATUS to 2_000))
        a.update(K.STATUS, "s", 1_000)
        assertFalse(a.valueAt<String>(K.STATUS, 2_500)!!.stale) // age 1500 < 2000
        assertTrue(a.valueAt<String>(K.STATUS, 4_000)!!.stale)  // age 3000 > 2000
    }

    @Test fun aligns_multiple_rates_to_one_instant() {
        val a = MultiRateAligner<K>(
            freshnessBudgetMillis = mapOf(K.OWN_SHIP to 500, K.TARGET to 3_000, K.STATUS to 3_000),
        )
        // own-ship fast, target/status slow
        a.update(K.OWN_SHIP, "os", 9_800)
        a.update(K.TARGET, "tg", 8_000)
        a.update(K.STATUS, "st", 7_500)
        val frame = a.snapshotAt(10_000)
        assertEquals(10_000, frame.alignAtLocal)
        assertEquals(3, frame.values.size)
        assertEquals(200, frame.values[K.OWN_SHIP]!!.ageMillis)
        assertFalse(frame.values[K.OWN_SHIP]!!.stale)
        assertFalse(frame.values[K.TARGET]!!.stale)  // age 2000 < 3000
        assertFalse(frame.anyStale())
    }

    @Test fun display_lag_renders_a_consistent_past_instant() {
        val a = MultiRateAligner<K>(freshnessBudgetMillis = emptyMap(), displayLagMillis = 1_000)
        a.update(K.OWN_SHIP, "old", 5_000)
        a.update(K.OWN_SHIP, "fresh", 9_500) // arrives "in the future" relative to lagged instant 9000
        val frame = a.snapshotAt(10_000) // aligns at 9_000
        assertEquals(9_000, frame.alignAtLocal)
        // the 9_500 sample is after 9_000, so the held value is still "old"
        assertEquals("old", frame.values[K.OWN_SHIP]!!.value)
        assertEquals(4_000, frame.values[K.OWN_SHIP]!!.ageMillis)
    }

    @Test fun unseen_key_is_absent() {
        val a = MultiRateAligner<K>(freshnessBudgetMillis = emptyMap())
        assertNull(a.valueAt<String>(K.TARGET, 100))
    }
}
