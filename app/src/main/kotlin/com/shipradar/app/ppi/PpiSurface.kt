package com.shipradar.app.ppi

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.shipradar.contract.EchoSpoke
import kotlinx.coroutines.flow.Flow

/**
 * Compose entry point for the PPI render surface. The heavy scan-conversion lives in the custom
 * [PpiView] (a hardware-accelerated [android.view.View]); this just hosts it via [AndroidView] and
 * keeps its [PpiConfig] in sync. The surrounding HMI (controls, alarm bar, menus) is T2.9 / T2.4.
 *
 * @param spokes live echo stream (comms image channel → [EchoSpoke]); null for a static preview.
 * @param config display configuration (range, orientation, palette, heading…).
 */
@Composable
fun PpiSurface(
    spokes: Flow<EchoSpoke>?,
    config: PpiConfig,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            PpiView(ctx).apply {
                setConfig(config)
                spokes?.let { attachSpokes(it) }
            }
        },
        update = { view -> view.setConfig(config) },
    )
}

/**
 * Standalone preview with fake spokes. The IDE preview renders a static frame, so we seed one full
 * revolution synchronously via [PpiView.renderSnapshot]; the running app uses the live sweep
 * ([FakeSpokes.continuousSweep]) in [PpiDemoActivity].
 *
 * Sized to the target marine console: landscape 1920×1200 (px in the preview ≈ dp).
 */
@Preview(name = "PPI 6 NM, head-up (fake spokes)", widthDp = 1920, heightDp = 1200, showBackground = true)
@Composable
private fun PpiSurfacePreview() {
    val snapshot = remember { FakeSpokes.oneRevolution() }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PpiView(ctx).apply {
                setConfig(PpiConfig(rangeScaleNm = 6.0))
                renderSnapshot(snapshot)
            }
        },
    )
}
