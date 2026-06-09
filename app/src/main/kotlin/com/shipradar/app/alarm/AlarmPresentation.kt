package com.shipradar.app.alarm

import com.shipradar.comms.alarm.AlarmEventType
import com.shipradar.comms.alarm.AlarmStateMachine
import com.shipradar.comms.alarm.AlertCatalog
import com.shipradar.comms.alarm.AudibleState
import com.shipradar.comms.alarm.BamAlarmManager
import com.shipradar.comms.alarm.BamAlertState
import com.shipradar.comms.alarm.TransitionResult
import com.shipradar.comms.alarm.VisualState
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState

/**
 * Pure (no Compose / no Android) presentation logic for the BAM alarm HMI (T2.8). Turns the
 * `com.shipradar.contract.AlarmEvent` stream produced by the T2.8a state machine into ready-to-draw
 * view state, deriving every display property straight from **IEC 62923-1**:
 *
 *  - flashing / audible annunciation — reused from `comms.alarm` so the HMI cannot drift from the
 *    certified state machine ([BamAlarmManager.annunciationFor], Annex G / Table 3);
 *  - which operator actions are offered — gated by what the state machine would actually accept
 *    ([AlarmStateMachine.next]), so the UI never shows an action that would be refused (ARC);
 *  - the standard alert title/purpose text — from [AlertCatalog] (IEC 62923-2 Table A.1);
 *  - priority colour — [AlarmColors] (IEC 62288 §4.7.2.1).
 *
 * Keeping this layer free of Compose makes it unit-testable on the JVM (no device required).
 */
object AlarmPresentation {

    /** Contract → comms state (1:1 since the contract was finalised per the T2.8a recommendation). */
    fun bamStateOf(state: AlarmState): BamAlertState = when (state) {
        AlarmState.NORMAL -> BamAlertState.NORMAL
        AlarmState.ACTIVE -> BamAlertState.ACTIVE
        AlarmState.ACTIVE_UNACK -> BamAlertState.ACTIVE_UNACK
        AlarmState.ACTIVE_SILENCED -> BamAlertState.ACTIVE_SILENCED
        AlarmState.ACTIVE_ACK -> BamAlertState.ACTIVE_ACK
        AlarmState.ACTIVE_RESP_TRANSFERRED -> BamAlertState.ACTIVE_RESP_TRANSFERRED
        AlarmState.RECTIFIED_UNACK -> BamAlertState.RECTIFIED_UNACK
    }

    /** Everything the HMI needs to render one alert row / the alarm bar. Immutable, comparable-free. */
    data class AlarmRow(
        val event: AlarmEvent,
        /** Standard title (IEC 62923-2 Table A.1), with the event's own text preferred when present. */
        val title: String,
        /** Longer purpose text from the catalog (null for unknown ids). */
        val detail: String?,
        val priority: AlarmPriority,
        val state: AlarmState,
        /** Short state label for the row, e.g. "UNACK", "SILENCED", "ACK", "RECTIFIED". */
        val stateLabel: String,
        val visual: VisualState,
        val audible: AudibleState,
        /** True when the indicator must flash (Annex G: any unacknowledged active state). */
        val flashing: Boolean,
        /** True while the audible is sounding (drives the loudspeaker / mute affordance). */
        val sounding: Boolean,
        val acknowledgeable: Boolean,
        val silenceable: Boolean,
        val transferable: Boolean,
        /** Packed ARGB priority colour (IEC 62288 §4.7.2.1). */
        val colorArgb: Int,
    )

    /** Aggregate view state for the whole alarm surface. */
    data class AlarmUiState(
        /** All displayable alerts, most-urgent first. NORMAL alerts are excluded (nothing to show). */
        val rows: List<AlarmRow>,
        /** The single alert the top [AlarmBar] highlights (most urgent unacknowledged, else most urgent active). */
        val bar: AlarmRow?,
        val activeCount: Int,
        /** Count of alerts still demanding acknowledgement (unack / silenced / rectified-unack). */
        val unacknowledgedCount: Int,
        val highestPriority: AlarmPriority?,
    )

