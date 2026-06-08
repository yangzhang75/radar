package com.shipradar.halofeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeedConfigTest {

    @Test fun parsesOverrides() {
        val c = FeedConfig.parse(arrayOf("--rpm=48", "--samples=512", "--doppler", "--scans=3"))
        assertEquals(48.0, c.rpm)
        assertEquals(512, c.nOfSamples)
        assertTrue(c.doppler)
        assertEquals(3, c.scans)
    }

    @Test fun rangeMetersDerivesCellSize() {
        // rangeCellSizeMm = meters*1000 / (2*rangeCellsDiv2)
        val c = FeedConfig.parse(arrayOf("--rangeCellsDiv2=512", "--rangeMeters=5556"))
        assertEquals(5425, c.rangeCellSizeMm) // 5556000 / 1024 = 5425.78 -> 5425
        assertEquals(5556.0, c.rangeMetersFull, 2.0)
    }

    @Test fun spokesPerSecondFromRpm() {
        // 24 rpm × 4096 spokes / 60 s = 1638.4 spokes/s
        assertEquals(1638.4, FeedConfig(rpm = 24.0).spokesPerSecond, 0.01)
    }

    @Test fun rejectsPacketOverMtu() {
        // 4 spokes × 536 + 8 = 2152 > 1400
        assertFailsWith<IllegalArgumentException> { FeedConfig(spokesPerPacket = 4) }
    }

    @Test fun rejectsUnknownOption() {
        assertFailsWith<IllegalArgumentException> { FeedConfig.parse(arrayOf("--nope=1")) }
    }

    @Test fun helpThrowsSentinel() {
        assertFailsWith<FeedConfig.HelpRequested> { FeedConfig.parse(arrayOf("--help")) }
    }
}
