package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Manager-level behaviour: catalog mapping, intent emission, injected-time silence/escalation. */
class BamAlarmManagerTest {

    private inline fun <reified T : AlarmIntent> List<AlarmIntent>.first(): T =
        filterIsInstance<T>().firstOrNull() ?: error("no ${T::class.simpleName} in $this")

    // --- 62923-2 catalog ---------------------------------------------------------------------

    @Test fun catalog_priorities_match_table_A1() {
        assertEquals(AlarmPriority.ALARM, AlertCatalog.priorityOf(3044))   // CPA/TCPA
        assertEquals(AlarmPriority.WARNING, AlertCatalog.priorityOf(3052)) // lost target
        assertEquals(AlarmPriority.WARNING, AlertCatalog.priorityOf(3048)) // new target
        assertEquals(AlarmPriority.WARNING, AlertCatalog.priorityOf(3042)) // capacity (alarm variant)
        assertEquals(AlarmPriority.CAUTION, AlertCatalog.priorityOf(3043)) // capacity (caution variant)
        assertEquals(AlarmPriority.WARNING, AlertCatalog.priorityOf(3015)) // lost input
        assertEquals(AlarmPriority.WARNING, AlertCatalog.priorityOf(3002)) // lost interface
    }

    @Test fun catalog_titles_and_unknown() {
        assertEquals("CPA/TCPA <ID>", AlertCatalog.spec(3044)?.title)
        assertEquals("The system has lost communication with a connected system.", AlertCatalog.spec(3002)?.purpose)
        assertNull(AlertCatalog.priorityOf(9999))
    }

    @Test fun cpa_tcpa_default_bypasses_rectifiedUnack() {
        assertTrue(AlertCatalog.configOf(3044).bypassRectifiedUnack)
        assertTrue(!AlertCatalog.configOf(3052).bypassRectifiedUnack)
    }

    // --- raise / acknowledge intents ---------------------------------------------------------