    private val UNACKNOWLEDGED_STATES = setOf(
        AlarmState.ACTIVE_UNACK, AlarmState.ACTIVE_SILENCED, AlarmState.RECTIFIED_UNACK,
    )

    fun isUnacknowledged(state: AlarmState): Boolean = state in UNACKNOWLEDGED_STATES

    /** Build a single row from a (non-NORMAL) alert event. */
    fun rowFor(event: AlarmEvent): AlarmRow {
        val bam = bamStateOf(event.state)
        val (visual, audible) = BamAlarmManager.annunciationFor(event.priority, bam)
        val spec = AlertCatalog.spec(event.identifier)
        return AlarmRow(
            event = event,
            title = event.text?.takeIf { it.isNotBlank() } ?: spec?.title ?: "Alert ${event.identifier}",
            detail = spec?.purpose,
            priority = event.priority,
            state = event.state,
            stateLabel = stateLabel(event.state),
            visual = visual,
            audible = audible,
            flashing = visual == VisualState.FLASHING,
            sounding = audible == AudibleState.AUDIBLE,
            acknowledgeable = accepts(event, AlarmEventType.ACKNOWLEDGE),
            silenceable = accepts(event, AlarmEventType.SILENCE),
            transferable = accepts(event, AlarmEventType.TRANSFER_RESPONSIBILITY),
            colorArgb = AlarmColors.colorFor(event.priority),
        )
    }

    /**
     * Build the full view state from the active-alert snapshot
     * (`com.shipradar.comms.alarm.BamAlarmManager.snapshot()`). Sorted most-urgent first; the bar
     * shows the most urgent unacknowledged alert, falling back to the most urgent active one.
     */
    fun uiStateOf(events: List<AlarmEvent>): AlarmUiState {
        val rows = events
            .filter { it.state != AlarmState.NORMAL }
            .map(::rowFor)
            .sortedWith(URGENCY)
        val bar = rows.firstOrNull { isUnacknowledged(it.state) } ?: rows.firstOrNull()
        return AlarmUiState(
            rows = rows,
            bar = bar,
            activeCount = rows.size,
            unacknowledgedCount = rows.count { isUnacknowledged(it.state) },
            highestPriority = rows.firstOrNull()?.priority,
        )
    }

    /** True iff the T2.8a state machine would accept [event] for this alert (single source of truth for button enablement). */
    private fun accepts(event: AlarmEvent, type: AlarmEventType): Boolean =
        AlarmStateMachine.next(event.priority, bamStateOf(event.state), type) is TransitionResult.Accepted

    private fun stateLabel(state: AlarmState): String = when (state) {
        AlarmState.NORMAL -> "NORMAL"
        AlarmState.ACTIVE -> "ACTIVE"
        AlarmState.ACTIVE_UNACK -> "UNACK"
        AlarmState.ACTIVE_SILENCED -> "SILENCED"
        AlarmState.ACTIVE_ACK -> "ACK"
        AlarmState.ACTIVE_RESP_TRANSFERRED -> "TRANSFERRED"
        AlarmState.RECTIFIED_UNACK -> "RECTIFIED"
    }

    /**
     * How much a state demands attention, low = more urgent. Unacknowledged-and-sounding first, then
     * silenced, then rectified-unack (cleared but unacked), then acknowledged/transferred.
     */
    private fun attentionRank(state: AlarmState): Int = when (state) {
        AlarmState.ACTIVE -> 0           // emergency/caution active (non-ackable)
        AlarmState.ACTIVE_UNACK -> 1
        AlarmState.ACTIVE_SILENCED -> 2
        AlarmState.RECTIFIED_UNACK -> 3
        AlarmState.ACTIVE_ACK -> 4
        AlarmState.ACTIVE_RESP_TRANSFERRED -> 5
        AlarmState.NORMAL -> 6
    }

    /**
     * Urgency ordering: priority (emergency→caution, by enum ordinal), then attention rank, then
     * most-recent first. Deterministic — id then instance as the final tie-break.
     */
    private val URGENCY: Comparator<AlarmRow> = compareBy<AlarmRow>(
        { it.priority.ordinal },
        { attentionRank(it.state) },
        { -it.event.utcMillis },
        { it.event.identifier },
    )
}
