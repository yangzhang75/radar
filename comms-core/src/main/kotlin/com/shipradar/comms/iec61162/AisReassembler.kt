package com.shipradar.comms.iec61162

/**
 * Reassembles multi-fragment AIS VDM/VDO sentences (§8.3.114 / §8.3.115).
 *
 * A long ITU-R M.1371 message is split across several `!--VDM` sentences sharing a sequential
 * message identifier (§7.3.4.2). [Iec61162Parser.parse] handles only single-fragment messages
 * (total == 1) statelessly; this helper accumulates fragments so the sync stage (T1.6) can
 * present a complete payload back to the decoder. It is intentionally a separate, stateful object
 * to keep [Iec61162Parser.parse] pure.
 *
 * Scope note: this assembles the de-armoured payload; decoding of the longer message types it
 * unlocks (type 5 static/voyage, type 24 static report, …) is still deferred — see
 * [AisPayloadDecoder]. TODO(待标准: ITU-R M.1371-5 §3.3) decode those message bodies.
 */
class AisReassembler {

    private data class Key(val talker: String, val seqId: String)
    private class Buffer(val total: Int) {
        val parts = arrayOfNulls<String>(total)
        var fill: Int = 0
        var count: Int = 0
    }

    private val buffers = HashMap<Key, Buffer>()

    /** Result of feeding one fragment. */
    sealed interface Result {
        /** Message still incomplete; waiting for more fragments. */
        object Incomplete : Result
        /** All fragments collected: full six-bit payload + trailing fill-bit count. */
        data class Complete(val payload: String, val fillBits: Int) : Result
        /** Fragment was malformed (bad indices/payload); the partial message is dropped. */
        object Dropped : Result
    }

    /**
     * Feed one raw VDM/VDO sentence. Returns [Result.Dropped] if the frame is malformed or its
     * checksum is bad (§7.2.4), [Result.Incomplete] while fragments are still missing, or
     * [Result.Complete] when the final fragment arrives. A single-fragment message (total==1)
     * completes immediately.
     */
    fun feed(raw: String): Result {
        val frame = (SentenceFrame.parse(raw) as? SentenceFrame.Result.Ok)?.frame
            ?: return Result.Dropped
        val total = Fields.parseInt(frame.field(1)) ?: return Result.Dropped
        val index = Fields.parseInt(frame.field(2)) ?: return Result.Dropped
        val payload = frame.field(5) ?: return Result.Dropped
        val fill = Fields.parseInt(frame.field(6)) ?: 0
        if (total < 1 || index < 1 || index > total) return Result.Dropped

        if (total == 1) return Result.Complete(payload, fill)

        // §7.3.4.2: fragments of one message share the sequential message identifier (field 3).
        val seqId = frame.field(3) ?: return Result.Dropped
        val key = Key(frame.talker, seqId)
        val buf = buffers.getOrPut(key) { Buffer(total) }
        if (buf.total != total) {
            // Inconsistent fragment group — restart the buffer for this key.
            buffers[key] = Buffer(total)
        }
        val b = buffers.getValue(key)
        if (b.parts[index - 1] == null) b.count++
        b.parts[index - 1] = payload
        if (index == total) b.fill = fill

        return if (b.count == b.total) {
            buffers.remove(key)
            Result.Complete(b.parts.joinToString("") { it ?: "" }, b.fill)
        } else {
            Result.Incomplete
        }
    }

    /** Drop all buffered partial messages (e.g. on link reset). */
    fun reset() = buffers.clear()
}
