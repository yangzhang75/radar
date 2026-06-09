package com.shipradar.comms.service

import com.shipradar.constants.Endpoint
import kotlinx.coroutines.flow.Flow

/**
 * The single socket-level seam of the comms module. Everything above it ([RadarCommsEngine],
 * [CommsRouter]) is plain logic; the only Android/socket dependency lives in implementations of this
 * interface. That is what makes the engine unit-testable on the JVM: tests inject a fake transport
 * (see test sources) and drive datagrams + virtual time, with no real network.
 *
 * Production implementation: [AndroidMulticastTransport] (real MulticastSocket + WifiManager
 * MulticastLock). Both HALO (236.6.7.x) and IEC 61162-450 (239.192.0.x) channels go through here.
 */
interface MulticastTransport {
    /**
     * Join the multicast group at [endpoint] and emit each received datagram's payload bytes.
     * Collecting the flow joins the group; cancelling the collection leaves the group and closes the
     * socket. The flow may complete or throw on socket error — the engine treats that as a dropped
     * channel and lets [com.shipradar.comms.sync.LinkSupervisor] schedule a reconnect.
     */
    fun inbound(endpoint: Endpoint): Flow<ByteArray>

    /** Send [data] as a single multicast datagram to [endpoint]. */
    suspend fun send(endpoint: Endpoint, data: ByteArray)

    /**
     * Acquire a Wi-Fi multicast lock for the transport's lifetime. Required to receive multicast even
     * on a **wired** interface, because the Android Wi-Fi stack otherwise filters inbound multicast
     * frames. Returns a handle the engine closes on shutdown. No-op for non-Android transports.
     */
    fun acquireMulticastLock(): AutoCloseable
}
