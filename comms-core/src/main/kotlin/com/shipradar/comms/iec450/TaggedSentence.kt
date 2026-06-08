package com.shipradar.comms.iec450

/**
 * One IEC 61162-1 sentence extracted from a 61162-450 datagram, together with the transport-layer
 * labels (source / sequence / grouping) lifted from its TAG block(s).
 *
 * This is the **interface boundary between T1.4 (transport) and T1.5 (`iec61162`, sentence-content
 * parsing)**. [rawSentence] is handed over **verbatim and unparsed** — T1.4 validates only framing
 * and checksum (structural integrity, §7.2.4/§7.2.5); it never interprets talker IDs, sentence IDs
 * or data fields. T1.5 consumes [rawSentence] and does all field decoding.
 *
 * @property group     transmission group the datagram was received on (§6.2.2 Table 4).
 * @property sourceId  IEC 61162-450-conformant source SFI from TAG-block `s` (§7.2.3.4), resolved as
 *                     the conformant `s` closest to the start of the sentence (§7.2.3.2). `null` is
 *                     never emitted for an accepted sentence — a sentence with no conformant `s` is
 *                     ignored per §7.2.3.4 (see [Iec450FrameParser]); the field is nullable only to
 *                     keep the shape stable for future callers.
 * @property sequence  line-count from TAG-block `n` (§7.2.3.6), in 1..999, or `null` if the source
 *                     did not number this sentence. Used for retransmission de-duplication.
 * @property rawSentence the complete `$..*hh` or `!..*hh` sentence text, **without** the trailing
 *                     `<CR><LF>` terminator (the terminator is the line delimiter, not sentence
 *                     content). Passed through unmodified for T1.5.
 * @property grouping  parsed TAG-block `g` value (§7.2.3.3) if present, for sentence grouping /
 *                     multi-line (MSM) association; `null` for ungrouped single-line sentences.
 */
data class TaggedSentence(
    val group: Iec450Group,
    val sourceId: String?,
    val sequence: Int?,
    val rawSentence: String,
    val grouping: SentenceGroup? = null,
)

/**
 * Parsed value of the TAG-block sentence-grouping parameter `g` (IEC 61162-450 ED3 §7.2.3.3),
 * whose value has the form `line-total-groupCode`.
 *
 * The transport layer surfaces this so the consumer (T1.5, which knows whether a sentence is an
 * "MSM" multi-sentence message) can associate/reassemble grouped lines and apply the §7.2.3.3
 * completeness rule. In IEC 61162-450 each grouped line is itself an independently complete,
 * checksum-valid sentence — the transport layer does **not** concatenate bytes across datagrams.
 *
 * @property line      line number of this TAG block within the group (1-based).
 * @property total     total number of lines in the group.
 * @property groupCode group code (1..99, see §7.2.3.3) distinguishing concurrent groups.
 */
data class SentenceGroup(
    val line: Int,
    val total: Int,
    val groupCode: Int,
)
