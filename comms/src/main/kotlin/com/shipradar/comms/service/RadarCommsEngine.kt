package com.shipradar.comms.service

import com.shipradar.comms.halo.control.HaloControlEncoder
import com.shipradar.comms.halo.handshake.HaloHandshake
import com.shipradar.comms.halo.handshake.LinkEvent
import com.shipradar.comms.halo.handshake.RadarLinkInfo
import com.shipradar.comms.sync.DataChannel
import com.shipradar.comms.sync.LinkAction
import com.shipradar.constants.Endpoint
import com.shipradar.constants.HaloEndpoints
import com.shipradar.constants.HaloOpcodes
import com.shipradar.contract.AlarmEvent
import com.shipradar.contract.EchoSpoke
import com.shipradar.contract.LinkState
import com.shipradar.contract.OwnShipData
import com.shipradar.contract.RadarCommand
import com.shipradar.contract.RadarController
import com.shipradar.contract.RadarDataBus
import com.shipradar.contract.RadarStatus
import com.shipradar.contract.TrackCommand
import com.shipradar.contract.TrackedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the real radar link: runs the HALO handshake, the A1C1 watchdog, the per-channel multicast
 * collectors and the liveness tick, feeding everything through [CommsRouter] and exposing the result
 * as the frozen [RadarDataBus] / [RadarController]. All socket work is delegated to the injected
 * [MulticastTransport]; all timing uses [scope] + [now], so the whole engine runs under
 * `kotlinx-coroutines-test` virtual time with a fake transport.
 *
 * Lifecycle: [start] launches the pipeline on [scope]; [stop] tears it down (the Service owns [scope]
 * and cancels it on destroy). Commands sent before the handshake completes go to the default HALO
 * control endpoint and are re-pointed at the negotiated endpoint once 01B2 is parsed.
 */
