package com.shipradar.comms.service

import com.shipradar.constants.Endpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * In-memory [MulticastTransport] for engine tests. Per-endpoint replaying flows let a test prime an
 * inbound datagram (e.g. the 01B2 reply) before the engine subscribes; [sent] captures every outbound
 * datagram and [subscribed] every joined endpoint.
 */
class FakeMulticastTransport : MulticastTransport {
    val sent = mutableListOf<Pair<Endpoint, ByteArray>>()
    val subscribed = mutableListOf<Endpoint>()
    var lockAcquireCount = 0
        private set

    private val flows = HashMap<Endpoint, MutableSharedFlow<ByteArray>>()

    private fun flowFor(endpoint: Endpoint): MutableSharedFlow<ByteArray> =
        flows.getOrPut(endpoint) { MutableSharedFlow(replay = 1, extraBufferCapacity = 64) }

    /** Push a datagram onto [endpoint]'s inbound stream (replayed to late subscribers). */
    fun emit(endpoint: Endpoint, bytes: ByteArray) {
        flowFor(endpoint).tryEmit(bytes)
    }

    fun sentTo(endpoint: Endpoint): List<ByteArray> = sent.filter { it.first == endpoint }.map { it.second }

    override fun inbound(endpoint: Endpoint): Flow<ByteArray> {
        subscribed += endpoint
        return flowFor(endpoint)
    }

    override suspend fun send(endpoint: Endpoint, data: ByteArray) {
        sent += endpoint to data
    }

    override fun acquireMulticastLock(): AutoCloseable {
        lockAcquireCount++
        return AutoCloseable {}
    }
}
