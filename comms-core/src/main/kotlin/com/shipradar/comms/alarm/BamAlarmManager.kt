package com.shipradar.comms.alarm

import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority

/**
 * Stateful BAM alarm manager (**IEC 62923-1:2018**). Owns the set of alerts the radar (EUT) is the
 * *source* of, applies operator/CAM commands and time-driven transitions on top of the pure
 * [AlarmStateMachine], and emits [AlarmIntent]s for the comms + UI layers.
 *
 * **Time is injected**: every entry point takes `nowMillis`, and timeouts are resolved only when
 * [tick] is called. There is no wall clock, no threads, no I/O — the manager is fully deterministic
 * and unit-testable by feeding a simulated clock (ALRM-01 verification requirement).
 *
 * Not thread-safe; the comms service is expected to call it from a single coroutine/loop.
 */
class BamAlarmManager {

    /** Composite key: a standard alert id plus an instance discriminator (62923 alerts are per-instance). */
    data class Key(val identifier: Int, val instance: Int)

    private data class Entry(
        val identifier: Int,
        val instance: Int,
        val priority: AlarmPriority,
        val config: AlertConfig,
        val title: String?,
        val source: String,
        val state: BamAlertState,
        val raisedAtMillis: Long,
        val silencedAtMillis: Long?,
        val lastChangeMillis: Long,
    )

    private val entries = LinkedHashMap<Key, Entry>()

    /** Number of alerts currently tracked (i.e. not in NORMAL — NORMAL alerts are dropped). */
    val activeCount: Int get() = entries.size

    /** Current BAM state of an alert, or [BamAlertState.NORMAL] if untracked. */
    fun stateOf(identifier: Int, instance: Int = 1): BamAlertState =
        entries[Key(identifier, instance)]?.state ?: BamAlertState.NORMAL

    /**
     * Raise (or re-raise) an alert. Priority/config default to [AlertCatalog]; callers may override
     * for non-catalog ids. Re-raising a tracked alert routes through [AlarmStateMachine] (e.g.
     * RECTIFIED_UNACK → ACTIVE_UNACK); raising a fresh one starts from NORMAL.
     *
     * @throws IllegalArgumentException if the id is unknown and no [priorityOverride] is given.
     */
    fun raise(
        identifier: Int,
        instance: Int = 1,
        nowMillis: Long,
        text: String? = null,
        source: String = "",
        priorityOverride: AlarmPriority? = null,
        configOverride: AlertConfig? = null,
    ): List<AlarmIntent> {
        val key = Key(identifier, instance)
        val existing = entries[key]
        val priority = priorityOverride
            ?: existing?.priority
            ?: AlertCatalog.priorityOf(identifier)
            ?: throw IllegalArgumentException(
                "unknown alert id $identifier; pass priorityOverride (not in IEC 62923-2 Table A.1)"
            )
        val config = configOverride ?: existing?.config ?: AlertCatalog.configOf(identifier)
        val current = existing?.state ?: BamAlertState.NORMAL

        // W4-E audit fix: re-raising a condition that is *already active* (and not merely
        // rectified-and-recurring) is not an operator command — it is the alert source re-asserting an
        // unchanged condition. Treat it as an idempotent refresh (re-emit ALF, no state change, timers
        // preserved) instead of an illegal transition that would emit a spurious ARC. RECTIFIED_UNACK
        // is excluded: there the condition had cleared, so a fresh raise is a genuine recurrence and is
        // routed through the state machine (RECTIFIED_UNACK --RAISE--> ACTIVE_UNACK) below.
        if (existing != null &&
            current != BamAlertState.NORMAL &&
            current != BamAlertState.RECTIFIED_UNACK
        ) {
            val refreshed = existing.copy(
                title = text ?: existing.title,
                source = source.ifEmpty { existing.source },
                config = config,
                lastChangeMillis = nowMillis,
            )
            entries[key] = refreshed
            return emitFor(refreshed, previous = current, cause = AlarmEventType.RAISE, nowMillis = nowMillis)
        }

        return when (val r = AlarmStateMachine.next(priority, current, AlarmEventType.RAISE, config)) {
            is TransitionResult.Accepted -> {
                val raisedAt = if (existing == null || existing.state == BamAlertState.NORMAL) nowMillis else existing.raisedAtMillis
                val entry = Entry(
                    identifier, instance, priority, config,
                    title = text ?: AlertCatalog.spec(identifier)?.title,
                    source = source,
                    state = r.to,
                    raisedAtMillis = raisedAt,
                    silencedAtMillis = null,
                    lastChangeMillis = nowMillis,
                )
                entries[key] = entry
                emitFor(entry, previous = r.from, cause = AlarmEventType.RAISE, nowMillis = nowMillis)
            }
            is TransitionResult.Rejected ->
                listOf(AlarmIntent.RefuseAcn(identifier, instance, AlarmEventType.RAISE, r.reason))
        }
    }