    @Test fun raise_emits_alf_and_annunciate_with_contract_state() {
        val m = BamAlarmManager()
        val intents = m.raise(3044, nowMillis = 1_000, source = "RADAR1")
        val alf = intents.first<AlarmIntent.ReportAlf>()
        assertEquals(3044, alf.event.identifier)
        assertEquals(AlarmPriority.ALARM, alf.event.priority)
        assertEquals(AlarmState.ACTIVE_UNACK, alf.event.state)
        assertEquals(BamAlertState.ACTIVE_UNACK, alf.bamState)
        assertEquals(1_000, alf.event.utcMillis)
        val ann = intents.first<AlarmIntent.Annunciate>()
        assertEquals(VisualState.FLASHING, ann.visual)
        assertEquals(AudibleState.AUDIBLE, ann.audible)
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3044))
    }

    @Test fun cpa_tcpa_rectify_goes_straight_to_normal_and_is_dropped() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        m.command(3044, event = AlarmEventType.ACKNOWLEDGE, nowMillis = 10) // active-ack
        val intents = m.command(3044, event = AlarmEventType.RECTIFY, nowMillis = 20)
        assertEquals(BamAlertState.NORMAL, m.stateOf(3044))
        assertEquals(0, m.activeCount)
        assertEquals(AlarmState.NORMAL, intents.first<AlarmIntent.ReportAlf>().event.state)
    }

    @Test fun illegal_command_emits_refuseAcn_arc() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        m.command(3044, event = AlarmEventType.ACKNOWLEDGE, nowMillis = 1)
        val intents = m.command(3044, event = AlarmEventType.SILENCE, nowMillis = 2) // silence from ack: illegal
        val arc = intents.first<AlarmIntent.RefuseAcn>()
        assertEquals(AlarmEventType.SILENCE, arc.attempted)
        assertEquals(BamAlertState.ACTIVE_ACK, m.stateOf(3044)) // unchanged
    }

    @Test fun command_on_unknown_alert_is_refused() {
        val m = BamAlarmManager()
        val intents = m.command(3044, event = AlarmEventType.ACKNOWLEDGE, nowMillis = 0)
        assertTrue(intents.first<AlarmIntent.RefuseAcn>().reason.contains("no active alert"))
    }

    @Test fun emergency_silence_request_refused_at_manager() {
        val m = BamAlarmManager()
        m.raise(9001, nowMillis = 0, priorityOverride = AlarmPriority.EMERGENCY_ALARM)
        val intents = m.command(9001, event = AlarmEventType.SILENCE, nowMillis = 1)
        assertTrue(intents.any { it is AlarmIntent.RefuseAcn })
        assertEquals(BamAlertState.ACTIVE, m.stateOf(9001))
    }

    @Test fun responsibility_transfer_refused_when_not_granted() {
        val m = BamAlarmManager()
        m.raise(3052, nowMillis = 0, configOverride = AlertConfig(grantResponsibilityTransfer = false))
        m.command(3052, event = AlarmEventType.ACKNOWLEDGE, nowMillis = 1)
        val intents = m.command(3052, event = AlarmEventType.TRANSFER_RESPONSIBILITY, nowMillis = 2)
        val arc = intents.first<AlarmIntent.RefuseAcn>()
        assertTrue(arc.reason.contains("6.7"))
        assertEquals(BamAlertState.ACTIVE_ACK, m.stateOf(3052))
    }

    // --- temporary silence timeout (injected clock) ------------------------------------------

    @Test fun silence_reverts_to_unack_after_30s() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        m.command(3044, event = AlarmEventType.SILENCE, nowMillis = 1_000)
        assertEquals(BamAlertState.ACTIVE_SILENCED, m.stateOf(3044))

        // Before 30 s: no revert.
        assertTrue(m.tick(nowMillis = 1_000 + BamTiming.TEMPORARY_SILENCE_MS - 1).isEmpty())
        assertEquals(BamAlertState.ACTIVE_SILENCED, m.stateOf(3044))

        // At/after 30 s: audible restarts (back to active-unack).
        val intents = m.tick(nowMillis = 1_000 + BamTiming.TEMPORARY_SILENCE_MS)
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3044))
        val ann = intents.first<AlarmIntent.Annunciate>()
        assertEquals(AudibleState.AUDIBLE, ann.audible)
    }

    // --- warning escalation (injected clock) -------------------------------------------------

    @Test fun warning_escalates_to_alarm_with_different_id_after_period() {
        val m = BamAlarmManager()
        // Warning 3052 configured to escalate to alarm 3044 after 60 s.
        m.raise(
            3052, nowMillis = 0,
            configOverride = AlertConfig(escalationMillis = 60_000, escalateToAlarmId = 3044),
        )
        // Before the period: no escalation.
        assertTrue(m.tick(nowMillis = 59_999).none { it is AlarmIntent.Escalate })
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3052))

        val intents = m.tick(nowMillis = 60_000)
        val esc = intents.first<AlarmIntent.Escalate>()
        assertEquals(3052, esc.fromIdentifier)
        assertEquals(3044, esc.toAlarmIdentifier)
        // Original warning terminated, new alarm active.
        assertEquals(BamAlertState.NORMAL, m.stateOf(3052))
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3044))
        assertEquals(AlarmPriority.ALARM, AlertCatalog.priorityOf(3044))
        // New alarm id differs from the original warning (§6.3.5).
        assertTrue(esc.fromIdentifier != esc.toAlarmIdentifier)
    }

    @Test fun acknowledged_warning_does_not_escalate() {
        val m = BamAlarmManager()
        m.raise(3052, nowMillis = 0, configOverride = AlertConfig(escalationMillis = 60_000, escalateToAlarmId = 3044))
        m.command(3052, event = AlarmEventType.ACKNOWLEDGE, nowMillis = 1_000)
        val intents = m.tick(nowMillis = 120_000)
        assertTrue(intents.none { it is AlarmIntent.Escalate })
        assertEquals(BamAlertState.ACTIVE_ACK, m.stateOf(3052))
    }

    @Test fun silenced_warning_reverts_then_escalates_same_tick() {
        val m = BamAlarmManager()
        m.raise(3052, nowMillis = 0, configOverride = AlertConfig(escalationMillis = 20_000, escalateToAlarmId = 3044))
        m.command(3052, event = AlarmEventType.SILENCE, nowMillis = 0)
        // At 30 s both the silence (30 s) and escalation (20 s) periods have elapsed:
        // silence reverts to unack first, then escalation fires.
        val intents = m.tick(nowMillis = BamTiming.TEMPORARY_SILENCE_MS)
        assertTrue(intents.any { it is AlarmIntent.Escalate })
        assertEquals(BamAlertState.NORMAL, m.stateOf(3052))
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3044))
    }

    // --- snapshot ----------------------------------------------------------------------------

    @Test fun snapshot_reports_active_alerts() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 5)
        m.raise(3002, nowMillis = 6)
        val snap = m.snapshot()
        assertEquals(2, snap.size)
        assertEquals(setOf(3044, 3002), snap.map { it.identifier }.toSet())
    }

    @Test fun annunciation_caution_is_silent() {
        val (visual, audible) = BamAlarmManager.annunciationFor(AlarmPriority.CAUTION, BamAlertState.ACTIVE)
        assertEquals(VisualState.STEADY, visual)
        assertEquals(AudibleState.SILENT, audible)
    }

    // --- W4-E audit fixes --------------------------------------------------------------------

    @Test fun repeated_silence_is_accepted_not_refused() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        m.command(3044, event = AlarmEventType.SILENCE, nowMillis = 1_000)
        val intents = m.command(3044, event = AlarmEventType.SILENCE, nowMillis = 5_000)
        // §6.3.4: repeated temporary-silence command is accepted (re-emit, no ARC), state unchanged.
        assertTrue(intents.none { it is AlarmIntent.RefuseAcn }, "repeated silence must not be refused")
        assertTrue(intents.any { it is AlarmIntent.ReportAlf })
        assertEquals(BamAlertState.ACTIVE_SILENCED, m.stateOf(3044))
    }

    @Test fun repeated_silence_does_not_reset_the_30s_timer() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        m.command(3044, event = AlarmEventType.SILENCE, nowMillis = 1_000) // silencedAt = 1_000
        m.command(3044, event = AlarmEventType.SILENCE, nowMillis = 20_000) // must NOT reset to 20_000
        // Original 30 s from 1_000 expires at 31_000: audible restarts there, proving no timer reset.
        assertTrue(m.tick(nowMillis = 31_000 - 1).none { it is AlarmIntent.ReportAlf })
        assertEquals(BamAlertState.ACTIVE_SILENCED, m.stateOf(3044))
        m.tick(nowMillis = 31_000)
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3044))
    }

    @Test fun re_raise_of_active_alert_refreshes_without_arc() {
        val m = BamAlarmManager()
        m.raise(3044, nowMillis = 0)
        m.command(3044, event = AlarmEventType.ACKNOWLEDGE, nowMillis = 10) // ACTIVE_ACK
        val intents = m.raise(3044, nowMillis = 20, text = "CPA target 12")
        // Re-asserting an already-active condition is not an ACN: refresh, no ARC, no state change.
        assertTrue(intents.none { it is AlarmIntent.RefuseAcn }, "re-raise must not emit a spurious ARC")
        assertTrue(intents.any { it is AlarmIntent.ReportAlf })
        assertEquals(BamAlertState.ACTIVE_ACK, m.stateOf(3044))
        assertEquals(1, m.activeCount)
    }

    @Test fun re_raise_from_rectified_unack_is_a_genuine_recurrence() {
        val m = BamAlarmManager()
        m.raise(3052, nowMillis = 0) // warning -> ACTIVE_UNACK (no bypass)
        m.command(3052, event = AlarmEventType.RECTIFY, nowMillis = 10) // -> RECTIFIED_UNACK
        assertEquals(BamAlertState.RECTIFIED_UNACK, m.stateOf(3052))
        val intents = m.raise(3052, nowMillis = 20) // condition recurred
        assertTrue(intents.none { it is AlarmIntent.RefuseAcn })
        assertEquals(BamAlertState.ACTIVE_UNACK, m.stateOf(3052))
    }
}
