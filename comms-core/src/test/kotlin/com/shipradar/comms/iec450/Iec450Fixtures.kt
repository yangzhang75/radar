package com.shipradar.comms.iec450

/**
 * Test helpers that build well-formed (and deliberately malformed) IEC 61162-450 datagrams,
 * computing TAG-block and sentence checksums the same way the standard does (XOR, §B.4 / 61162-1).
 */
object Iec450Fixtures {

    private fun xor(s: String): Int = s.fold(0) { acc, c -> acc xor c.code } and 0xFF
    private fun hh(v: Int): String = "%02X".format(v)

    /** Build a TAG block `\<content>*hh\` with a correct checksum. `content` is the parameterList. */
    fun tag(content: String): String = "\\$content*${hh(xor(content))}\\"

    /** Build a TAG block but override the checksum bytes (to forge a bad-checksum block). */
    fun tagBadChecksum(content: String, badHh: String = "00"): String = "\\$content*$badHh\\"

    /**
     * Build a sentence `$<body>*hh` (or `!<body>*hh`) with a correct checksum. `body` is everything
     * between the start delimiter and the `*`.
     */
    fun sentence(body: String, encapsulated: Boolean = false): String {
        val delim = if (encapsulated) "!" else "$"
        return "$delim$body*${hh(xor(body))}"
    }

    /** Build a sentence with a deliberately wrong checksum. */
    fun sentenceBadChecksum(body: String, badHh: String = "00"): String = "$$body*$badHh"

    /**
     * Assemble a full `UdPbC` datagram from already-built lines (each line = optional TAG blocks +
     * optional sentence). Each line is `<CR><LF>`-terminated (§B.5).
     */
    fun frame(vararg lines: String, header: String = "UdPbC", nullByte: Boolean = true): ByteArray {
        val head = header.toByteArray(Charsets.ISO_8859_1) + (if (nullByte) byteArrayOf(0) else byteArrayOf())
        val payload = lines.joinToString("") { it + "\r\n" }
        return head + payload.toByteArray(Charsets.ISO_8859_1)
    }

    /** Assemble a datagram with a raw (possibly non-terminated) payload string. */
    fun rawFrame(payload: String, header: String = "UdPbC"): ByteArray =
        header.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + payload.toByteArray(Charsets.ISO_8859_1)
}
