package com.shipradar.app.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.uicore.ppi.PpiOrientation
import com.shipradar.uicore.ppi.RangeModel

/**
 * Range / motion / orientation controls (part ② of T2.6, formerly T2.4).
 *
 * - Range: steps through the mandatory IEC 62388 §9.4.1.1 scales (0.25 … 24 NM) via
 *   [RangeModel.nextRangeScale]/[RangeModel.previousRangeScale]; the selected scale is permanently
 *   displayed (always-on, IEC 62388 permanent-indication requirement). Changing scale both updates
 *   the presentation state and sends `RadarCommand.SetRange` so the antenna scans to that range.
 * - Motion: relative (RM) vs true (TM) — IEC 62388 §6. Presentation-only.
 * - Orientation: head-up / north-up / course-up — reuses ui-core [PpiOrientation]. Presentation-only.
 *
 * Presentation changes are hoisted out via [onDisplayChange]; range additionally drives [controller].
 */
@Composable
fun ModeControls(
    display: RadarDisplaySettings,
    controller: RadarController,
    modifier: Modifier = Modifier,
    onDisplayChange: (RadarDisplaySettings) -> Unit = {},
) {
    val scale = display.rangeScaleNm

    ControlSection(ControlVocabulary.RANGE, modifier) {
        // Permanent, prominent current-range indication.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                ControlVocabulary.formatRangeNm(scale),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            Text(ControlVocabulary.NM, style = MaterialTheme.typography.titleMedium)
            Text(
                "${ControlVocabulary.RINGS} ${ControlVocabulary.formatRangeNm(ringSpacingRounded(scale))} ${ControlVocabulary.NM}",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { selectRange(RangeModel.previousRangeScale(scale), controller, display, onDisplayChange) },
                enabled = scale > RangeModel.mandatoryScalesNm.first(),
                modifier = Modifier.weight(1f),
            ) { Text("− ${ControlVocabulary.RANGE}") }
            OutlinedButton(
                onClick = { selectRange(RangeModel.nextRangeScale(scale), controller, display, onDisplayChange) },
                enabled = scale < RangeModel.mandatoryScalesNm.last(),
                modifier = Modifier.weight(1f),
            ) { Text("+ ${ControlVocabulary.RANGE}") }
        }
        Spacer(Modifier.padding(top = 6.dp))
        // Direct selection of any mandatory scale.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RangeModel.mandatoryScalesNm.chunked(4).forEach { rowScales ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowScales.forEach { s ->
                        val selected = kotlin.math.abs(s - scale) <= 1e-9
                        if (selected) {
                            Button(onClick = { }, modifier = Modifier.weight(1f)) {
                                Text(ControlVocabulary.formatRangeNm(s), maxLines = 1)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { selectRange(s, controller, display, onDisplayChange) },
                                modifier = Modifier.weight(1f),
                            ) { Text(ControlVocabulary.formatRangeNm(s), maxLines = 1) }
                        }
                    }
                }
            }
        }
    }

    ControlSection(ControlVocabulary.MOTION, modifier) {
        SegmentedSelector(
            options = listOf(
                MotionMode.RELATIVE to "${ControlVocabulary.RELATIVE_MOTION} · RELATIVE",
                MotionMode.TRUE to "${ControlVocabulary.TRUE_MOTION} · TRUE",
            ),
            selected = display.motion,
            onSelect = { onDisplayChange(display.copy(motion = it)) },
        )
    }

    ControlSection(ControlVocabulary.ORIENTATION, modifier) {
        SegmentedSelector(
            options = listOf(
                PpiOrientation.HEAD_UP to ControlVocabulary.HEAD_UP,
                PpiOrientation.NORTH_UP to ControlVocabulary.NORTH_UP,
                PpiOrientation.COURSE_UP to ControlVocabulary.COURSE_UP,
            ),
            selected = display.orientation,
            onSelect = { onDisplayChange(display.copy(orientation = it)) },
        )
    }
}

/** Round ring spacing to a tidy 2-dp value for display. */
private fun ringSpacingRounded(scaleNm: Double): Double {
    val raw = ControlVocabulary.ringSpacingNm(scaleNm)
    return kotlin.math.round(raw * 100.0) / 100.0
}

private fun selectRange(
    newScaleNm: Double,
    controller: RadarController,
    display: RadarDisplaySettings,
    onDisplayChange: (RadarDisplaySettings) -> Unit,
) {
    if (kotlin.math.abs(newScaleNm - display.rangeScaleNm) <= 1e-9) return
    onDisplayChange(display.copy(rangeScaleNm = newScaleNm))
    // The radar must scan to the selected range. SetRange takes metres (see T1.3 encoder, 03C1).
    controller.send(RadarCommand.SetRange(RangeModel.nmToMeters(newScaleNm).toInt()))
}

@Preview(showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun ModeControlsPreview() {
    Column(Modifier.padding(8.dp)) {
        ModeControls(display = RadarDisplaySettings(rangeScaleNm = 6.0), controller = NoOpController)
    }
}
