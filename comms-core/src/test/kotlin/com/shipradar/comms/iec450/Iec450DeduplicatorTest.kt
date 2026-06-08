package com.shipradar.comms.iec450

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Retransmission de-duplication keyed on TAG-block `n` (IEC 61162-450 ED3 §7.2.3.6). */
class Iec450DeduplicatorTest {

    private fun sentence(
        group: Iec450Group = Iec450Group.TGTD,
        source: String? = "TT0001",
        seq: Int? = 1,
        raw: String = "\$TTM,1*00",
    ) = TaggedSentence(group, source, seq, raw)

    @Test
    fun `same source and sequence is a duplicate`() {
        val dedup = Iec450Deduplicator()
        assertFalse(dedup.isDuplicate(sentence(seq = 5)))
        assertTrue(dedup.isDuplicate(sentence(seq = 5)), "retransmission of (source,n) must be dropped")
    }

    @Test
    fun `different sequence from same source is not a duplicate`() {
        val dedup = Iec450Deduplicator()
        assertFalse(dedup.isDuplicate(sentence(seq = 5)))
        assertFalse(dedup.isDuplicate(sentence(seq = 6)))
    }

    @Test
    fun `same sequence from a different source is not a duplicate`() {
        val dedup = Iec450Deduplicator()
        assertFalse(dedup.isDuplicate(sentence(source = "TT0001", seq = 5)))
        assertFalse(dedup.isDuplicate(sentence(source = "AI0002", seq = 5)))
    }

    @Test
    fun `same sequence in a different group is not a duplicate`() {
        val dedup = Iec450Deduplicator()
        assertFalse(dedup.isDuplicate(sentence(group = Iec450Group.TGTD, seq = 5)))
        assertFalse(dedup.isDuplicate(sentence(group = Iec450Group.NAVD, seq = 5)))
    }

    @Test
    fun `sentences without a sequence are never de-duplicated`() {
        // No `n` => no transport-layer dedup signal (§7.2.3.6); identical content passes through,
        // since it may be a legitimate periodic update repeating a value.
        val dedup = Iec450Deduplicator()
        assertFalse(dedup.isDuplicate(sentence(seq = null)))
        assertFalse(dedup.isDuplicate(sentence(seq = null)))
    }

    @Test
    fun `sequence reused after the window has evicted it is treated as new (wrap-around)`() {
        val dedup = Iec450Deduplicator(windowPerSource = 4)
        assertFalse(dedup.isDuplicate(sentence(seq = 1)))
        // Push more than `window` distinct sequences so n=1 is evicted.
        for (n in 2..6) assertFalse(dedup.isDuplicate(sentence(seq = n)))
        assertFalse(dedup.isDuplicate(sentence(seq = 1)), "evicted sequence should not count as duplicate")
    }

    @Test
    fun `deduplicate filters a list preserving order`() {
        val dedup = Iec450Deduplicator()
        val a = sentence(seq = 1, raw = "\$A*00")
        val b = sentence(seq = 2, raw = "\$B*00")
        val aRetransmit = sentence(seq = 1, raw = "\$A*00")
        val out = dedup.deduplicate(listOf(a, b, aRetransmit))
        assertEquals(listOf("\$A*00", "\$B*00"), out.map { it.rawSentence })
    }

    @Test
    fun `reset forgets remembered sequences`() {
        val dedup = Iec450Deduplicator()
        assertFalse(dedup.isDuplicate(sentence(seq = 5)))
        dedup.reset()
        assertFalse(dedup.isDuplicate(sentence(seq = 5)), "after reset the sequence is new again")
    }
}
