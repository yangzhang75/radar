package com.shipradar.app.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shipradar.contract.OwnShipData
import com.shipradar.uicore.ppi.PpiOrientation

/**
 * W7-C — past-tracks control: on/off + selectable plot time (OFF / 1 / 3 / 6 min). State is hoisted
 * ([length] + [onLengthChange]). Per IEC 62388 §11.2.2.1 (MSC.192/5.23.1) it shows the **total plot
 * time and the mode** ([TracksConfig.indication]).
 */
@Composable
fun TracksControlPanel(
    length: TrackLength,
    onLengthChange: (TrackLength) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    Column(modifier.background(cs.surface, shape).padding(8.dp)) {
        Text("PAST TRACKS", color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in TrackLength.entries) {
                val selected = l == length
                Box(
                    Modifier
                        .heightIn(min = 44.dp)
                        .background(if (selected) cs.primary else cs.surfaceVariant, shape)
                        .border(1.dp, cs.outline, shape)
                        .clickable { onLengthChange(l) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(l.label, color = if (selected) cs.onPrimary else cs.onSurface, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Plot: ${TracksConfig(length).indication}", color = cs.onSurfaceVariant, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// Previews — on-device / Android Studio verification.
// ---------------------------------------------------------------------------------------------

/** Sample history: one target whose past positions recede from 4.5→3.0 NM, true bearing 60°→45°. */
private fun demoSnapshot(): Map<String, List<TrackPoint>> {
    val marks = (0..5).map { k ->
        TrackPoint(
            timestampMs = -(5 - k) * 10_000L,
            trueBearingDeg = 60.0 - k * 3.0,
            rangeNm = 4.5 - k * 0.25,
        )
    }
    return mapOf("T1" to marks)
}

@Preview(name = "PastTracksOverlay", widthDp = 360, heightDp = 360)
@Composable
private fun PastTracksOverlayPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        BoxWithConstraints(Modifier.size(360.dp).background(Color(0xFF02060B))) {
            val px = with(LocalDensity.current) { maxWidth.toPx() }
            PastTracksOverlay(
                snapshot = demoSnapshot(),
                ownShip = OwnShipData(headingDeg = 0.0),
                center = Offset(px / 2f, px / 2f),
                radiusPx = px / 2f,
                rangeScaleNm = 6.0,
                orientation = PpiOrientation.NORTH_UP,
                dotRadiusPx = 4f,
            )
        }
    }
}

@Preview(name = "TracksControlPanel", widthDp = 320)
@Composable
private fun TracksControlPanelPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        TracksControlPanel(length = TrackLength.MIN_3, onLengthChange = {})
    }
}
