package com.shipradar.uicore.target

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Edge-detection for new-target (3048) / lost-target (3052) alerts. */
class TargetLifecycleTest {

    @Test
    fun `first update reports all ids as appeared`() {
        val lc = TargetLifecycle()
        val c = lc.update(setOf("T1", "T2"))
        assertEquals(setOf("T1", "T2"), c.appeared)
        assertTrue(c.disappeared.isEmpty())
    }

    @Test
    fun `stable set produces no changes`() {
        val lc = TargetLifecycle()
        lc.update(setOf("T1", "T2"))
        val c = lc.update(setOf("T1", "T2"))
        assertTrue(c.isEmpty, "no churn when the set is unchanged")
    }

    @Test
    fun `appearance and disappearance are detected exactly once`() {
        val lc = TargetLifecycle()
        lc.update(setOf("T1"))
        val c1 = lc.update(setOf("T1", "T2"))      // T2 acquired
        assertEquals(setOf("T2"), c1.appeared)
        assertTrue(c1.disappeared.isEmpty())
        val c2 = lc.update(setOf("T2"))            // T1 lost
        assertEquals(setOf("T1"), c2.disappeared)
        assertTrue(c2.appeared.isEmpty())
        val c3 = lc.update(setOf("T2"))            // steady → nothing
        assertTrue(c3.isEmpty)
    }

    @Test
    fun `simultaneous appear and disappear`() {
        val lc = TargetLifecycle()
        lc.update(setOf("A", "B"))
        val c = lc.update(setOf("B", "C"))
        assertEquals(setOf("C"), c.appeared)
        assertEquals(setOf("A"), c.disappeared)
    }

    @Test
    fun `reset clears history so ids re-appear`() {
        val lc = TargetLifecycle()
        lc.update(setOf("T1"))
        lc.reset()
        val c = lc.update(setOf("T1"))
        assertEquals(setOf("T1"), c.appeared, "after reset, T1 is new again")
    }
}
