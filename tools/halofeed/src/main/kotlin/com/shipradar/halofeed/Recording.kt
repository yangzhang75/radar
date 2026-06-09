package com.shipradar.halofeed

import com.shipradar.constants.Endpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One recorded datagram: time since capture start (µs), its destination endpoint, and the bytes sent. */
data class RecordEntry(val tMicros: Long, val endpoint: Endpoint, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordEntry) return false
        return tMicros == other.tMicros && endpoint == other.endpoint && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int = (tMicros.hashCode() * 31 + endpoint.hashCode()) * 31 + payload.contentHashCode()
}

/**
 * Simple self-describing capture format so any feed (image/status/target/nav) can be replayed.
 * Layout: ASCII magic, then per entry: tMicros(i64) | addrLen(u8) | addr | port(u16) | len(i32) | payload.
 * Container fields are big-endian (DataOutputStream); the payload bytes are stored verbatim, so the
 * HALO little-endian wire content is preserved exactly.
 */
object RecordFormat {
    const val MAGIC = "HALOFEEDREC1\n"
}

/** Streams [RecordEntry] records to [out]. Writes the magic header on construction. */
class RecordWriter(out: OutputStream) : Closeable {
    private val dos = DataOutputStream(BufferedOutputStream(out))
    init { dos.writeBytes(RecordFormat.MAGIC) }

    fun append(e: RecordEntry) {
        dos.writeLong(e.tMicros)
        val addr = e.endpoint.address.toByteArray(Charsets.US_ASCII)
        require(addr.size <= 255) { "address too long" }
        dos.writeByte(addr.size)
        dos.write(addr)
        dos.writeShort(e.endpoint.port)
        dos.writeInt(e.payload.size)
        dos.write(e.payload)
    }

    override fun close() = dos.close()

    companion object {
        fun toFile(path: String): RecordWriter = RecordWriter(File(path).outputStream())
    }
}

/** Reads [RecordEntry] records from [inp]; validates the magic header. */
class RecordReader(inp: InputStream) : Closeable {
    private val dis = DataInputStream(BufferedInputStream(inp))
    init {
        val magic = ByteArray(RecordFormat.MAGIC.length)
        dis.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == RecordFormat.MAGIC) { "not a halofeed recording" }
    }

    /** Read the next entry, or null at clean end-of-stream. */
    fun next(): RecordEntry? {
        val t = try { dis.readLong() } catch (e: EOFException) { return null }
        val addrLen = dis.readUnsignedByte()
        val addr = ByteArray(addrLen).also { dis.readFully(it) }
        val port = dis.readUnsignedShort()
        val len = dis.readInt()
        val payload = ByteArray(len).also { dis.readFully(it) }
        return RecordEntry(t, Endpoint(String(addr, Charsets.US_ASCII), port), payload)
    }

    fun readAll(): List<RecordEntry> = buildList { while (true) add(next() ?: break) }

    override fun close() = dis.close()

    companion object {
        fun fromFile(path: String): RecordReader = RecordReader(File(path).inputStream())
    }
}

/**
 * Replays recorded datagrams through [transport]. When [paced], re-creates the original inter-packet
 * timing from the µs timestamps; otherwise sends as fast as possible. Returns the number replayed.
 */
fun replay(reader: RecordReader, transport: Transport, paced: Boolean = true, sleeper: (Long) -> Unit = ::sleepMicros): Int {
    var prev = 0L
    var count = 0
    while (true) {
        val e = reader.next() ?: break
        if (paced) {
            val waitMicros = e.tMicros - prev
            if (waitMicros > 0) sleeper(waitMicros)
            prev = e.tMicros
        }
        transport.send(Datagram(e.endpoint, e.payload))
        count++
    }
    return count
}

internal fun sleepMicros(micros: Long) {
    if (micros <= 0) return
    Thread.sleep(micros / 1_000, ((micros % 1_000) * 1_000).toInt())
}
