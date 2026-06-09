package com.shipradar.app.alarm

import com.shipradar.comms.alarm.AudibleState
import com.shipradar.comms.alarm.VisualState
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure presentation logic tests (JVM, no device). Confirms the HMI view state matches IEC 62923-1
 * via the T2.8a state machine — flashing/audible, action enablement, urgency ordering, ID text.
 */
class AlarmPresentationTest {

    private fun ev(id: Int, p: AlarmPriority, s: AlarmState, text: String? = null, t: Long = 0) =
        AlarmEvent(identifier = id, priority = p, state = s, text = text, utcMillis = t)

    // --- annunciation derives from comms.alarm (Annex G) -------------------------------------

    @Test fun unack_alarm_flashes_and_sounds() {
        val row = AlarmPresentation.rowFor(ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_UNACK))
        assertEquals(VisualState.FLASHING, row.visual)
        assertEquals(AudibleState.AUDIBLE, row.audible)
        assertTrue(row.flashing)
        assertTrue(row.sounding)
    }

    @Test fun silenced_alarm_flashes_but_silent() {
        val row = AlarmPresentation.rowFor(ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_SILENCED))
        assertTrue(row.flashing)
        assertFalse(row.sounding)
        assertEquals(AudibleState.TEMPORARILY_SILENCED, row.audible)
    }

    @Test fun acknowledged_is_steady_and_silent() {
        val row = AlarmPresentation.rowFor(ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_ACK))
        assertFalse(row.flashing)
        assertFalse(row.sounding)
        assertEquals(VisualState.STEADY, row.visual)
    }

    @Test fun caution_active_is_steady_and_silent() {
        val row = AlarmPresentation.rowFor(ev(3043, AlarmPriority.CAUTION, AlarmState.ACTIVE))
        assertEquals(VisualState.STEADY, row.visual)
        assertEquals(AudibleState.SILENT, row.audible)
    }

    // --- action enablement matches the state machine -----------------------------------------

    @Test fun unack_alarm_offers_ack_and_silence_not_transfer() {
        val row = AlarmPresentation.rowFor(ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_UNACK))
        assertTrue(row.acknowledgeable)
        assertTrue(row.silenceable)
        assertFalse(row.transferable)
    }

    @Test fun acked_alarm_offers_transfer_not_silence() {
        val row = AlarmPresentation.rowFor(ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_ACK))
        assertFalse(row.silenceable)
        assertFalse(row.acknowledgeable)
        assertTrue(row.transferable)
    }

    @Test fun emergency_offers_no_ack_or_silence() {
        val row = AlarmPresentation.rowFor(ev(9001, AlarmPriority.EMERGENCY_ALARM, AlarmState.ACTIVE))
        assertFalse(row.acknowledgeable)
        assertFalse(row.silenceable)
        assertFalse(row.transferable)
    }

    @Test fun caution_offers_no_actions() {
        val row = AlarmPresentation.rowFor(ev(3043, AlarmPriority.CAUTION, AlarmState.ACTIVE))
        assertFalse(row.acknowledgeable)
        assertFalse(row.silenceable)
    }

    @Test fun rectified_unack_can_be_acknowledged() {
        val row = AlarmPresentation.rowFor(ev(3052, AlarmPriority.WARNING, AlarmState.RECTIFIED_UNACK))
        assertTrue(row.acknowledgeable)
        assertFalse(row.silenceable)
    }

    // --- standard ID text + colours ----------------------------------------------------------

    @Test fun title_falls_back_to_catalog_then_event_text_wins() {
        // No event text -> catalog title (IEC 62923-2 Table A.1).
        assertEquals("CPA/TCPA <ID>", AlarmPresentation.rowFor(ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_UNACK)).title)
        // Event text present -> preferred.
        assertEquals("CPA/TCPA target 12", AlarmPresentation.rowFor(ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_UNACK, text = "CPA/TCPA target 12")).title)
        // Unknown id -> generic fallback.
        assertEquals("Alert 8888", AlarmPresentation.rowFor(ev(8888, AlarmPriority.WARNING, AlarmState.ACTIVE_UNACK)).title)
    }

    @Test fun priority_colours_per_62288() {
        assertEquals(AlarmColors.ALARM_RED, AlarmColors.colorFor(AlarmPriority.ALARM))
        assertEquals(AlarmColors.ALARM_RED, AlarmColors.colorFor(AlarmPriority.EMERGENCY_ALARM))
        assertEquals(AlarmColors.WARNING_ORANGE, AlarmColors.colorFor(AlarmPriority.WARNING))
        assertEquals(AlarmColors.CAUTION_YELLOW, AlarmColors.colorFor(AlarmPriority.CAUTION))
    }

    // --- aggregate view state ----------------------------------------------------------------

    @Test fun ui_state_sorts_most_urgent_first_and_excludes_normal() {
        val ui = AlarmPresentation.uiStateOf(
            listOf(
                ev(3015, AlarmPriority.WARNING, AlarmState.ACTIVE_ACK, t = 5),
                ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_UNACK, t = 4),
                ev(9001, AlarmPriority.EMERGENCY_ALARM, AlarmState.ACTIVE, t = 3),
                ev(3043, AlarmPriority.CAUTION, AlarmState.ACTIVE, t = 2),
                ev(1111, AlarmPriority.ALARM, AlarmState.NORMAL, t = 1), // excluded
            )
        )
        assertEquals(4, ui.rows.size)
        assertEquals(AlarmPriority.EMERGENCY_ALARM, ui.rows.first().priority)
        assertEquals(listOf(9001, 3044, 3015, 3043), ui.rows.map { it.event.identifier })
        assertEquals(AlarmPriority.EMERGENCY_ALARM, ui.highestPriority)
        assertEquals(4, ui.activeCount)
    }

    @Test fun bar_is_most_urgent_unacknowledged() {
        val ui = AlarmPresentation.uiStateOf(
            listOf(
                ev(9001, AlarmPriority.EMERGENCY_ALARM, AlarmState.ACTIVE, t = 3), // not "unacknowledged" set
                ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_UNACK, t = 2),
                ev(3015, AlarmPriority.WARNING, AlarmState.ACTIVE_ACK, t = 1),
            )
        )
        // ACTIVE (emergency) is not in the unacknowledged set, so the bar surfaces the unack alarm.
        assertEquals(3044, ui.bar?.event?.identifier)
        assertEquals(1, ui.unacknowledgedCount)
    }

    @Test fun bar_falls_back_to_most_urgent_active_when_none_unacknowledged() {
        val ui = AlarmPresentation.uiStateOf(
            listOf(
                ev(3015, AlarmPriority.WARNING, AlarmState.ACTIVE_ACK, t = 1),
                ev(3044, AlarmPriority.ALARM, AlarmState.ACTIVE_ACK, t = 2),
            )
        )
        assertEquals(3044, ui.bar?.event?.identifier)
        assertEquals(0, ui.unacknowledgedCount)
    }

    @Test fun empty_input_yields_empty_state() {
        val ui = AlarmPresentation.uiStateOf(emptyList())
        assertTrue(ui.rows.isEmpty())
        assertNull(ui.bar)
        assertNull(ui.highestPriority)
        assertEquals(0, ui.activeCount)
    }

    // --- action → event mapping (intent round-trip) ------------------------------------------

    @Test fun actions_map_to_state_machine_events() {
        assertEquals(com.shipradar.comms.alarm.AlarmEventType.ACKNOWLEDGE, AlarmAction.Acknowledge(3044).event)
        assertEquals(com.shipradar.comms.alarm.AlarmEventType.SILENCE, AlarmAction.Silence(3044).event)
        assertEquals(com.shipradar.comms.alarm.AlarmEventType.TRANSFER_RESPONSIBILITY, AlarmAction.TransferResponsibility(3044).event)
    }

    @Test fun controller_receives_dispatched_action() {
        val seen = mutableListOf<AlarmAction>()
        val controller = RadarAlarmController { seen += it }
        controller.dispatch(AlarmAction.Acknowledge(3044, instance = 2))
        assertEquals(1, seen.size)
        assertEquals(3044, seen.first().identifier)
        assertEquals(2, seen.first().instance)
    }
}
