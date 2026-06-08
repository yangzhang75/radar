package com.shipradar.comms.iec450

/**
 * Stateful retransmission de-duplicator for the IEC 61162-450 sentence stream.
 *
 * IEC 61162-450 ED3 §7.2.3.6 lets a system function block tag selected sentences with a line-count
 * `n` (1..999, wrapping). A retransmitted sentence carries the **same** `(source, n)` as its
 * original, so duplicates are detected on the key `(group, sourceId, n)`. Sentences **without** an
 * `n` carry no transport-layer de-duplication signal — the source did not opt into sequencing — so
 * they are always passed through (de-duplicating them by content would wrongly drop legitimate
 * periodic updates that happen to repeat a value).
 *
 * Because `n` wraps at 999, only a small recent window of sequence numbers per source is remembered
 * ([windowPerSource]); a value reused after a full wrap is treated as new. This bounds memory and
 * avoids false positives. The precise retention policy across reconnects is owned by T1.6 (`sync`).
 *
 * Pure JVM and not thread-safe; drive it from a single consumer of the parsed sentence stream.
 *
 * @param windowPerSource number of most-recent `n` values retained per `(group, source)`.
 */
class Iec450Deduplicator(private val windowPerSource: Int = DEFAULT_WINDOW) {

    private val recentBySource = HashMap<String, ArrayDeque<Int>>()

    /**
     * @return `true` if [sentence] is a retransmission of one already seen within the window
     *         (and therefore should be dropped); `false` if it is new or unsequenced.
     *         Calling this records the sentence's sequence number.
     */
    fun isDuplicate(sentence: TaggedSentence): Boolean {
        val seq = sentence.sequence ?: return false      // no `n` → no dedup signal (§7.2.3.6)
        val source = sentence.sourceId ?: return false
        val key = sentence.group.name + '|' + source
        val recent = recentBySource.getOrPut(key) { ArrayDeque() }
        if (recent.contains(seq)) return true
        recent.addLast(seq)
        if (recent.size > windowPerSource) recent.removeFirst()
        return false
    }

    /** Filter a list, preserving order and dropping retransmissions. */
    fun deduplicate(sentences: List<TaggedSentence>): List<TaggedSentence> =
        sentences.filterNot { isDuplicate(it) }

    /** Forget all remembered sequence numbers (e.g. on reconnect, per T1.6 policy). */
    fun reset() = recentBySource.clear()

    companion object {
        const val DEFAULT_WINDOW = 32
    }
}
