package com.shipradar.comms.service

import android.content.Context
import android.net.wifi.WifiManager
import com.shipradar.constants.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * The only Android/socket-coupled class in the module: real [MulticastTransport] over
 * [MulticastSocket] plus a [WifiManager] MulticastLock. Not unit-tested (needs a device); the engine
 * logic is tested against a fake transport. Network I/O runs on [Dispatchers.IO].
 *
 * The MulticastLock is essential even on a **wired** marine LAN: without `CHANGE_WIFI_MULTICAST_STATE`
 * + a held lock, Android's Wi-Fi stack drops inbound multicast and the HALO image/status streams never
 * arrive.
 */
class AndroidMulticastTransport(context: Context) : MulticastTransport {

    private val appContext = context.applicationContext

    override fun inbound(endpoint: Endpoint): Flow<ByteArray> = callbackFlow {
        val group = InetAddress.getByName(endpoint.address)
        val groupAddr = InetSocketAddress(group, endpoint.port)
        val netIf = pickMulticastInterface()
        val socket = MulticastSocket(endpoint.port).apply { reuseAddress = true }

        try {
            if (netIf != null) socket.joinGroup(groupAddr, netIf)
            else @Suppress("DEPRECATION") socket.joinGroup(group)
        } catch (e: Throwable) {
            socket.close()
            close(e)
            return@callbackFlow
        }

        val reader = thread(isDaemon = true, name = "mcast-${endpoint.address}:${endpoint.port}") {
            val buf = ByteArray(2048)
            try {
                while (!socket.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    trySend(pkt.data.copyOfRange(0, pkt.length))
                }
            } catch (_: Throwable) {
                // socket closed / interrupted — flow is being torn down
            }
        }

        awaitClose {
            runCatching {
                if (netIf != null) socket.leaveGroup(groupAddr, netIf)
                else @Suppress("DEPRECATION") socket.leaveGroup(group)
            }
            socket.close()
            reader.interrupt()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(endpoint: Endpoint, data: ByteArray) {
        withContext(Dispatchers.IO) {
            MulticastSocket().use { socket ->
                pickMulticastInterface()?.let { runCatching { socket.networkInterface = it } }
                socket.send(DatagramPacket(data, data.size, InetAddress.getByName(endpoint.address), endpoint.port))
            }
        }
    }

    override fun acquireMulticastLock(): AutoCloseable {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("shipradar-comms").apply {
            setReferenceCounted(false)
            acquire()
        }
        return AutoCloseable { runCatching { if (lock.isHeld) lock.release() } }
    }

    /** First up, multicast-capable, non-loopback interface with an address (best-effort). */
    private fun pickMulticastInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().firstOrNull {
            it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.hasMoreElements()
        }
    }.getOrNull()
}
