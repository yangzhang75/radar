package com.shipradar.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Top-level HMI assembly — **OWNED BY THE ORCHESTRATOR**. Feature workers must NOT edit this file
 * or [MainActivity]; they each deliver a self-contained Composable in their own package, and the
 * orchestrator wires it into a slot here as it lands. This keeps the third-wave UI workers
 * file-level non-overlapping.
 *
 * Slot plan (each filled by one worker package; consume data via com.shipradar.contract interfaces
 * + preview data so they develop independently of the T1.1 service wiring):
 *   - center  : com.shipradar.app.ppi      PPI echo render surface          (T2.1r, vivacious-clover)
 *   - overlay : com.shipradar.app.target   target/track overlay             (T2.3r, few-basin)
 *   - chrome  : com.shipradar.app.framework OpenBridge scaffold/theme/bars   (T2.9, absorbed-stetson)
 *   - top     : com.shipradar.app.databar  data bar + permanent display     (T2.7, rounded-fireplace)
 *   - side    : com.shipradar.app.control  radar control panel              (T2.6, thorn-poppyseed)
 *   - modes   : com.shipradar.app.mode     range/motion/orientation switch  (T2.4, purring-budget)
 *   - input   : com.shipradar.app.input    touch/key/mouse + EBL/VRM        (T2.5, past-freon)
 *   - alarms  : com.shipradar.app.alarm    BAM alarm UI                     (T2.8, carefree-drip)
 *
 * As each FooView lands, the orchestrator replaces the placeholder below with the real composition.
 */
@Composable
fun RadarScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // TODO(orchestrator): compose framework scaffold + PPI + overlays + bars as workers deliver.
        Text("Ship Radar — HMI assembly (slots fill in as third-wave workers land)")
    }
}
