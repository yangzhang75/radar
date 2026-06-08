package com.shipradar.comms.iec61162

/**
 * A structurally-validated IEC 61162-1 sentence: start delimiter checked, checksum verified,
 * address field split into talker + formatter, data fields delimited by ",".
 *
 * IEC 61162-1 ED6 §7.3.1 (general structure), §7.2.4 (checksum field), §7.2.3 (data fields).
 */
internal class SentenceFrame private constructor(
    /** '$' for parametric sentences, '!' for encapsulation sentences (§7.3.3 / §7.3.4). */
    val start: Char,
    val talker: String,
    val formatter: String,
    /** Data fields only (the address field is split out into [talker]/[formatter]); may be empty. */
    val fields: List<String>,
) {
    /** Field by 1-based data-field index as used in the standard's diagrams; null/blank → null. */
    fun field(index1Based: Int): String? = fields.getOrNull(index1Based - 1)?.takeIf { it.isNotEmpty() }

    sealed interface Result {
        data class Ok(val frame: SentenceFrame) : Result
        /** Start delimiter / "*" / length / address-field problems — a structurally broken packet. */
        object Malformed : Result
        /** Frame is well-formed but the transmitted checksum does not match (§7.2.4) — drop & count. */
        object ChecksumError : Result
    }

    companion object {
        /** §7.3.1: maximum 82 characters including "$"/"!" and <CR><LF>. */
        const val MAX_SENTENCE_LENGTH = 82

        /**
         * Frame and validate [raw]. Trailing <CR><LF> (and stray whitespace) are tolerated.
         * Returns [Result.Malformed] / [Result.ChecksumError] so the caller can count drop reasons.
         */
        fun parse(raw: String): Result {
            // Strip the sentence terminator and any transport whitespace (§7.3.1: ends with <CR><LF>).
            val s = raw.trim().trimEnd('\r', '\n').trim()
            if (s.length > MAX_SENTENCE_LENGTH) return Result.Malformed

            val start = s.firstOrNull() ?: return Result.Malformed
            if (start != '$' && start != '!') return Result.Malformed

            // §7.2.4: a checksum field is required in all sentences; it follows the "*" delimiter
            // and is exactly two upper-case hex characters.
            val star = s.lastIndexOf('*')
            if (star < 0 || star != s.length - 3) return Result.Malformed
            val body = s.substring(1, star) // between (excl) start delimiter and (excl) "*"
            if (body.isEmpty()) return Result.Malformed

            val expected = s.substring(star + 1).uppercase()
            val checksum = computeChecksum(body)
            val actual = checksum.toString(16).uppercase().padStart(2, '0')
            if (actual != expected) return Result.ChecksumError

            // §7.2.2 / §8 address field: first 2 chars = talker, remainder = formatter mnemonic.
            val parts = body.split(',')
            val address = parts[0]
            if (address.length < 3) return Result.Malformed // need 2-char talker + >=1 formatter char
            val talker = address.substring(0, 2)
            val formatter = address.substring(2)
            val fields = parts.drop(1)
            return Result.Ok(SentenceFrame(start, talker, formatter, fields))
        }

        /**
         * §7.2.4 checksum: eight-bit exclusive-OR of all characters between (but not including)
         * the "$"/"!" and "*" delimiters, including "," and "^" field delimiters.
         */
        fun computeChecksum(body: String): Int {
            var cs = 0
            for (c in body) cs = cs xor (c.code and 0xFF)
            return cs and 0xFF
        }
    }
}
