package com.shipradar.comms.service

import com.shipradar.comms.halo.handshake.LinkEvent
import com.shipradar.comms.halo.handshake.LinkStateMachine
import com.shipradar.comms.halo.image.SpokeParser
import com.shipradar.comms.halo.status.HaloStatusParser
import com.shipradar.comms.halo.target.TargetParser
import com.shipradar.comms.iec450.Iec450DiscardCounters
import com.shipradar.comms.iec450.Iec450FrameParser
import com.shipradar.comms.iec450.Iec450Group
import com.shipradar.comms.alarm.AlarmCommand
import com.shipradar.comms.alarm.AlarmIntent
import com.shipradar.comms.alarm.AlertCatalog
import com.shipradar.comms.alarm.BamAlarmManager
import com.shipradar.comms.iec61162.AlertCommand
import com.shipradar.comms.iec61162.AlertCommandKind
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
import com.shipradar.contract.ConningData
import com.shipradar.contract.OwnShipData
import com.shipradar.uicore.target.DangerClassifier
import com.shipradar.uicore.target.DangerCriteria
import com.shipradar.uicore.target.RadarTrackingPipeline
import com.shipradar.contract.RadarPowerState
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.TargetSource
import com.shipradar.contract.TargetStatus
import com.shipradar.contract.TrackedTarget
import com.shipradar.uicore.target.TargetLifecycle
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

    // 双量程 (HALO dual-range): Radar B 第二回波流 (236.6.7.13)。与 A 同样解析,独立去重。
    private val _echoSpokesB = MutableSharedFlow<EchoSpoke>(
        replay = 0,
        extraBufferCapacity = config.echoBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val echoSpokesB: Flow<EchoSpoke> get() = _echoSpokesB.asSharedFlow()

    private val _targets = MutableStateFlow<List<TrackedTarget>>(emptyList())
    val targets: StateFlow<List<TrackedTarget>> get() = _targets.asStateFlow()

    private val _ownShip = MutableStateFlow(OwnShipData())
    val ownShip: StateFlow<OwnShipData> get() = _ownShip.asStateFlow()

    private val _conning = MutableStateFlow(ConningData())
    val conning: StateFlow<ConningData> get() = _conning.asStateFlow()

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
    private val seqTrackerB = SeqTracker() // 双量程 Radar B 独立序列去重
    private val spokeParser = SpokeParser
    private val statusParser = HaloStatusParser
    private val targetParser = TargetParser
    private val iec450 = Iec450FrameParser()
    private val iec61162 = Iec61162Parser()
    private val ownShipFusion = OwnShipFusion()
    private val targetAggregator = TargetAggregator()

    // Radar-video target tracking: spokes → plots → tracks (ui-core). For an image-only radar like HALO
    // (no on-board ARPA / TT packets) this is where radar TTs are *born from the echo image*. When a real
    // radar also pushes TT packets via onHaloTarget, that snapshot path takes over instead.
    private val trackingPipeline = RadarTrackingPipeline()

    /** HALO rangeCellSize→mm 换算(默认 mm;真机待确认可设 dm)。见 [CommsConfig.rangeUnitToMm]。 */
    private val rangeUnitToMm = config.rangeUnitToMm

    // Collision assessment: the merged radar+AIS picture is enriched with CPA/TCPA + dangerous flag before
    // it is published, so every consumer (overlay red symbol, info panel, alarms) sees the same A.823
    // close-quarters classification. Re-run whenever targets OR own-ship motion change (CPA depends on both).
    private var rawTargets: List<TrackedTarget> = emptyList()
    @Volatile private var dangerCriteria = DangerCriteria()

    // New-target (3048) / lost-target (3052) edge detection over the confirmed radar-TT id set.
    private val radarLifecycle = TargetLifecycle()
    @Volatile private var lastNow = 0L

    /** Operator-settable CPA/TCPA close-quarters limits; re-publishes the current picture immediately. */
    fun setDangerCriteria(criteria: DangerCriteria) {
        dangerCriteria = criteria
        publishTargets(rawTargets)
    }

    private fun publishTargets(raw: List<TrackedTarget>) {
        rawTargets = raw
        val enriched = DangerClassifier.evaluateAll(_ownShip.value, raw, dangerCriteria)
        _targets.value = enriched
        // A.823 §3.3.2 — raise new-target (3048) / lost-target (3052) on the confirmed radar-TT set.
        val radarIds = enriched.asSequence()
            .filter { it.source == TargetSource.RADAR_TT && it.status == TargetStatus.TRACKED }
            .map { it.id }.toSet()
        val changes = radarLifecycle.update(radarIds)
        for (id in changes.appeared) {
            emitAlarms(alarmManager.raise(AlertCatalog.ID_NEW_TARGET, nowMillis = lastNow, text = "New target $id", source = "RADAR"))
        }
        for (id in changes.disappeared) {
            emitAlarms(alarmManager.raise(AlertCatalog.ID_LOST_TARGET, nowMillis = lastNow, text = "Lost target $id", source = "RADAR"))
        }
    }

    // BAM alarm state machine — the single source of truth for alarm state. Inbound alerts (ALR/ALF)
    // are raised through it and inbound commands (ACN/ARC) drive its transitions, so a central alarm
    // panel can acknowledge/silence/transfer our alarms (IEC 62923-1 §6.3 / §6.9).
    private val alarmManager = BamAlarmManager()

    // --- diagnostics (not part of the contract) ---
    @Volatile var discardCounters = Iec450DiscardCounters(); private set
    var aisDeferred = 0L; private set
    fun seqStats(): SeqStats = seqTracker.stats()

    // 链路监视计数(每通道收包数 + 最后到包时刻 ms),供 LIVE 诊断界面轮询。轻量 @Volatile,不走 Flow。
    @Volatile private var echoPkts = 0L;   @Volatile private var echoLast = 0L
    @Volatile private var echoBPkts = 0L;  @Volatile private var echoBLast = 0L
    @Volatile private var statusPkts = 0L; @Volatile private var statusLast = 0L
    @Volatile private var targetPkts = 0L; @Volatile private var targetLast = 0L
    @Volatile private var iec450Pkts = 0L; @Volatile private var iec450Last = 0L

    /** 链路状态快照(各通道收包/时延 + 回波序列完整性 + 450 丢弃)。UI 按需轮询并据此算速率。 */
    fun dataLinkSnapshot(nowMs: Long): DataLinkStats = DataLinkStats(
        nowMs = nowMs,
        linkState = _linkState.value,
        echo = ChannelStat(echoPkts, echoLast),
        echoB = ChannelStat(echoBPkts, echoBLast),
        status = ChannelStat(statusPkts, statusLast),
        target = ChannelStat(targetPkts, targetLast),
        iec450 = ChannelStat(iec450Pkts, iec450Last),
        seq = seqTracker.stats(),
        discards = discardCounters,
        aisDeferred = aisDeferred,
    )

    // ------------------------------------------------------------------ HALO inbound

    /** HALO echo image datagram (236.6.7.8). Returns liveness actions for the engine to execute. */
    fun onHaloImage(bytes: ByteArray, now: Long): List<LinkAction> {
        lastNow = now; echoPkts++; echoLast = now
        for (spoke in spokeParser.parse(bytes, rangeUnitToMm)) {
            // Drop retransmit duplicates; everything else (in-order / gap / reordered) reaches the renderer.
            if (seqTracker.observe(spoke.sequenceNumber) != SeqClass.DUPLICATE) {
                _echoSpokes.tryEmit(spoke)
                // Feed the tracker; once a full revolution completes it returns the radar-TT snapshot.
                trackingPipeline.onSpoke(spoke, now, _ownShip.value)?.let { tracks ->
                    publishTargets(targetAggregator.replaceRadarSnapshot(tracks))
                }
            }
        }
        return supervisor.onPacket(DataChannel.ECHO, now)
    }

    /** HALO Radar-B echo image datagram (双量程, 236.6.7.13). Parsed identically into the B flow. */
    fun onHaloImageB(bytes: ByteArray, now: Long): List<LinkAction> {
        lastNow = now; echoBPkts++; echoBLast = now
        for (spoke in spokeParser.parse(bytes, rangeUnitToMm)) {
            if (seqTrackerB.observe(spoke.sequenceNumber) != SeqClass.DUPLICATE) {
                _echoSpokesB.tryEmit(spoke)
            }
        }
        return supervisor.onPacket(DataChannel.ECHO, now)
    }

    /** HALO status datagram (236.6.7.9): merge onto the running status snapshot. */
    fun onHaloStatus(bytes: ByteArray, now: Long): List<LinkAction> {
        lastNow = now; statusPkts++; statusLast = now
        _radarStatus.value = statusParser.parseStatus(bytes).applyTo(_radarStatus.value)
        return supervisor.onPacket(DataChannel.STATUS, now)
    }

    /** HALO tracked-target datagram (236.6.7.18): full radar-TT snapshot. */
    fun onHaloTarget(bytes: ByteArray, now: Long): List<LinkAction> {
        lastNow = now; targetPkts++; targetLast = now
        publishTargets(targetAggregator.replaceRadarSnapshot(targetParser.parseTargets(bytes)))
        return supervisor.onPacket(DataChannel.TARGET, now)
    }

    // ------------------------------------------------------------------ 61162-450 inbound

    /** A 61162-450 datagram received on [group]. Extracts sentences and routes each. */
    fun on450(group: Iec450Group, bytes: ByteArray, now: Long): List<LinkAction> {
        lastNow = now; iec450Pkts++; iec450Last = now
        val result = iec450.parse(bytes, group)
        discardCounters += result.discards
        for (ts in result.sentences) routeSentence(ts.rawSentence, now)
        return supervisor.onPacket(channelFor(group), now)
    }

    private fun routeSentence(raw: String, now: Long) {
        when (val parsed = iec61162.parse(raw)) {
            is ParsedSentence.OwnShipUpdate -> {
                _ownShip.value = ownShipFusion.merge(parsed.data)
                publishTargets(rawTargets) // own-ship motion changed → recompute CPA/TCPA on the current picture
            }
            is ParsedSentence.TargetUpdate -> publishTargets(targetAggregator.upsert(parsed.target))
            // Conning/engine sentence (RSA/RPM/DPT/DBT) → merge onto the running conning snapshot.
            is ParsedSentence.ConningUpdate -> _conning.value = _conning.value.mergedWith(parsed.data)
            // Inbound alert (ALR/ALF) → raise through the BAM state machine, emit the resulting event.
            is ParsedSentence.AlertUpdate -> emitAlarms(
                alarmManager.raise(
                    identifier = parsed.alarm.identifier,
                    nowMillis = now,
                    text = parsed.alarm.text,
                    source = parsed.alarm.source,
                    priorityOverride = parsed.alarm.priority,
                ),
            )
            // Inbound command (ACN ack/silence/responsibility) → drive the state machine.
            is ParsedSentence.AlertCommandReceived ->
                emitAlarms(alarmManager.accept(parsed.command.toAlarmCommand(), now))
            // ARC (a refusal reported by the source) and ALC (cyclic list) are informational here;
            // ARC reasons surface via the manager's own RefuseAcn path. No bus emission needed.
            is ParsedSentence.AlertCommandRefused -> {}
            is ParsedSentence.AlertListUpdate -> {}
            // AIS position reports are geographic-only; range/bearing fusion needs own-ship + ui-core
            // geometry (T1.6/ui-core), so they are counted here, not synthesised into targets.
            is ParsedSentence.AisPositionReport -> aisDeferred++
            // W5-A secondary sentences not yet wired to the bus — TODO(T1.x), tracked in 认证缺口清单 D-class.
            is ParsedSentence.TargetGeoUpdate -> aisDeferred++   // TLL geo-only; needs own-ship/geometry fusion (T1.6)
            is ParsedSentence.TargetLabels -> {}                 // TLB label association: needs a target-label store
            is ParsedSentence.RadarSystemDataUpdate -> {}        // RSD EBL/VRM/cursor: UI-side state (T2.5)
            is ParsedSentence.DisplayDimming -> {}               // DDC: should drive day/dusk/night palette (T2.9)
            is ParsedSentence.Heartbeat -> {}                    // HBT: sensor-supervision feed (T1.6 supervisor)
            is ParsedSentence.Unsupported -> {}
            null -> {}
        }
    }

    /** Emit the UI-facing [AlarmEvent]s carried by the manager's intents (ReportAlf/ReportAlc). */
    private fun emitAlarms(intents: List<AlarmIntent>) {
        for (intent in intents) when (intent) {
            is AlarmIntent.ReportAlf -> _alarms.tryEmit(intent.event)
            is AlarmIntent.ReportAlc -> intent.alerts.forEach { _alarms.tryEmit(it) }
            // RefuseAcn (→ outbound ARC), Annunciate (→ audible/visual driver), Escalate (timeout-driven
            // via tick(), not raise/accept) are not part of the UI AlarmEvent stream.
            else -> {}
        }
    }

    private fun AlertCommand.toAlarmCommand(): AlarmCommand = AlarmCommand(
        identifier = identifier,
        kind = when (kind) {
            AlertCommandKind.ACKNOWLEDGE -> AlarmCommand.Kind.ACKNOWLEDGE
            AlertCommandKind.SILENCE -> AlarmCommand.Kind.SILENCE
            AlertCommandKind.RESPONSIBILITY_TRANSFER -> AlarmCommand.Kind.RESPONSIBILITY_TRANSFER
            AlertCommandKind.REQUEST_REPEAT -> AlarmCommand.Kind.REQUEST_REPEAT
        },
        instance = instance ?: 1,
    )

    private fun channelFor(group: Iec450Group): DataChannel = when (group) {
        Iec450Group.TGTD -> DataChannel.TARGET
        Iec450Group.SATD, Iec450Group.NAVD -> DataChannel.OWN_SHIP
        Iec450Group.BAM1, Iec450Group.BAM2, Iec450Group.CAM1, Iec450Group.CAM2 -> DataChannel.ALARM
    }

    // ------------------------------------------------------------------ liveness / link state

    /** Advance the liveness tick; returns the supervisor's actions for the engine to execute. */
    fun onTick(now: Long): List<LinkAction> { lastNow = now; return supervisor.onTick(now) }

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
