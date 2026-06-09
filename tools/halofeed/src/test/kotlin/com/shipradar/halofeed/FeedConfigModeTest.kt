package com.shipradar.halofeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedConfigModeTest {

    @Test fun recordSetsModeAndFile() {
        val c = FeedConfig.parse(arrayOf("--record=feed.bin"))
        assertEquals(FeedMode.RECORD, c.mode)
        assertEquals("feed.bin", c.file)
    }

    @Test fun replaySetsModeAndFile() {
        val c = FeedConfig.parse(arrayOf("--replay=cap.bin", "--no-paced"))
        assertEquals(FeedMode.REPLAY, c.mode)
        assertEquals("cap.bin", c.file)
        assertFalse(c.paced)
    }

    @Test fun channelTogglesParse() {
        val c = FeedConfig.parse(arrayOf("--no-status", "--no-ownship", "--targets=5"))
        assertTrue(c.emitImage)
        assertFalse(c.emitStatus)
        assertTrue(c.emitTarget)
        assertFalse(c.emitOwnship)
        assertEquals(5, c.targetCount)
    }

    @Test fun periodsParse() {
        val c = FeedConfig.parse(arrayOf("--statusPeriod=3.0", "--targetPeriod=0.5", "--ownshipPeriod=2.0"))
        assertEquals(3.0, c.statusPeriodSec)
        assertEquals(0.5, c.targetPeriodSec)
        assertEquals(2.0, c.ownshipPeriodSec)
    }

    @Test fun modeKeywordParses() {
        assertEquals(FeedMode.REPLAY, FeedConfig.parse(arrayOf("--mode=replay", "--file=x")).mode)
    }
}
