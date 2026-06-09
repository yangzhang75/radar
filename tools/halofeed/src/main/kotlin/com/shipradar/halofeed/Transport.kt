package com.shipradar.halofeed

import com.shipradar.constants.Endpoint
import java.io.Closeable
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/** One UDP datagram bound for a multicast [endpoint]. Channel-agnostic: image/status/target/nav all use it. */
class Datagram(val endpoint: Endpoint, val payload: ByteArray)

/** A sink for datagrams. Lets live send, recording, and replay share one path. */
interface Transport : Closeable {
    fun send(d: Datagram)
}

/** Sends datagrams as real multicast UDP. One socket, addresses cached per endpoint. */
class MulticastTransport(ttl: Int = 1, iface: String? = null) : Transport {
    private val socket = MulticastSocket().apply {
        timeToLive = ttl
        iface?.let {
            networkInterface = NetworkInterface.getByName(it) ?: error("network interface '$it' not found")
        }
    }
    private val addrs = HashMap<Endpoint, InetSocketAddress>()

    override fun send(d: Datagram) {
        val sa = addrs.getOrPut(d.endpoint) {
            InetSocketAddress(InetAddress.getByName(d.endpoint.address), d.endpoint.port)
        }
        socket.send(DatagramPacket(d.payload, d.payload.size, sa))
    }

    override fun close() = socket.close()
}

/**
 * Wraps another [Transport] and additionally appends every datagram to a [RecordWriter] with a
 * monotonic timestamp, so a live session can be replayed byte-for-byte later.
 */
class RecordingTransport(
    private val delegate: Transport,
    private val writer: RecordWriter,
    private val clockNanos: () -> Long = System::nanoTime,
) : Transport {
    private val startNanos = clockNanos()

    override fun send(d: Datagram) {
        delegate.send(d)
        writer.append(RecordEntry((clockNanos() - startNanos) / 1_000, d.endpoint, d.payload))
    }

    override fun close() {
        writer.close()
        delegate.close()
    }
}
