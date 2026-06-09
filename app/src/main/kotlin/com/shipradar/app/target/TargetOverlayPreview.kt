package com.shipradar.app.target

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.shipradar.uicore.color.ColorMapper
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.target.OverlayConfig
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * @Preview renders of [TargetOverlay] over a dark PPI-like background (IEC 62288 §5.4.1.1 dark
 * non-reflecting background) using [FakeTargets]. These let the overlay be reviewed without the
 * comms service or a device.
 */

@Preview(name = "Overlay — mixed scene (day)", widthDp = 360, heightDp = 360)
@Composable
private fun PreviewMixedDay() {
    PpiBackdrop {
        TargetOverlay(
            targets = MutableStateFlow(FakeTargets.mixedScene()),
            ownShip = MutableStateFlow(FakeTargets.ownShip),
            rangeScaleNm = 12.0,
            orientation = PpiOrientation.HEAD_UP,
            config = OverlayConfig(palette = ColorMapper.Palette.DAY, vectorTimeMin = 6.0, trueVectors = true, selectedId = "AIS-455"),
        )
    }
}

@Preview(name = "Overlay — mixed scene (night, relative vectors)", widthDp = 360, heightDp = 360)
@Composable
private fun PreviewMixedNight() {
    PpiBackdrop {
        TargetOverlay(
            targets = MutableStateFlow(FakeTargets.mixedScene()),
            ownShip = MutableStateFlow(FakeTargets.ownShip),
            rangeScaleNm = 12.0,
            orientation = PpiOrientation.NORTH_UP,
            config = OverlayConfig(palette = ColorMapper.Palette.NIGHT, trueVectors = false, selectedId = "TT-7"),
        )
    }
}

@Preview(name = "Overlay — 280-target CAT 1 capacity stress", widthDp = 480, heightDp = 480)
@Composable
private fun PreviewFullCapacity() {
    PpiBackdrop {
        TargetOverlay(
            targets = MutableStateFlow(FakeTargets.fullCapacityScene()),
            ownShip = MutableStateFlow(FakeTargets.ownShip),
            rangeScaleNm = 24.0,
            orientation = PpiOrientation.HEAD_UP,
            config = OverlayConfig(palette = ColorMapper.Palette.DAY),
        )
    }
}

@Composable
private fun PpiBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF03060A))) { content() }
}
