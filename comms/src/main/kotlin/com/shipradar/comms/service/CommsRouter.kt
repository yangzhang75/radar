package com.shipradar.comms.service

import com.shipradar.comms.halo.handshake.LinkEvent
import com.shipradar.comms.halo.handshake.LinkStateMachine
import com.shipradar.comms.halo.image.SpokeParser
import com.shipradar.comms.halo.status.HaloStatusParser
import com.shipradar.comms.halo.target.TargetParser
import com.shipradar.comms.iec450.Iec450DiscardCounters
import com.shipradar.comms.iec450.Iec450FrameParser
import com.shipradar.comms.iec450.Iec450Group
import com.shipradar.comms.iec61162.Iec61162Parser
import com.shipradar.comms.iec61162.ParsedSentence
import com.shipradar.comms.sync.DataChannel
import com.shipradar.comms.sync.LinkAction
import com.shipradar.comms.sync.LinkSupervisor
import com.shipradar.comms.sync.SeqClass
import com.shipradar.comms.sync.SeqStats
import com.shipradar.comms.sync.SeqTracker
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.AlarmPriority
import com.shipradar.contract.AlarmState
import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.LinkState
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.TrackedTarget
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The transport-agnostic core of the comms module: turns raw datagrams (already routed to a logical
 * channel by the engine) into the frozen [com.shipradar.contract.RadarDataBus] streams, and drives the
 * pure sync logic (sequence integrity, link liveness). Holds no sockets, no coroutines beyond the
 * flows it emits into, and no real clock — every entry point takes `now` from the caller — so it is
 * exhaustively unit-testable.
 *
 * Pipeline per datagram:
 *  - HALO image  → [SpokeParser] → [SeqTracker] dedup/loss stats → emit [EchoSpoke] (drop-oldest buffer)
 *  - HALO status → [HaloStatusParser] applied onto the running [RadarStatus]
 *  - HALO target → [TargetParser] radar-TT snapshot → [TargetAggregator]
 *  - 61162-450   → [Iec450FrameParser] → [Iec61162Parser] → own-ship fusion / TTM upsert / BAM alarm
 *  - every datagram also feeds [LinkSupervisor.onPacket] for staleness/reconnect/3002.
 *
 * Link liveness vs handshake state: [LinkSupervisor] (downlink staleness, 3002) and the handshake
 * [LinkStateMachine] (01B1/01B2 + watchdog) are combined here into the single contract [LinkState] the
 * UI sees — the comms-lost rollup degrades it; recovery restores it.
 */
class CommsRouter(config: CommsConfig) {

    // --- RadarDataBus backing flows ---
    private val _echoSpokes = MutableSharedFlow<EchoSpoke>(
        replay = 0,
        extraBufferCapacity = config.echoBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // echo drop policy (spec): old spokes expendable
    )
    val echoSpokes: Flow<EchoSpoke> get() = _echoSpokes.asSharedFlow()

    private val _targets = MutableStateFlow<List<TrackedTarget>>(emptyList())
    val targets: StateFlow<List<TrackedTarget>> get() = _targets.asStateFlow()

    private val _ownShip = MutableStateFlow(OwnShipData())
    val ownShip: StateFlow<OwnShipData> get() = _ownShip.asStateFlow()

    private val _radarStatus = MutableStateFlow(RadarStatus(powerState = RadarPowerState.OFF))
    val radarStatus: StateFlow<RadarStatus> get() = _radarStatus.asStateFlow()