    /**
     * Apply an operator/CAM command ([AlarmEventType.ACKNOWLEDGE], [AlarmEventType.SILENCE],
     * [AlarmEventType.RECTIFY], [AlarmEventType.TRANSFER_RESPONSIBILITY], [AlarmEventType.TERMINATE]).
     * Illegal transitions are refused with [AlarmIntent.RefuseAcn] (→ ARC); the alert is unchanged.
     *
     * [AlarmEventType.RAISE] must go through [raise]; the timeout events are internal to [tick].
     */
    fun command(identifier: Int, instance: Int = 1, event: AlarmEventType, nowMillis: Long): List<AlarmIntent> {
        require(event != AlarmEventType.RAISE) { "use raise() for RAISE" }
        require(event != AlarmEventType.SILENCE_TIMEOUT && event != AlarmEventType.ESCALATION_TIMEOUT) {
            "timeout events are produced internally by tick(), not by callers"
        }
        val key = Key(identifier, instance)
        val entry = entries[key]
            ?: return listOf(AlarmIntent.RefuseAcn(identifier, instance, event, "no active alert $identifier/$instance"))

        // W4-E audit fix (IEC 62923-1 §6.3.4): "a repeated activation of the temporary silence
        // command does not prevent the start of the audible signal after 30 s". A silence command on
        // an already-silenced alert is therefore accepted (re-emit, no ARC) but must NOT restart the
        // 30 s timer — so we re-emit without touching silencedAtMillis.
        if (event == AlarmEventType.SILENCE && entry.state == BamAlertState.ACTIVE_SILENCED) {
            return emitFor(entry, previous = entry.state, cause = AlarmEventType.SILENCE, nowMillis = nowMillis)
        }

        // Responsibility transfer is a *request*: honour only if the alert is configured to grant it.
        if (event == AlarmEventType.TRANSFER_RESPONSIBILITY && !entry.config.grantResponsibilityTransfer) {
            return listOf(
                AlarmIntent.RefuseAcn(
                    identifier, instance, event,
                    "responsibility-transfer request rejected (IEC 62923-1 §6.7)"
                )
            )
        }

        return applyTransition(entry, event, nowMillis)
    }

    /**
     * Advance time. Resolves, in order: (1) expired temporary-silence periods (ACTIVE_SILENCED →
     * ACTIVE_UNACK after [BamTiming.TEMPORARY_SILENCE_MS], audible restarts); (2) due warning
     * escalations (§6.3.5). Silence is processed first so a warning that was silenced reverts to
     * active-unack and may then escalate in the same tick — matching "escalates immediately after
     * the silence period has expired".
     */
    fun tick(nowMillis: Long): List<AlarmIntent> {
        val intents = mutableListOf<AlarmIntent>()

        // (1) Temporary-silence expiry.
        for (entry in entries.values.toList()) {
            if (entry.state == BamAlertState.ACTIVE_SILENCED) {
                val silencedAt = entry.silencedAtMillis ?: entry.lastChangeMillis
                if (nowMillis - silencedAt >= BamTiming.TEMPORARY_SILENCE_MS) {
                    intents += applyTransition(entry, AlarmEventType.SILENCE_TIMEOUT, nowMillis)
                }
            }
        }

        // (2) Warning escalation: only warnings, only from active-unacknowledged (§6.3.5 — escalation
        // occurs after reverting to active-unack), only when the escalation period has elapsed.
        for (entry in entries.values.toList()) {
            val escalationMillis = entry.config.escalationMillis ?: continue
            if (entry.priority != AlarmPriority.WARNING) continue
            if (entry.state != BamAlertState.ACTIVE_UNACK) continue
            if (nowMillis - entry.raisedAtMillis < escalationMillis) continue
            intents += escalate(entry, nowMillis)
        }
        return intents
    }

