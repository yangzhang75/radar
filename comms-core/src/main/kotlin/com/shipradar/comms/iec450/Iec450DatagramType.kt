package com.shipradar.comms.iec450

/**
 * Datagram-header code carried in the first six bytes of every IEC 61162-450 datagram.
 *
 * Per IEC 61162-450 ED3 §7.1.1, the first six bytes are one of the five-character codes below
 * followed by a single null byte (all bits zero). Datagrams whose header is not one of these — or
 * whose sixth byte is not null — must be discarded without further processing (§7.1.1) and counted
 * (§7.1.2).
 *
 * T1.4 (this module) only *processes* [SENTENCE] (`UdPbC`) — the IEC 61162-1 sentence transport of
 * §7.2. The other codes are recognized so they can be told apart from a corrupt/unknown header, but
 * their payloads (binary-file transfer §7.3, PGN §7.4, TCP file transfer §7.6) are out of scope and
 * are reported as `nonSentenceDatagram` discards.
 */
enum class Iec450DatagramType(val tag: String) {
    /** `UdPbC` — IEC 61162-1 formatted sentences (§7.2). The only type processed by T1.4. */
    SENTENCE("UdPbC"),

    /** `RaUdP` — non re-transmittable binary file transfer over UDP multicast (§7.3). Out of scope. */
    BINARY_FILE("RaUdP"),

    /** `RrUdP` — re-transmittable binary file transfer over UDP multicast (§7.3). Out of scope. */
    BINARY_FILE_RETRANSMITTABLE("RrUdP"),

    /** `NkPgN` — IEC 61162-3 PGN message transmission (§7.4). Out of scope. */
    PGN("NkPgN"),

    /** `RrTcP` — binary file transfer over TCP/IP point-to-point (§7.6). Out of scope (not UDP). */
    TCP_BINARY_FILE("RrTcP");

    companion object {
        /** Length of the datagram header in bytes: five-character code + one null byte (§7.1.1). */
        const val HEADER_LENGTH = 6

        /**
         * Identify the datagram type from its leading bytes, or `null` if the frame is too short,
         * the sixth byte is not a null character, or the code is unknown (§7.1.1 → discard).
         */
        fun fromHeader(frame: ByteArray): Iec450DatagramType? {
            if (frame.size < HEADER_LENGTH) return null
            if (frame[5].toInt() != 0) return null // sixth byte must be the trailing null (§7.1.1)
            val tag = String(frame, 0, 5, Charsets.ISO_8859_1)
            return entries.firstOrNull { it.tag == tag }
        }
    }
}
