package com.shipradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * App entry point (scaffold). Landscape, 1920x1200 target.
 *
 * Third-wave workers replace [RadarApp] with the real HMI:
 *  - PPI render surface (Canvas/OpenGL ES) consuming ui-core geometry + colour (T2.1/T2.2),
 *  - control panel / data bar / alarm UI / OpenBridge framework (T2.4–T2.9),
 *  - bind to the comms Foreground Service (RadarDataBus / RadarController).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RadarApp() }
    }
}

@Composable
private fun RadarApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ship Radar — scaffold (PPI/HMI: third wave)")
            }
        }
    }
}
