package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **W4-E certification audit (IEC 62923-1 Annex G).** Exhaustive truth table for
 * [AlarmStateMachine.next] — every (priority-class × state × event) cell is asserted against the
 * Annex G state diagrams (Fig G.1 emergency / G.2 alarm / G.3 warning / G.4 caution). Any divergence
 * — a missing legal transition or an illegal one silently accepted — fails here.
 *
 * Each legal cell carries its Annex G transition label; every other cell must be rejected (the
 * fail-to-safe principle, §8). This complements the example-based [AlarmStateMachineTest].
 */
class AlarmTransitionMatrixTest {

    private val ALL_STATES = BamAlertState.entries
    private val ALL_EVENTS = AlarmEventType.entries

    /**
     * Full lifecycle (alarm Fig G.2 / warning Fig G.3), default config. Map value = expected target
     * state; any (state,event) absent from the inner map must be **rejected**.
     */
    private val FULL: Map<BamAlertState, Map<AlarmEventType, BamAlertState>> = mapOf(
        BamAlertState.NORMAL to mapOf(
            AlarmEventType.RAISE to BamAlertState.ACTIVE_UNACK,                       // AT1/WT1
        ),
        BamAlertState.ACTIVE_UNACK to mapOf(
            AlarmEventType.ACKNOWLEDGE to BamAlertState.ACTIVE_ACK,                   // AT9/WT9
            AlarmEventType.SILENCE to BamAlertState.ACTIVE_SILENCED,                  // A2->A4 / W2->W4 (§6.3.4)
            AlarmEventType.RECTIFY to BamAlertState.RECTIFIED_UNACK,                  // AT5/WT5
            AlarmEventType.TERMINATE to BamAlertState.NORMAL,                         // AT18/WT19
        ),
        BamAlertState.ACTIVE_SILENCED to mapOf(
            AlarmEventType.ACKNOWLEDGE to BamAlertState.ACTIVE_ACK,                   // AT8
            AlarmEventType.RECTIFY to BamAlertState.RECTIFIED_UNACK,
            AlarmEventType.SILENCE_TIMEOUT to BamAlertState.ACTIVE_UNACK,             // AT7 (timeout, audible restarts)
            AlarmEventType.TERMINATE to BamAlertState.NORMAL,
        ),
        BamAlertState.ACTIVE_ACK to mapOf(
            AlarmEventType.RECTIFY to BamAlertState.NORMAL,                           // AT10/WT10
            AlarmEventType.TRANSFER_RESPONSIBILITY to BamAlertState.ACTIVE_RESP_TRANSFERRED, // AT13/WT14
            AlarmEventType.TERMINATE to BamAlertState.NORMAL,
        ),
        BamAlertState.ACTIVE_RESP_TRANSFERRED to mapOf(
            AlarmEventType.RECTIFY to BamAlertState.NORMAL,                           // AT20/WT12
            AlarmEventType.ACKNOWLEDGE to BamAlertState.ACTIVE_ACK,                   // AT14/WT18 (optional)
            AlarmEventType.TERMINATE to BamAlertState.NORMAL,
        ),
        BamAlertState.RECTIFIED_UNACK to mapOf(
            AlarmEventType.ACKNOWLEDGE to BamAlertState.NORMAL,                       // AT2/WT2
            AlarmEventType.RAISE to BamAlertState.ACTIVE_UNACK,                       // condition recurred before ack
            AlarmEventType.TERMINATE to BamAlertState.NORMAL,
        ),
        // ACTIVE is reserved for the minimal lifecycle — never legal here.
        BamAlertState.ACTIVE to emptyMap(),
    )

    /** Minimal lifecycle (emergency Fig G.1 / caution Fig G.4): NORMAL <-> ACTIVE only. */
    private val MINIMAL: Map<BamAlertState, Map<AlarmEventType, BamAlertState>> = mapOf(
        BamAlertState.NORMAL to mapOf(
            AlarmEventType.RAISE to BamAlertState.ACTIVE,                             // ET1/CT1
        ),
        BamAlertState.ACTIVE to mapOf(
            AlarmEventType.RECTIFY to BamAlertState.NORMAL,                           // ET2/CT2
            AlarmEventType.TERMINATE to BamAlertState.NORMAL,                         // ET3/CT3
        ),
    )

    private fun checkMatrix(
        priority: AlarmPriority,
        expected: Map<BamAlertState, Map<AlarmEventType, BamAlertState>>,
        config: AlertConfig = AlertConfig(),
    ) {
        val failures = mutableListOf<String>()
        for (state in ALL_STATES) {
            for (event in ALL_EVENTS) {
                val want = expected[state]?.get(event) // null => expect rejection
                val got = AlarmStateMachine.next(priority, state, event, config)
                when {
                    want != null && got !is TransitionResult.Accepted ->
                        failures += "$priority $state --$event--> expected $want but got $got"
                    want != null && (got as TransitionResult.Accepted).to != want ->
                        failures += "$priority $state --$event--> expected $want but got ${got.to}"
                    want == null && got is TransitionResult.Accepted ->
                        failures += "$priority $state --$event--> expected REJECT but accepted -> ${got.to}"
                }
            }
        }
        if (failures.isNotEmpty()) fail("Annex G matrix mismatches (${failures.size}):\n" + failures.joinToString("\n"))
    }

    @Test fun alarm_full_matrix() = checkMatrix(AlarmPriority.ALARM, FULL)

    @Test fun warning_full_matrix_is_identical_to_alarm() = checkMatrix(AlarmPriority.WARNING, FULL)

    @Test fun emergency_minimal_matrix() = checkMatrix(AlarmPriority.EMERGENCY_ALARM, MINIMAL)

    @Test fun caution_minimal_matrix() = checkMatrix(AlarmPriority.CAUTION, MINIMAL)

    @Test fun bypass_rectified_unack_redirects_both_rectify_cells_to_normal() {
        // Annex G footnote b (CPA/TCPA): rectify from an unacknowledged state goes straight to NORMAL.
        val bypassed = FULL.toMutableMap().apply {
            put(BamAlertState.ACTIVE_UNACK, FULL[BamAlertState.ACTIVE_UNACK]!! + (AlarmEventType.RECTIFY to BamAlertState.NORMAL))
            put(BamAlertState.ACTIVE_SILENCED, FULL[BamAlertState.ACTIVE_SILENCED]!! + (AlarmEventType.RECTIFY to BamAlertState.NORMAL))
        }
        checkMatrix(AlarmPriority.ALARM, bypassed, AlertConfig(bypassRectifiedUnack = true))
    }

    @Test fun every_cell_is_covered_by_the_matrix() {
        // Guard: the matrix iterates all 7 states x 8 events for each priority class (no silent gaps).
        assertEquals(7, ALL_STATES.size)
        assertEquals(8, ALL_EVENTS.size)
        assertTrue(FULL.keys.containsAll(ALL_STATES.toSet()))
    }
}
