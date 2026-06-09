package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W5-B — [BamAlarmManager.accept] consuming inbound alert commands (61162 ACN, IEC 62923-1 §6.3/§6.9)
 * and producing ALF + ALC receipts / ARC refusals. Covers the full RESPONSIBILITY_TRANSFERRED chain.
 */
class AlarmCommandTest {

    private inline fun <reified T : AlarmIntent> List<AlarmIntent>.has(): Boolean = any { it is T }
    private inline fun <reified T : AlarmIntent> List<AlarmIntent>.first(): T =
        filterIsInstance<T>().firstOrNull() ?: error("no ${T::class.simpleName} in $this")

    private fun acn(id: Int, kind: AlarmCommand.Kind, instance: Int = 1) =
        AlarmCommand(identifier = id, kind = kind, instance = instance, source = "CAM1")

    // --- ACN acknowledge -> ACTIVE_ACK, with ALF + ALC ---------------------------------------

    @Test fun acn_acknowledge_drives_to_ack_and_replies_alf_and_alc() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        val intents = m.accept(acn(3044, AlarmCommand.Kind.ACKNOWLEDGE), nowMillis = 100)
        assertEquals(BamAlertState.ACTIVE_ACK, m.stateOf(3044))
        assertTrue(intents.has<AlarmIntent.ReportAlf>(), "expected an ALF receipt")
        assertTrue(intents.has<AlarmIntent.ReportAlc>(), "expected an ALC list reply")
        assertFalse(intents.has<AlarmIntent.RefuseAcn>())
        // ALF reports the acknowledged state.
        assertEquals(BamAlertState.ACTIVE_ACK, intents.first<AlarmIntent.ReportAlf>().bamState)
    }

    @Test fun acn_silence_drives_to_silenced() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        val intents = m.accept(acn(3044, AlarmCommand.Kind.SILENCE), nowMillis = 10)
        assertEquals(BamAlertState.ACTIVE_SILENCED, m.stateOf(3044))
        assertTrue(intents.has<AlarmIntent.ReportAlc>())
    }

    // --- responsibility-transfer FULL chain --------------------------------------------------

    @Test fun responsibility_transfer_full_chain_via_acn() {
        val m = BamAlarmManager()
        // raise (warning) -> ACTIVE_UNACK
        m.raise(3052, nowMillis = 0)
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3052))

        // ACN acknowledge -> ACTIVE_ACK (must precede transfer, Annex G W2->W5->W6)
        m.accept(acn(3052, AlarmCommand.Kind.ACKNOWLEDGE), nowMillis = 10)
        assertEquals(BamAlertState.ACTIVE_ACK, m.stateOf(3052))

        // ACN responsibility transfer -> ACTIVE_RESP_TRANSFERRED, with ALF + ALC
        val t = m.accept(acn(3052, AlarmCommand.Kind.RESPONSIBILITY_TRANSFER), nowMillis = 20)
        assertEquals(BamAlertState.ACTIVE_RESP_TRANSFERRED, m.stateOf(3052))
        assertTrue(t.has<AlarmIntent.ReportAlf>() && t.has<AlarmIntent.ReportAlc>())
        assertFalse(t.has<AlarmIntent.RefuseAcn>())

        // Optional WT18: a further acknowledge returns it to ACTIVE_ACK
        m.accept(acn(3052, AlarmCommand.Kind.ACKNOWLEDGE), nowMillis = 30)
        assertEquals(BamAlertState.ACTIVE_ACK, m.stateOf(3052))

        // Condition clears (not an ACN — a rectify event) -> NORMAL, alert dropped
        m.command(3052, event = AlarmEventType.RECTIFY, nowMillis = 40)
        assertEquals(BamAlertState.NORMAL, m.stateOf(3052))
        assertEquals(0, m.activeCount)
    }

    @Test fun transfer_before_acknowledge_is_refused() {
        val m = BamAlarmManager()
        m.raise(3052, nowMillis = 0) // ACTIVE_UNACK
        val intents = m.accept(acn(3052, AlarmCommand.Kind.RESPONSIBILITY_TRANSFER), nowMillis = 10)
        assertTrue(intents.has<AlarmIntent.RefuseAcn>(), "transfer from unack must be refused (ARC)")
        assertFalse(intents.has<AlarmIntent.ReportAlc>(), "no ALC on refusal")
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3052))
    }

    @Test fun transfer_denied_by_policy_yields_arc() {
        val m = BamAlarmManager()
        m.raise(3052, nowMillis = 0, configOverride = AlertConfig(grantResponsibilityTransfer = false))
        m.accept(acn(3052, AlarmCommand.Kind.ACKNOWLEDGE), nowMillis = 10) // ACTIVE_ACK
        val intents = m.accept(acn(3052, AlarmCommand.Kind.RESPONSIBILITY_TRANSFER), nowMillis = 20)
        val arc = intents.first<AlarmIntent.RefuseAcn>()
        assertEquals(AlarmEventType.TRANSFER_RESPONSIBILITY, arc.attempted)
        assertFalse(intents.has<AlarmIntent.ReportAlc>())
        assertEquals(BamAlertState.ACTIVE_ACK, m.stateOf(3052)) // unchanged (§6.9.2.1 deny)
    }

    // --- request-repeat ('Q') ----------------------------------------------------------------

    @Test fun request_repeat_reissues_alc_without_state_change() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        m.raise(3002, nowMillis = 1)
        val intents = m.accept(acn(3044, AlarmCommand.Kind.REQUEST_REPEAT), nowMillis = 5)
        val alc = intents.first<AlarmIntent.ReportAlc>()
        assertEquals(setOf(3044, 3002), alc.alerts.map { it.identifier }.toSet())
        assertTrue(intents.has<AlarmIntent.ReportAlf>(), "addressed alert's ALF re-issued")
        assertFalse(intents.has<AlarmIntent.RefuseAcn>())
        // No transition occurred.
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3044))
    }

    @Test fun request_repeat_for_unknown_alert_still_returns_list() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        val intents = m.accept(acn(9999, AlarmCommand.Kind.REQUEST_REPEAT), nowMillis = 5)
        assertTrue(intents.has<AlarmIntent.ReportAlc>())
        assertFalse(intents.has<AlarmIntent.ReportAlf>(), "no ALF for an unknown/absent alert")
    }

    // --- refusal / mapping -------------------------------------------------------------------

    @Test fun acn_on_unknown_alert_is_refused() {
        val m = BamAlarmManager()
        val intents = m.accept(acn(3044, AlarmCommand.Kind.ACKNOWLEDGE), nowMillis = 0)
        assertTrue(intents.first<AlarmIntent.RefuseAcn>().reason.contains("no active alert"))
    }

    @Test fun acn_acknowledge_on_emergency_is_refused() {
        val m = BamAlarmManager()
        m.raise(9001, nowMillis = 0, priorityOverride = AlarmPriority.EMERGENCY_ALARM)
        val intents = m.accept(acn(9001, AlarmCommand.Kind.ACKNOWLEDGE), nowMillis = 1)
        assertTrue(intents.has<AlarmIntent.RefuseAcn>())
        assertEquals(BamAlertState.ACTIVE, m.stateOf(9001))
    }

    @Test fun command_kind_maps_to_event() {
        assertEquals(AlarmEventType.ACKNOWLEDGE, AlarmCommand(3044, AlarmCommand.Kind.ACKNOWLEDGE).event)
        assertEquals(AlarmEventType.SILENCE, AlarmCommand(3044, AlarmCommand.Kind.SILENCE).event)
        assertEquals(AlarmEventType.TRANSFER_RESPONSIBILITY, AlarmCommand(3044, AlarmCommand.Kind.RESPONSIBILITY_TRANSFER).event)
        assertEquals(null, AlarmCommand(3044, AlarmCommand.Kind.REQUEST_REPEAT).event)
    }

    @Test fun alc_snapshot_reflects_active_set_after_acknowledge() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        m.raise(3052, nowMillis = 1)
        val intents = m.accept(acn(3044, AlarmCommand.Kind.ACKNOWLEDGE), nowMillis = 2)
        val alc = intents.first<AlarmIntent.ReportAlc>()
        assertEquals(2, alc.alerts.size) // both still active (ack does not remove)
    }
}