    /** Snapshot of all tracked alerts as contract events (for ALC-style alert-list reporting). */
    fun snapshot(): List<AlarmEvent> = entries.values.map { it.toContractEvent() }

    // --- internals ---------------------------------------------------------------------------

    private fun applyTransition(entry: Entry, event: AlarmEventType, nowMillis: Long): List<AlarmIntent> {
        return when (val r = AlarmStateMachine.next(entry.priority, entry.state, event, entry.config)) {
            is TransitionResult.Accepted -> {
                val key = Key(entry.identifier, entry.instance)
                val updated = entry.copy(
                    state = r.to,
                    silencedAtMillis = if (r.to == BamAlertState.ACTIVE_SILENCED) nowMillis else null,
                    lastChangeMillis = nowMillis,
                )
                if (r.to == BamAlertState.NORMAL) entries.remove(key) else entries[key] = updated
                emitFor(updated, previous = r.from, cause = event, nowMillis = nowMillis)
            }
            is TransitionResult.Rejected ->
                listOf(AlarmIntent.RefuseAcn(entry.identifier, entry.instance, event, r.reason))
        }
    }

    private fun escalate(warning: Entry, nowMillis: Long): List<AlarmIntent> {
        val newAlarmId = warning.config.escalateToAlarmId
            ?: error("escalationMillis set without escalateToAlarmId for ${warning.identifier}")
        val intents = mutableListOf<AlarmIntent>()
        // Terminate the original warning (§6.3.5: original warning is terminated).
        intents += applyTransition(warning, AlarmEventType.TERMINATE, nowMillis)
        // Raise the new alarm with a distinct id.
        intents += raise(newAlarmId, instance = warning.instance, nowMillis = nowMillis, source = warning.source)
        intents += AlarmIntent.Escalate(warning.identifier, warning.instance, newAlarmId)
        return intents
    }

    private fun emitFor(entry: Entry, previous: BamAlertState, cause: AlarmEventType, nowMillis: Long): List<AlarmIntent> {
        val (visual, audible) = annunciationFor(entry.priority, entry.state)
        return listOf(
            AlarmIntent.ReportAlf(entry.toContractEvent(nowMillis), entry.state, previous, cause),
            AlarmIntent.Annunciate(entry.identifier, entry.instance, visual, audible),
        )
    }

    private fun Entry.toContractEvent(nowMillis: Long = lastChangeMillis): AlarmEvent = AlarmEvent(
        identifier = identifier,
        priority = priority,
        state = state.toContract(),
        text = title,
        source = source,
        utcMillis = nowMillis,
    )

    companion object {
        /**
         * Visual + audible annunciation for a (priority, state) pair, per IEC 62923-1 Annex G state
         * annotations and Table 3; cautions are always silent (§6.3.6 / Table 5).
         */
        fun annunciationFor(priority: AlarmPriority, state: BamAlertState): Pair<VisualState, AudibleState> =
            when (state) {
                BamAlertState.NORMAL -> VisualState.NONE to AudibleState.SILENT
                BamAlertState.ACTIVE -> when (priority) {
                    // Emergency alarm: flashing + continuous audible, not silenceable (Figure G.1).
                    AlarmPriority.EMERGENCY_ALARM -> VisualState.FLASHING to AudibleState.AUDIBLE
                    // Caution: steady visual, silent (Figure G.4, §6.3.6).
                    else -> VisualState.STEADY to AudibleState.SILENT
                }
                BamAlertState.ACTIVE_UNACK -> VisualState.FLASHING to AudibleState.AUDIBLE
                BamAlertState.ACTIVE_SILENCED -> VisualState.FLASHING to AudibleState.TEMPORARILY_SILENCED
                BamAlertState.RECTIFIED_UNACK -> VisualState.FLASHING to AudibleState.SILENT
                BamAlertState.ACTIVE_ACK -> VisualState.STEADY to AudibleState.SILENT
                BamAlertState.ACTIVE_RESP_TRANSFERRED -> VisualState.STEADY to AudibleState.SILENT
            }
    }
}
