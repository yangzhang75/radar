package com.shipradar.halofeed

import com.shipradar.contract.SampleEncoding
import com.shipradar.util.Angles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SceneTest {

    private val scene = Scene(nOfSamples = 1024)

    @Test fun headingMarkerOnFirstSpoke() {
        val levels = scene.samplesFor(spokeAzimuth = 0, encoding = SampleEncoding.AMPLITUDE)
        // The heading marker brightens the whole radial at azimuth 0.
        assertTrue(levels.all { (it.toInt() and 0xFF) >= 12 })
    }

    @Test fun rangeRingsPresentAwayFromMarker() {
        // A spoke clear of targets/marker still shows ring cells at multiples of 128.
        val az = Angles.degToRawAzimuth(10.0)
        val levels = scene.samplesFor(az, SampleEncoding.AMPLITUDE)
        assertEquals(5, levels[128].toInt())
        assertEquals(5, levels[256].toInt())
        assertEquals(0, levels[129].toInt()) // gap between rings
    }

    @Test fun targetBlockAtFortyFiveDegrees() {
        val az = Angles.degToRawAzimuth(45.0)
        val levels = scene.samplesFor(az, SampleEncoding.AMPLITUDE)
        // Target #0 spans cells 180..230 at full strength.
        assertEquals(15, levels[200].toInt())
        assertEquals(0, levels[300].toInt()) // outside the block
    }

    @Test fun noTargetBetweenBearings() {
        val az = Angles.degToRawAzimuth(200.0) // away from 45/135/270
        val levels = scene.samplesFor(az, SampleEncoding.AMPLITUDE)
        assertTrue(levels.none { it.toInt() == 15 })
    }

    @Test fun dopplerMarksApproachingAndReceding() {
        // Target #0 (idx even) approaching=15; target #1 (idx odd) receding=14.
        val approaching = scene.samplesFor(Angles.degToRawAzimuth(45.0), SampleEncoding.DOPPLER)
        val receding = scene.samplesFor(Angles.degToRawAzimuth(135.0), SampleEncoding.DOPPLER)
        assertEquals(15, approaching[200].toInt())
        assertEquals(14, receding[380].toInt())
    }
}
