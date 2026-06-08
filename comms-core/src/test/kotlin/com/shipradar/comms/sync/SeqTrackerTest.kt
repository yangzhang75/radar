package com.shipradar.comms.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeqTrackerTest {

    @Test fun in_order_stream_has_no_loss() {
        val t = SeqTracker()
        for (s in 0..99) assertEquals(SeqClass.IN_ORDER, t.observe(s))
        val st = t.stats()
        assertEquals(100, st.received)
        assertEquals(0, st.missing)
        assertEquals(0.0, st.lossRate)
    }

    @Test fun detects_gap_and_counts_missing() {
        val t = SeqTracker()
        t.observe(10)
        assertEquals(SeqClass.GAP, t.observe(13)) // 11,12 skipped
        val st = t.stats()
        assertEquals(2, st.missing)
        assertEquals(1, st.gapEvents)
        // expected = delivered(2) + missing(2) = 4
        assertEquals(0.5, st.lossRate)
    }

    @Test fun detects_exact_duplicate() {
        val t = SeqTracker()
        t.observe(5)
        t.observe(6)
        assertEquals(SeqClass.DUPLICATE, t.observe(6))
        assertEquals(SeqClass.DUPLICATE, t.observe(5))
        val st = t.stats()
        assertEquals(2, st.duplicates)
        assertTrue(st.duplicateRate > 0.0)
    }

    @Test fun reordered_arrival_recovers_a_missing_spoke() {
        val t = SeqTracker()
        t.observe(20)
        assertEquals(SeqClass.GAP, t.observe(23))            // 21,22 pending-missing
        assertEquals(SeqClass.REORDERED_RECOVERED, t.observe(21)) // late 21 fills the hole
        val st = t.stats()
        assertEquals(1, st.recovered)
        assertEquals(1, st.missing) // only 22 still missing
    }

    @Test fun reordered_but_not_previously_missing_is_plain_reorder() {
        val t = SeqTracker()
        t.observe(200); t.observe(201); t.observe(202)
        // 199 arrives late: behind the high-water mark, within the reorder window, but was never a
        // skipped (pending-missing) spoke -> a plain reorder, not a recovery.
        assertEquals(SeqClass.REORDERED, t.observe(199))
        assertEquals(1, t.stats().reordered)
        assertEquals(0, t.stats().recovered)
    }

    @Test fun handles_sequence_wraparound() {
        val t = SeqTracker()
        t.observe(4094)
        assertEquals(SeqClass.IN_ORDER, t.observe(4095))
        assertEquals(SeqClass.IN_ORDER, t.observe(0))    // wrap 4095 -> 0
        assertEquals(SeqClass.IN_ORDER, t.observe(1))
        assertEquals(0, t.stats().missing)
    }

    @Test fun gap_across_wraparound_counts_correctly() {
        val t = SeqTracker()
        t.observe(4094)
        assertEquals(SeqClass.GAP, t.observe(1)) // 4095, 0 skipped
        assertEquals(2, t.stats().missing)
    }

    @Test fun huge_jump_is_resync_not_loss() {
        val t = SeqTracker()
        t.observe(0)
        assertEquals(SeqClass.RESYNC, t.observe(2000)) // beyond maxForwardGap(512), not near-wrap
        val st = t.stats()
        assertEquals(1, st.resyncs)
        assertEquals(0, st.missing) // resync must NOT be counted as 2000 lost spokes
    }

    @Test fun mixed_stream_loss_rate_is_plausible() {
        val t = SeqTracker()
        // deliver 0..199 but drop every 10th (10,20,...,190) -> 19 missing of 200 expected
        var prev = -1
        for (s in 0..199) {
            if (s % 10 == 0 && s != 0) continue
            t.observe(s)
            prev = s
        }
        val st = t.stats()
        // 19 dropped (10..190 step 10), none recovered
        assertEquals(19, st.missing)
        assertTrue(st.lossRate in 0.08..0.11, "lossRate=${st.lossRate}")
    }
}
