package com.shipradar.comms.iec450

/**
 * Pure-function transport-layer parser for IEC 61162-450 ED3 UDP datagrams (compliance trace
 * **SENS-02**). Given the raw bytes of **one** UDP datagram and the multicast [Iec450Group] it
 * arrived on, it extracts the embedded IEC 61162-1 sentences as [TaggedSentence]s, attaching the
 * source / sequence / grouping labels from their TAG blocks.
 *
 * Scope (T1.4): transport layer only. The parser validates datagram framing, the TAG-block
 * structure/checksum (§7.2.3, Annex B) and the **sentence framing + checksum** (structural
 * integrity required by §7.2.4/§7.2.5) — but it **never interprets sentence fields**. Field decoding
 * (talker, sentence id, data) is T1.5 (`com.shipradar.comms.iec61162`); [TaggedSentence.rawSentence]
 * is passed through verbatim.
 *
 * Robustness (VPN packet loss / truncation is expected): no input ever throws out of [parse];
 * oversized, mis-framed, bad-checksum or unsourced datagrams are dropped and counted in
 * [Iec450ParseResult.discards].
 *
 * The parser is stateless and thread-safe. Cross-datagram concerns — retransmission
 * de-duplication and group reassembly — are handled by [Iec450Deduplicator] and by the consumer
 * (see [TaggedSentence.grouping]).
 *
 * References: IEC 61162-450 ED3 §6.2.4 (size), §7.1.1/§7.1.2 (header), §7.2 (sentence transport),
 * §7.2.3 (TAG block parameters), §7.2.4/§7.2.5 (incoming-datagram processing + error logging),
 * Annex B (TAG block definitions).
 */
class Iec450FrameParser {

    /**
     * Parse one UDP datagram. Never throws.
     *
     * @param frame the complete UDP payload of a single 61162-450 datagram.
     * @param group the transmission group the datagram was received on.
     * @return the extracted sentences (possibly empty) plus per-datagram discard counters.
     */
    fun parse(frame: ByteArray, group: Iec450Group): Iec450ParseResult {
        // §6.2.4 — datagram must not exceed 1472 bytes; receivers may discard larger ones.
        if (frame.size > MAX_DATAGRAM_BYTES) {
            return Iec450ParseResult(emptyList(), Iec450DiscardCounters(oversized = 1))
        }

        // §7.1.1 — first six bytes must be a known header code + null; else discard (§7.1.2 count).
        val type = Iec450DatagramType.fromHeader(frame)
            ?: return Iec450ParseResult(emptyList(), Iec450DiscardCounters(invalidHeader = 1))

        // T1.4 only processes IEC 61162-1 sentence transport (UdPbC). Binary-file (§7.3) / PGN
        // (§7.4) / TCP (§7.6) datagrams have a valid header but are out of scope here.
        if (type != Iec450DatagramType.SENTENCE) {
            return Iec450ParseResult(emptyList(), Iec450DiscardCounters(nonSentenceDatagram = 1))
        }

        val payload = String(
            frame,
            Iec450DatagramType.HEADER_LENGTH,
            frame.size - Iec450DatagramType.HEADER_LENGTH,
            Charsets.ISO_8859_1, // lossless byte<->char; IEC 61162-1 content is ASCII
        )

        val sentences = ArrayList<TaggedSentence>()
        var ignoredNoSource = 0
        try {
            for (line in splitLines(payload)) {
                when (val r = parseLine(line, group)) {
                    is LineResult.Sentence -> sentences.add(r.sentence)
                    LineResult.TagOnly -> Unit
                    LineResult.IgnoreNoSource -> ignoredNoSource++
                }
            }
        } catch (e: DatagramDiscard) {
            // §7.2.4 — any syntax error in a TAG block or sentence discards the COMPLETE datagram.
            return Iec450ParseResult(emptyList(), e.reason.counters())
        }

        return Iec450ParseResult(sentences, Iec450DiscardCounters(ignoredNoSource = ignoredNoSource))
    }

    // --- line splitting ----------------------------------------------------------------------