class RadarCommsEngine(
    private val transport: MulticastTransport,
    private val config: CommsConfig,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
) : RadarDataBus, RadarController {

    private val router = CommsRouter(config)

    override val echoSpokes: Flow<EchoSpoke> get() = router.echoSpokes
    override val targets: StateFlow<List<TrackedTarget>> get() = router.targets
    override val ownShip: StateFlow<OwnShipData> get() = router.ownShip
    override val radarStatus: StateFlow<RadarStatus> get() = router.radarStatus
    override val alarms: Flow<AlarmEvent> get() = router.alarms
    override val linkState: StateFlow<LinkState> get() = router.linkState

    @Volatile private var controlEndpoint: Endpoint = HaloEndpoints.CONTROL
    @Volatile private var trackControlEndpoint: Endpoint = HaloEndpoints.TRACK_CONTROL

    /** Endpoints of the supervised, reconnectable channels (set after handshake). */
    private val channelEndpoints = HashMap<DataChannel, Endpoint>()
    private val channelJobs = HashMap<DataChannel, Job>()

    private var multicastLock: AutoCloseable? = null
    private var supervisorJob: Job? = null
    private var watchdogJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { run() }
    }

    fun stop() {
        watchdogJob?.cancel()
        supervisorJob?.cancel()
        channelJobs.values.forEach { it.cancel() }
        channelJobs.clear()
        multicastLock?.let { runCatching { it.close() } }
        multicastLock = null
        started = false
    }

    // --------------------------------------------------------------- RadarController

    override fun send(cmd: RadarCommand) {
        val endpoint = controlEndpoint
        scope.launch { runCatching { transport.send(endpoint, HaloControlEncoder.encode(cmd)) } }
    }

    override fun send(cmd: TrackCommand) {
        val endpoint = trackControlEndpoint
        scope.launch { runCatching { transport.send(endpoint, HaloControlEncoder.encodeTrack(cmd)) } }
    }

    // --------------------------------------------------------------- pipeline

    private suspend fun run() {
        multicastLock = runCatching { transport.acquireMulticastLock() }.getOrNull()

        val info = handshake()
        controlEndpoint = info.control ?: HaloEndpoints.CONTROL
        trackControlEndpoint = info.trackControl ?: HaloEndpoints.TRACK_CONTROL

        startWatchdog()

        // Supervised HALO data channels (reconnectable on staleness).
        bindChannel(DataChannel.ECHO, info.image ?: HaloEndpoints.IMAGE)
        bindChannel(DataChannel.STATUS, info.status ?: HaloEndpoints.STATUS)
        bindChannel(DataChannel.TARGET, info.target ?: HaloEndpoints.TARGET)

        // IEC 61162-450 sensor groups (own-ship / AIS / radar TT / BAM alarms).
        for (group in config.iec450Groups) {
            val ep = group.endpoint
            launchCollector(ep) { processActions(router.on450(group, it, now())) }
        }

        startTicks()
    }

    /** Run 01B1/01B2, falling back to a manual IP if configured. Updates [linkState] throughout. */
    private suspend fun handshake(): RadarLinkInfo {
        if (config.skipHandshake && config.manualRadarIp != null) {
            router.applyLinkEvent(LinkEvent.AllowReceived)
            return HaloHandshake.manualFallback(config.manualRadarIp)
        }
        while (scope.isActive) {
            router.applyLinkEvent(LinkEvent.RequestSent) // -> NEGOTIATING
            runCatching {
                transport.send(HaloHandshake.NEGOTIATION_ENDPOINT, HaloHandshake.buildLinkRequest())
            }
            val reply = withTimeoutOrNull(config.handshakeTimeoutMs) {
                transport.inbound(HaloHandshake.NEGOTIATION_ENDPOINT).firstOrNull { isLinkAllow(it) }
            }
            if (reply != null) {
                val info = runCatching { HaloHandshake.parseLinkAllow(reply) }.getOrNull()
                if (info != null) {
                    router.applyLinkEvent(LinkEvent.AllowReceived) // -> CONNECTED
                    return info
                }
            }
            router.applyLinkEvent(LinkEvent.NegotiationTimeout) // -> DISCONNECTED
            config.manualRadarIp?.let { ip ->
                router.applyLinkEvent(LinkEvent.AllowReceived)
                return HaloHandshake.manualFallback(ip)
            }
            delay(config.handshakeRetryDelayMs)
        }
        // Scope cancelled mid-handshake.
        return HaloHandshake.manualFallback(config.manualRadarIp ?: "")
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            val wd = HaloControlEncoder.encode(RadarCommand.Watchdog)
            while (isActive) {
                runCatching { transport.send(controlEndpoint, wd) }
                delay(config.watchdog.periodMs)
            }
        }
    }

    private fun startTicks() {
        supervisorJob?.cancel()
        supervisorJob = scope.launch {
            while (isActive) {
                delay(config.tickIntervalMs)
                processActions(router.onTick(now()))
            }
        }
    }

    /** Bind a supervised channel and remember its endpoint so a [LinkAction.Reconnect] can rebind it. */
    private fun bindChannel(channel: DataChannel, endpoint: Endpoint) {
        channelEndpoints[channel] = endpoint
        channelJobs[channel]?.cancel()
        channelJobs[channel] = launchCollector(endpoint, datagramHandler(channel))
    }

    /** The router call for a supervised HALO channel; its returned liveness actions are executed. */
    private fun datagramHandler(channel: DataChannel): (ByteArray) -> Unit = when (channel) {
        DataChannel.ECHO -> { b -> processActions(router.onHaloImage(b, now())) }
        DataChannel.STATUS -> { b -> processActions(router.onHaloStatus(b, now())) }
        DataChannel.TARGET -> { b -> processActions(router.onHaloTarget(b, now())) }
        else -> { _ -> }
    }

    /**
     * Collect [endpoint] datagrams into [onDatagram]. A socket error / completion ends the collector
     * quietly; the supervisor's staleness check will mark the channel LOST and schedule a reconnect.
     */
    private fun launchCollector(endpoint: Endpoint, onDatagram: (ByteArray) -> Unit): Job =
        scope.launch {
            try {
                transport.inbound(endpoint).collect { onDatagram(it) }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // dropped channel — handled by LinkSupervisor reconnect
            }
        }

    private fun processActions(actions: List<LinkAction>) {
        for (action in actions) when (action) {
            is LinkAction.Reconnect -> {
                val ep = channelEndpoints[action.channel] ?: continue
                rebind(action.channel, ep)
            }
            is LinkAction.RaiseCommsAlarm -> router.raiseCommsAlarm(action.channels, action.atMillis)
            is LinkAction.ClearCommsAlarm -> router.clearCommsAlarm(action.atMillis)
            is LinkAction.ChannelUp, is LinkAction.ChannelDown -> { /* rollup handled via comms alarm */ }
        }
    }

    private fun rebind(channel: DataChannel, endpoint: Endpoint) {
        if (channel !in channelEndpoints) return
        channelJobs[channel]?.cancel()
        channelJobs[channel] = launchCollector(endpoint, datagramHandler(channel))
    }

    private fun isLinkAllow(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == ((HaloOpcodes.LINK_ALLOW ushr 8) and 0xFF) &&
            (bytes[1].toInt() and 0xFF) == (HaloOpcodes.LINK_ALLOW and 0xFF)
}
