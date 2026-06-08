package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers every legal transition of the IEC 62923-1 Annex G state diagrams plus representative
 * illegal transitions (rejected per the fail-to-safe principle). ALRM-01 transition coverage.
 */
class AlarmStateMachineTest {

    private fun assertTo(
        priority: AlarmPriority,
        from: BamAlertState,
        event: AlarmEventType,
        expected: BamAlertState,
        config: AlertConfig = AlertConfig(),
    ) {
        val r = AlarmStateMachine.next(priority, from, event, config)
        val acc = assertIs<TransitionResult.Accepted>(r, "expected $from --$event--> $expected, got $r")
        assertEquals(expected, acc.to)
    }

    private fun assertRejected(priority: AlarmPriority, from: BamAlertState, event: AlarmEventType) {
        val r = AlarmStateMachine.next(priority, from, event)
        assertIs<TransitionResult.Rejected>(r, "expected $from --$event--> rejected, got $r")
    }

    // --- Alarm / warning full lifecycle (Figure G.2 / G.3) -----------------------------------

    @Test fun alarm_raise_normal_to_activeUnack() =
        assertTo(AlarmPriority.ALARM, BamAlertState.NORMAL, AlarmEventType.RAISE, BamAlertState.ACTIVE_UNACK)

    @Test fun alarm_acknowledge_unack_to_ack() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_UNACK, AlarmEventType.ACKNOWLEDGE, BamAlertState.ACTIVE_ACK)

    @Test fun alarm_silence_unack_to_silenced() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_UNACK, AlarmEventType.SILENCE, BamAlertState.ACTIVE_SILENCED)

    @Test fun alarm_rectify_unack_to_rectifiedUnack() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_UNACK, AlarmEventType.RECTIFY, BamAlertState.RECTIFIED_UNACK)

    @Test fun alarm_acknowledge_silenced_to_ack() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_SILENCED, AlarmEventType.ACKNOWLEDGE, BamAlertState.ACTIVE_ACK)

    @Test fun alarm_rectify_silenced_to_rectifiedUnack() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_SILENCED, AlarmEventType.RECTIFY, BamAlertState.RECTIFIED_UNACK)

    @Test fun alarm_silenceTimeout_silenced_to_unack() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_SILENCED, AlarmEventType.SILENCE_TIMEOUT, BamAlertState.ACTIVE_UNACK)

    @Test fun alarm_rectify_ack_to_normal() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_ACK, AlarmEventType.RECTIFY, BamAlertState.NORMAL)

    @Test fun alarm_transfer_ack_to_respTransferred() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_ACK, AlarmEventType.TRANSFER_RESPONSIBILITY, BamAlertState.ACTIVE_RESP_TRANSFERRED)

    @Test fun alarm_rectify_respTransferred_to_normal() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_RESP_TRANSFERRED, AlarmEventType.RECTIFY, BamAlertState.NORMAL)

    @Test fun alarm_acknowledge_respTransferred_to_ack_optionalWT18() =
        assertTo(AlarmPriority.ALARM, BamAlertState.ACTIVE_RESP_TRANSFERRED, AlarmEventType.ACKNOWLEDGE, BamAlertState.ACTIVE_ACK)

    @Test fun alarm_acknowledge_rectifiedUnack_to_normal() =
        assertTo(AlarmPriority.ALARM, BamAlertState.RECTIFIED_UNACK, AlarmEventType.ACKNOWLEDGE, BamAlertState.NORMAL)

    @Test fun alarm_raise_rectifiedUnack_recurs_to_unack() =
        assertTo(AlarmPriority.ALARM, BamAlertState.RECTIFIED_UNACK, AlarmEventType.RAISE, BamAlertState.ACTIVE_UNACK)

    @Test fun alarm_terminate_from_each_active_state_to_normal() {
        for (s in listOf(
            BamAlertState.ACTIVE_UNACK, BamAlertState.ACTIVE_SILENCED, BamAlertState.ACTIVE_ACK,
            BamAlertState.ACTIVE_RESP_TRANSFERRED, BamAlertState.RECTIFIED_UNACK,
        )) {
            assertTo(AlarmPriority.ALARM, s, AlarmEventType.TERMINATE, BamAlertState.NORMAL)
        }
    }

    @Test fun warning_shares_full_lifecycle() {
        // Spot-check that WARNING uses the same lifecycle as ALARM.
        assertTo(AlarmPriority.WARNING, BamAlertState.NORMAL, AlarmEventType.RAISE, BamAlertState.ACTIVE_UNACK)
        assertTo(AlarmPriority.WARNING, BamAlertState.ACTIVE_UNACK, AlarmEventType.SILENCE, BamAlertState.ACTIVE_SILENCED)
        assertTo(AlarmPriority.WARNING, BamAlertState.ACTIVE_ACK, AlarmEventType.TRANSFER_RESPONSIBILITY, BamAlertState.ACTIVE_RESP_TRANSFERRED)
    }

    // --- bypass rectified-unack (Annex G footnote b, e.g. CPA/TCPA) --------------------------

    @Test fun alarm_rectify_unack_bypass_to_normal() =
        assertTo(
            AlarmPriority.ALARM, BamAlertState.ACTIVE_UNACK, AlarmEventType.RECTIFY, BamAlertState.NORMAL,
            config = AlertConfig(bypassRectifiedUnack = true),
        )

    @Test fun alarm_rectify_silenced_bypass_to_normal() =
        assertTo(
            AlarmPriority.ALARM, BamAlertState.ACTIVE_SILENCED, AlarmEventType.RECTIFY, BamAlertState.NORMAL,
            config = AlertConfig(bypassRectifiedUnack = true),
        )

    // --- Emergency alarm (Figure G.1) minimal lifecycle --------------------------------------

    @Test fun emergency_raise_normal_to_active() =
        assertTo(AlarmPriority.EMERGENCY_ALARM, BamAlertState.NORMAL, AlarmEventType.RAISE, BamAlertState.ACTIVE)

    @Test fun emergency_rectify_active_to_normal() =
        assertTo(AlarmPriority.EMERGENCY_ALARM, BamAlertState.ACTIVE, AlarmEventType.RECTIFY, BamAlertState.NORMAL)

    @Test fun emergency_cannot_be_silenced() =
        assertRejected(AlarmPriority.EMERGENCY_ALARM, BamAlertState.ACTIVE, AlarmEventType.SILENCE)

    @Test fun emergency_cannot_be_acknowledged() =
        assertRejected(AlarmPriority.EMERGENCY_ALARM, BamAlertState.ACTIVE, AlarmEventType.ACKNOWLEDGE)

    // --- Caution (Figure G.4) minimal lifecycle ----------------------------------------------

    @Test fun caution_raise_normal_to_active() =
        assertTo(AlarmPriority.CAUTION, BamAlertState.NORMAL, AlarmEventType.RAISE, BamAlertState.ACTIVE)

    @Test fun caution_rectify_active_to_normal() =
        assertTo(AlarmPriority.CAUTION, BamAlertState.ACTIVE, AlarmEventType.RECTIFY, BamAlertState.NORMAL)

    @Test fun caution_needs_no_acknowledge() =
        assertRejected(AlarmPriority.CAUTION, BamAlertState.ACTIVE, AlarmEventType.ACKNOWLEDGE)

    // --- representative illegal transitions --------------------------------------------------

    @Test fun illegal_acknowledge_from_normal() =
        assertRejected(AlarmPriority.ALARM, BamAlertState.NORMAL, AlarmEventType.ACKNOWLEDGE)

    @Test fun illegal_silence_from_ack() =
        assertRejected(AlarmPriority.ALARM, BamAlertState.ACTIVE_ACK, AlarmEventType.SILENCE)

    @Test fun illegal_silence_from_rectifiedUnack() =
        assertRejected(AlarmPriority.ALARM, BamAlertState.RECTIFIED_UNACK, AlarmEventType.SILENCE)

    @Test fun illegal_transfer_from_unack() =
        assertRejected(AlarmPriority.ALARM, BamAlertState.ACTIVE_UNACK, AlarmEventType.TRANSFER_RESPONSIBILITY)

    @Test fun illegal_silenceTimeout_from_unack() =
        assertRejected(AlarmPriority.ALARM, BamAlertState.ACTIVE_UNACK, AlarmEventType.SILENCE_TIMEOUT)

    @Test fun rejection_message_is_descriptive() {
        val r = AlarmStateMachine.next(AlarmPriority.ALARM, BamAlertState.ACTIVE_ACK, AlarmEventType.SILENCE)
        val rej = assertIs<TransitionResult.Rejected>(r)
        assertTrue(rej.reason.contains("illegal transition"), "reason should explain: ${rej.reason}")
    }

    @Test fun config_rejects_escalation_over_5min() {
        var threw = false
        try {
            AlertConfig(escalationMillis = BamTiming.MAX_ESCALATION_MS + 1, escalateToAlarmId = 3044)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "escalation > 5 min must be rejected (§6.3.5)")
    }
}
