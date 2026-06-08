package com.shipradar.comms.iec450

/**
 * A single parsed IEC 61162-450 ED3 TAG block (Annex B): the content between a pair of `\`
 * delimiters, with its checksum already verified by [Iec450FrameParser].
 *
 * Form: `\` parameterList `*` checksum `\` (§B.3). [params] preserves the on-wire order of the
 * `code:value` pairs, which matters for the "closest to the start of the sentence wins" resolution
 * of duplicated `s`/`n` parameters (§7.2.3.2).
 *
 * @property raw    the block as received, including both `\` delimiters.
 * @property params ordered parameter `code:value` pairs (the parameterList of §B.3).
 */
internal data class TagBlock(
    val raw: String,
    val params: List<TagParam>,
)

/**
 * One TAG-block parameter: a [code] (`[a-zA-Z0-9]+`, §B.3) and its raw string [value]. Defined
 * parameter codes are listed in Table B.1 (`a c d g n r s t x z`). The value is kept as a raw
 * string and only interpreted for the transport-relevant codes `s`, `n`, `g`.
 */
internal data class TagParam(
    val code: String,
    val value: String,
)