    private val _alarms = MutableSharedFlow<AlarmEvent>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND, // alarms never dropped
    )
    val alarms: Flow<AlarmEvent> get() = _alarms.asSharedFlow()

    private val _linkState = MutableStateFlow(LinkStateMachine.INITIAL)
    val linkState: StateFlow<LinkState> get() = _linkState.asStateFlow()

    // --- pure logic collaborators ---
    private val supervisor = LinkSupervisor(config.channelConfigs)
    private val seqTracker = SeqTracker()
    private val spokeParser = SpokeParser
    private val statusParser = HaloStatusParser
    private val targetParser = TargetParser
    private val iec450 = Iec450FrameParser()
    private val iec61162 = Iec61162Parser()
    private val ownShipFusion = OwnShipFusion()
    private val targetAggregator = TargetAggregator()

    // --- diagnostics (not part of the contract) ---
    @Volatile var discardCounters = Iec450DiscardCounters(); private set
    var aisDeferred = 0L; private set
    fun seqStats(): SeqStats = seqTracker.stats()

    // ------------------------------------------------------------------ HALO inbound

    /** HALO echo image datagram (236.6.7.8). Returns liveness actions for the engine to execute. */
    fun onHaloImage(bytes: ByteArray, now: Long): List<LinkAction> {
        for (spoke in spokeParser.parse(bytes)) {
            // Drop retransmit duplicates; everything else (in-order / gap / reordered) reaches the renderer.
            if (seqTracker.observe(spoke.sequenceNumber) != SeqClass.DUPLICATE) {
                _echoSpokes.tryEmit(spoke)
            }
        }
        return supervisor.onPacket(DataChannel.ECHO, now)
    }

    /** HALO status datagram (236.6.7.9): merge onto the running status snapshot. */
    fun onHaloStatus(bytes: ByteArray, now: Long): List<LinkAction> {
        _radarStatus.value = statusParser.parseStatus(bytes).applyTo(_radarStatus.value)
        return supervisor.onPacket(DataChannel.STATUS, now)
    }

    /** HALO tracked-target datagram (236.6.7.18): full radar-TT snapshot. */
    fun onHaloTarget(bytes: ByteArray, now: Long): List<LinkAction> {
        _targets.value = targetAggregator.replaceRadarSnapshot(targetParser.parseTargets(bytes))
        return supervisor.onPacket(DataChannel.TARGET, now)
    }

    // ------------------------------------------------------------------ 61162-450 inbound

    /** A 61162-450 datagram received on [group]. Extracts sentences and routes each. */
    fun on450(group: Iec450Group, bytes: ByteArray, now: Long): List<LinkAction> {
        val result = iec450.parse(bytes, group)
        discardCounters += result.discards
        for (ts in result.sentences) routeSentence(ts.rawSentence)
        return supervisor.onPacket(channelFor(group), now)
    }

    private fun routeSentence(raw: String) {
        when (val parsed = iec61162.parse(raw)) {
            is ParsedSentence.OwnShipUpdate -> _ownShip.value = ownShipFusion.merge(parsed.data)
            is ParsedSentence.TargetUpdate -> _targets.value = targetAggregator.upsert(parsed.target)
            is ParsedSentence.AlertUpdate -> _alarms.tryEmit(parsed.alarm)
            // AIS position reports are geographic-only; range/bearing fusion needs own-ship + ui-core
            // geometry (T1.6/ui-core), so they are counted here, not synthesised into targets.
            is ParsedSentence.AisPositionReport -> aisDeferred++
            // W5-A secondary sentences: parsed + available, routed where trivial; the rest are
            // intentionally not yet wired to the bus — TODO(T1.x), tracked in 认证缺口清单 D-class.
            is ParsedSentence.TargetGeoUpdate -> aisDeferred++   // TLL geo-only; needs own-ship/geometry fusion (T1.6)
            is ParsedSentence.TargetLabels -> {}                 // TLB label association: needs a target-label store
            is ParsedSentence.RadarSystemDataUpdate -> {}        // RSD EBL/VRM/cursor: UI-side state (T2.5)
            is ParsedSentence.DisplayDimming -> {}               // DDC: should drive day/dusk/night palette (T2.9)
            is ParsedSentence.Heartbeat -> {}                    // HBT: sensor-supervision feed (T1.6 supervisor)
            is ParsedSentence.AlertListUpdate -> {}              // ALC cyclic alert list -> alarms
            is ParsedSentence.AlertCommandReceived -> {}         // ACN ack/responsibility -> BamAlarmManager (W5-B)
            is ParsedSentence.AlertCommandRefused -> {}          // ARC -> BamAlarmManager (W5-B)
            is ParsedSentence.Unsupported -> {}
            null -> {}
        }
    }

    private fun channelFor(group: Iec450Group): DataChannel = when (group) {
        Iec450Group.TGTD -> DataChannel.TARGET
        Iec450Group.SATD, Iec450Group.NAVD -> DataChannel.OWN_SHIP
        Iec450Group.BAM1, Iec450Group.BAM2, Iec450Group.CAM1, Iec450Group.CAM2 -> DataChannel.ALARM
    }

    // ------------------------------------------------------------------ liveness / link state

    /** Advance the liveness tick; returns the supervisor's actions for the engine to execute. */
    fun onTick(now: Long): List<LinkAction> = supervisor.onTick(now)

    /** Feed a handshake/recovery event into the combined contract [LinkState]. */
    fun applyLinkEvent(event: LinkEvent) {
        _linkState.value = LinkStateMachine.transition(_linkState.value, event)
    }

    /** Raise the BAM 3002 communications-lost alarm and degrade the link. */
    fun raiseCommsAlarm(channels: Set<DataChannel>, atMillis: Long) {
        _alarms.tryEmit(
            AlarmEvent(
                identifier = 3002,
                priority = AlarmPriority.WARNING,
                state = AlarmState.ACTIVE_UNACK,
                text = "Radar communications lost: ${channels.joinToString { it.name }}",
                source = "comms",
                utcMillis = atMillis,
            ),
        )
        applyLinkEvent(LinkEvent.Degraded)
    }

    /** Clear 3002 and recover the link. */
    fun clearCommsAlarm(atMillis: Long) {
        _alarms.tryEmit(
            AlarmEvent(
                identifier = 3002,
                priority = AlarmPriority.WARNING,
                state = AlarmState.NORMAL,
                text = "Radar communications restored",
                source = "comms",
                utcMillis = atMillis,
            ),
        )
        applyLinkEvent(LinkEvent.Recovered)
    }
}
