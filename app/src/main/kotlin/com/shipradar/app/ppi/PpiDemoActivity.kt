package com.shipradar.app.ppi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.ppi.PpiOrientation

/**
 * Demo host: shows the PPI render surface driven by [FakeSpokes.continuousSweep] so the surface can
 * be run and screenshotted standalone (the task's "预览用假辐条"). The production HMI host is T2.9.
 *
 * Landscape lock + fullscreen come from the manifest (marine console, 1920×1200).
 */
class PpiDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val spokes = remember { FakeSpokes.continuousSweep(rpm = 24.0) }
            PpiSurface(
                spokes = spokes,
                config = PpiConfig(
                    rangeScaleNm = 6.0,
                    orientation = PpiOrientation.HEAD_UP,
                    palette = ColorMapper.Palette.DAY,
                    headingDeg = 0.0,
                    antennaRpm = 24.0,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
