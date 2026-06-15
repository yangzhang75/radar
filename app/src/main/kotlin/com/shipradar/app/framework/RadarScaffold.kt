package com.shipradar.app.framework

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * T2.9 — OpenBridge layout container. The orchestrator drops each third-wave FooView into a named
 * slot here (see the slot plan in `RadarScreen`); this file owns the *frame*, not the contents.
 *
 * Layout (landscape 1920×1200), aligned to the OpenBridge 6.0 application frame:
 *
 *   ┌─────────────────────── top: data bar (permanent display) ───────────────────────┐
 *   │ side  │                      operational display area                            │
 *   │ ctrl  │   center (PPI)  +  overlay (targets)  +  input (EBL/VRM)  +  modes corner │
 *   │ panel │                                                                          │
 *   └─────────────────────────── alarms: BAM alert bar ───────────────────────────────┘
 *
 * The operational area uses the dark non-reflecting background mandated by IEC 62288 §5.4.1.1; chrome
 * (side/top/alarms) uses the OpenBridge container surface. The whole thing must sit inside an
 * [OpenBridgeTheme] so [OpenBridge.colors] resolves.
 *
 * Every slot defaults to empty so the scaffold renders (and previews) before all workers have landed.
 */
@Composable
fun RadarScaffold(
    modifier: Modifier = Modifier,
    /** Permanent data bar (T2.7) — always-visible navigation read-outs. */
    top: @Composable () -> Unit = {},
    /** Radar control panel (T2.6) — gain/sea/rain, presets. Sized by its own content. */
    side: @Composable () -> Unit = {},
    /** Range / motion / orientation switch cluster (T2.4), pinned to a corner of the operational area. */
    modes: @Composable () -> Unit = {},
    /** BAM alert bar (T2.8). */
    alarms: @Composable () -> Unit = {},
    /** PPI echo render surface (T2.1), fills the operational area. */
    center: @Composable () -> Unit = {},
    /** Target / track overlay (T2.3), drawn over [center] — gets [BoxScope] for alignment. */
    overlay: @Composable BoxScope.() -> Unit = {},
    /** Touch / key / EBL-VRM interaction layer (T2.5), topmost over [center] — gets [BoxScope]. */
    input: @Composable BoxScope.() -> Unit = {},
    /**
     * Right-hand information column (standard IMO layout area ③, cf. Furuno FAR-2xx8 §1.4): own-ship
     * read-outs, selected-target (TT/AIS) data, TT/AIS settings, collision-danger. Sized by content.
     */
    right: @Composable () -> Unit = {},
) {
    val c = OpenBridge.colors
    // chrome 基底用 OpenBridge 容器色(日间浅/夜间黑);只有中间操作区用暗底(IEC 62288 §5.4.1.1)。
    Column(modifier = modifier.fillMaxSize().background(c.chromeBackground)) {
        // Top permanent data bar.
        top()
        // Middle band: control panel | operational area | right info column.
        Row(Modifier.weight(1f).fillMaxHeight()) {
            side()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(c.operationalBackground),
            ) {
                // z-order: echoes → targets → interaction → mode cluster on top.
                center()
                overlay()
                input()
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { modes() }
            }
            right()
        }
        // Bottom alert bar.
        alarms()
    }
}

// ---------------------------------------------------------------------------------------------
// Previews — for on-device / Android Studio verification (no Android SDK in this build env, so the
// scaffold cannot be compiled/rendered here; these document the intended look per theme).
// ---------------------------------------------------------------------------------------------

@Composable
private fun previewScaffold(theme: ObTheme) {
    OpenBridgeTheme(theme) {
        RadarScaffold(
            top = {
                ObDataBar {
                    ObDataField("HDG", "047.3°T")
                    ObDataField("SOG", "12.4 kn")
                    ObDataField("RANGE", "6 NM")
                    ObDataField("CPA", "0.8 NM")
                }
            },
            side = {
                ObMenu(Modifier.width(180.dp).padding(8.dp)) {
                    ObMenuItem("Gain", onClick = {}, selected = true)
                    ObMenuItem("Sea clutter", onClick = {})
                    ObMenuItem("Rain", onClick = {})
                    ObButton("Transmit", onClick = {}, modifier = Modifier.padding(top = 8.dp))
                }
            },
            modes = {
                ObButton("N-UP", onClick = {}, selected = true)
            },
            center = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("PPI (T2.1)", color = OpenBridge.colors.foregroundSecondary, fontSize = 14.sp)
                }
            },
            alarms = {
                ObAlertBar(
                    priority = ObAlertPriority.WARNING,
                    message = "New target in guard zone",
                    acknowledged = false,
                    onAcknowledge = {},
                )
            },
        )
    }
}

@Preview(name = "Day", widthDp = 960, heightDp = 600)
@Composable
private fun PreviewDay() = previewScaffold(ObTheme.DAY)

@Preview(name = "Dusk", widthDp = 960, heightDp = 600)
@Composable
private fun PreviewDusk() = previewScaffold(ObTheme.DUSK)

@Preview(name = "Night", widthDp = 960, heightDp = 600)
@Composable
private fun PreviewNight() = previewScaffold(ObTheme.NIGHT)