    /**
     * Split the datagram payload into TAG-block "lines" on `<CR><LF>` (§B.5: every line is
     * terminated by `<CR><LF>`). A compliant datagram ends with a terminator, yielding a trailing
     * empty segment which is dropped. A non-empty unterminated final segment (e.g. a truncated
     * datagram) is kept and will fail validation in [parseLine] → whole-datagram discard (§7.2.4).
     */
    private fun splitLines(payload: String): List<String> {
        if (payload.isEmpty()) return emptyList()
        val parts = payload.split(LINE_TERMINATOR)
        // Drop only the trailing empty segment produced by a proper terminator.
        return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    // --- per-line parsing --------------------------------------------------------------------

    private sealed interface LineResult {
        data class Sentence(val sentence: TaggedSentence) : LineResult
        /** A TAG-block-only line (§B.5 case 1), e.g. a grouping/auth continuation — emits nothing. */
        data object TagOnly : LineResult
        /** Sentence ignored: no IEC 61162-450-conformant `s` parameter (§7.2.3.4). */
        data object IgnoreNoSource : LineResult
    }

    private fun parseLine(line: String, group: Iec450Group): LineResult {
        if (line.isEmpty()) return LineResult.TagOnly // tolerate a stray blank line

        // 1. Consume the leading run of TAG blocks (§7.2.3.1: each sentence is preceded by >=1).
        val tagBlocks = ArrayList<TagBlock>()
        var i = 0
        while (i < line.length && line[i] == TAG_DELIMITER) {
            val close = line.indexOf(TAG_DELIMITER, i + 1)
            if (close < 0) throw DatagramDiscard(DiscardReason.TAG_FRAMING) // no closing '\' (§B.2)
            tagBlocks.add(parseTagBlock(line.substring(i, close + 1)))
            i = close + 1
        }

        val remainder = line.substring(i)

        // 2. TAG-block-only line: nothing to emit (§B.5 case 1).
        if (remainder.isEmpty()) return LineResult.TagOnly

        // 3. Whatever follows the TAG blocks must be a sentence starting with '$' or '!' (§B.2/§B.5);
        //    anything else is stray content between TAG block and sentence → syntax error.
        if (remainder[0] != SENTENCE_START && remainder[0] != SENTENCE_START_ENCAPSULATED) {
            throw DatagramDiscard(DiscardReason.TAG_SYNTAX)
        }

        // 4. Validate sentence framing + checksum (structural only; §7.2.4/§7.2.5).
        validateSentenceFraming(remainder)

        // 5. Resolve the conformant source (§7.2.3.4/§7.2.3.2). No conformant `s` → ignore (soft).
        val sourceId = resolveSource(tagBlocks) ?: return LineResult.IgnoreNoSource

        return LineResult.Sentence(
            TaggedSentence(
                group = group,
                sourceId = sourceId,
                sequence = resolveSequence(tagBlocks),
                rawSentence = remainder,
                grouping = resolveGrouping(tagBlocks),
            ),
        )
    }

    // --- TAG block parsing (Annex B) ---------------------------------------------------------

    private fun parseTagBlock(raw: String): TagBlock {
        // §B.3: a TAG block is at most 80 characters including the two '\' delimiters.
        if (raw.length > MAX_TAG_BLOCK_CHARS) throw DatagramDiscard(DiscardReason.TAG_SYNTAX)

        val inner = raw.substring(1, raw.length - 1) // content between the two '\'
        val star = inner.lastIndexOf(CHECKSUM_DELIMITER)
        // Need `...*hh` with exactly two checksum digits at the end (§B.3 / §B.4).
        if (star < 0 || star + 3 != inner.length) throw DatagramDiscard(DiscardReason.TAG_FORMAT)

        val expected = NmeaChecksum.parseHex2(inner.substring(star + 1))
            ?: throw DatagramDiscard(DiscardReason.TAG_FORMAT)
        // §B.4: XOR of all chars between the opening '\' and the '*' delimiter.
        if (NmeaChecksum.xor(inner, 0, star) != expected) {
            throw DatagramDiscard(DiscardReason.TAG_CHECKSUM)
        }

        return TagBlock(raw, parseParams(inner.substring(0, star)))
    }

    /** Parse the `parameterList` (§B.3): `parameterPair (',' parameterPair)*`. */
    private fun parseParams(body: String): List<TagParam> {
        if (body.isEmpty()) throw DatagramDiscard(DiscardReason.TAG_SYNTAX) // empty parameterList
        val params = ArrayList<TagParam>()
        for (pair in body.split(PARAM_SEPARATOR)) {
            val colon = pair.indexOf(PARAM_KV_SEPARATOR)
            // parameterPair ::= parameterCode ':' value — code and value both non-empty.
            if (colon <= 0 || colon == pair.length - 1) throw DatagramDiscard(DiscardReason.TAG_SYNTAX)
            val code = pair.substring(0, colon)
            // parameterCode ::= [a-zA-Z0-9]+ (§B.3).
            if (!code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
                throw DatagramDiscard(DiscardReason.TAG_SYNTAX)
            }
            // Value kept raw and uninterpreted here (only s/n/g are read later); not field-validated
            // by type to avoid coupling to sentence semantics (T1.5).
            params.add(TagParam(code, pair.substring(colon + 1)))
        }
        return params
    }

    // --- sentence framing validation (structural only — NOT field parsing) -------------------

    private fun validateSentenceFraming(sentence: String) {
        // IEC 61162-1: at most 79 characters between the starting '$'/'!' and the <CR><LF>
        // terminator (§B.2); the *hh is included in that count, the leading delimiter is not.
        if (sentence.length - 1 > MAX_SENTENCE_BODY_CHARS) {
            throw DatagramDiscard(DiscardReason.SENTENCE)
        }
        val star = sentence.lastIndexOf(CHECKSUM_DELIMITER)
        if (star < 0 || star + 3 != sentence.length) throw DatagramDiscard(DiscardReason.SENTENCE)
        val expected = NmeaChecksum.parseHex2(sentence.substring(star + 1))
            ?: throw DatagramDiscard(DiscardReason.SENTENCE)
        // XOR between the starting delimiter (index 0, excluded) and the '*' (excluded).
        if (NmeaChecksum.xor(sentence, 1, star) != expected) {
            throw DatagramDiscard(DiscardReason.SENTENCE)
        }
    }

    // --- TAG parameter resolution ------------------------------------------------------------

    /**
     * Resolve the source SFI per §7.2.3.4 / §7.2.3.2: among all `s` parameters across the line's TAG
     * blocks, return the IEC 61162-450-conformant one **closest to the start of the sentence** (i.e.
     * the last conformant `s` in on-wire order). Non-conformant `s` values (§7.2.3.4, e.g. a bare
     * MMSI) are skipped. Returns `null` when no conformant `s` exists → the sentence is ignored.
     */
    private fun resolveSource(tagBlocks: List<TagBlock>): String? {
        var source: String? = null
        for (block in tagBlocks) {
            for (p in block.params) {
                if (p.code == CODE_SOURCE && isConformantSfi(p.value)) source = p.value
            }
        }
        return source
    }

    /**
     * Resolve the line-count `n` (§7.2.3.6): the last valid value in on-wire order, in 1..999.
     * `null` if absent or out of range (the source did not number this sentence).
     */
    private fun resolveSequence(tagBlocks: List<TagBlock>): Int? {
        var seq: Int? = null
        for (block in tagBlocks) {
            for (p in block.params) {
                if (p.code == CODE_LINE_COUNT) {
                    val v = p.value.toIntOrNull()
                    if (v != null && v in 1..999) seq = v
                }
            }
        }
        return seq
    }

    /**
     * Resolve the sentence-grouping `g` (§7.2.3.3): value `line-total-groupCode`. Returns `null`
     * when absent or malformed — the §7.2.3.3 rule that a *multi-sentence (MSM)* message with a
     * missing/inconsistent group must be discarded requires knowing the sentence is MSM, which is a
     * sentence-content concern (T1.5). The transport layer therefore only surfaces a well-formed
     * group and leaves the MSM completeness decision to the consumer.
     */
    private fun resolveGrouping(tagBlocks: List<TagBlock>): SentenceGroup? {
        for (block in tagBlocks) {
            for (p in block.params) {
                if (p.code == CODE_GROUPING) {
                    val parts = p.value.split(GROUP_FIELD_SEPARATOR)
                    if (parts.size == 3) {
                        val line = parts[0].toIntOrNull()
                        val total = parts[1].toIntOrNull()
                        val code = parts[2].toIntOrNull()
                        if (line != null && total != null && code != null) {
                            return SentenceGroup(line, total, code)
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * IEC 61162-450 conformance test for an `s`/SFI value (§4.4.3): `ccxxxx`, where `cc` is two
     * talker-mnemonic characters and `xxxx` is four numeric characters (instance 0001..9999, and
     * 9999 reserved/accepted on receive). Non-conformant sources (e.g. a 9-digit MMSI such as
     * `002300000`) fail this test and are ignored per §7.2.3.4.
     *
     * NOTE: `cc` is restricted here to ASCII letters, which matches every example in the standard.
     * §4.4.3 defers `cc` to the IEC 61162-1 talker-mnemonic / valid-character set, which could in
     * principle be broader — see delivery report (open question).
     */
    private fun isConformantSfi(value: String): Boolean = SFI_REGEX.matches(value)

    // --- discard signalling ------------------------------------------------------------------

    private enum class DiscardReason {
        TAG_FORMAT, TAG_CHECKSUM, TAG_SYNTAX, TAG_FRAMING, SENTENCE;

        fun counters(): Iec450DiscardCounters = when (this) {
            TAG_FORMAT -> Iec450DiscardCounters(tagFormatError = 1)
            TAG_CHECKSUM -> Iec450DiscardCounters(tagChecksumError = 1)
            TAG_SYNTAX -> Iec450DiscardCounters(tagSyntaxError = 1)
            TAG_FRAMING -> Iec450DiscardCounters(tagFramingError = 1)
            SENTENCE -> Iec450DiscardCounters(sentenceError = 1)
        }
    }

    private class DatagramDiscard(val reason: DiscardReason) : Exception() {
        // Control-flow signal; stack trace is never used.
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        /** §6.2.4 — maximum datagram size including the Clause 7 header. */
        const val MAX_DATAGRAM_BYTES = 1472

        /** §B.3 — maximum TAG block length including both `\` delimiters. */
        const val MAX_TAG_BLOCK_CHARS = 80

        /** §B.2 — maximum characters between the sentence start delimiter and `<CR><LF>`. */
        const val MAX_SENTENCE_BODY_CHARS = 79

        private const val LINE_TERMINATOR = "\r\n"
        private const val TAG_DELIMITER = '\\'
        private const val CHECKSUM_DELIMITER = '*'
        private const val SENTENCE_START = '$'
        private const val SENTENCE_START_ENCAPSULATED = '!'
        private const val PARAM_SEPARATOR = ','
        private const val PARAM_KV_SEPARATOR = ':'
        private const val GROUP_FIELD_SEPARATOR = '-'

        private const val CODE_SOURCE = "s"
        private const val CODE_LINE_COUNT = "n"
        private const val CODE_GROUPING = "g"

        /** SFI `ccxxxx` per §4.4.3 — two letters + four digits. */
        private val SFI_REGEX = Regex("^[A-Za-z]{2}[0-9]{4}$")
    }
}

/**
 * Result of parsing one datagram: the extracted [sentences] (in on-wire order) and the [discards]
 * tallied while processing this datagram.
 */
data class Iec450ParseResult(
    val sentences: List<TaggedSentence>,
    val discards: Iec450DiscardCounters,
)

/**
 * Per-datagram discard/error tallies. Counter names map onto the error-logging requirements of
 * IEC 61162-450 ED3 §7.1.2 (header) and §7.2.5 (incoming IEC 61162-1 sentence processing), plus the
 * §6.2.4 oversize rule. Callers accumulate these across datagrams (see [plus]) to feed the network
 * function error-logging counters of §4.3.3.
 *
 * @property oversized          §6.2.4 — datagram larger than 1472 bytes.
 * @property invalidHeader      §7.1.1/§7.1.2 — unknown or missing six-byte header.
 * @property nonSentenceDatagram valid header but not `UdPbC` (binary-file/PGN/TCP) — out of T1.4 scope.
 * @property tagFormatError     §7.2.5 — TAG-block formatting error (§7.2.3.1; bad `*hh` structure).
 * @property tagChecksumError   §7.2.5 — TAG-block checksum mismatch.
 * @property tagSyntaxError     §7.2.5 — TAG syntax error (line length, delimiters, invalid characters).
 * @property tagFramingError    §7.2.5 — TAG framing error (incorrect start/termination of TAG block).
 * @property sentenceError      §7.2.5 — sentence syntax error (formatting, length or checksum).
 * @property ignoredNoSource    §7.2.3.4 — sentence ignored: no IEC 61162-450-conformant `s` parameter.
 */
data class Iec450DiscardCounters(
    val oversized: Int = 0,
    val invalidHeader: Int = 0,
    val nonSentenceDatagram: Int = 0,
    val tagFormatError: Int = 0,
    val tagChecksumError: Int = 0,
    val tagSyntaxError: Int = 0,
    val tagFramingError: Int = 0,
    val sentenceError: Int = 0,
    val ignoredNoSource: Int = 0,
) {
    /** Sum two tallies field-wise (for accumulating across many datagrams). */
    operator fun plus(other: Iec450DiscardCounters): Iec450DiscardCounters = Iec450DiscardCounters(
        oversized = oversized + other.oversized,
        invalidHeader = invalidHeader + other.invalidHeader,
        nonSentenceDatagram = nonSentenceDatagram + other.nonSentenceDatagram,
        tagFormatError = tagFormatError + other.tagFormatError,
        tagChecksumError = tagChecksumError + other.tagChecksumError,
        tagSyntaxError = tagSyntaxError + other.tagSyntaxError,
        tagFramingError = tagFramingError + other.tagFramingError,
        sentenceError = sentenceError + other.sentenceError,
        ignoredNoSource = ignoredNoSource + other.ignoredNoSource,
    )

    /** Total number of dropped/ignored items recorded in this tally. */
    val total: Int
        get() = oversized + invalidHeader + nonSentenceDatagram + tagFormatError +
            tagChecksumError + tagSyntaxError + tagFramingError + sentenceError + ignoredNoSource
}
