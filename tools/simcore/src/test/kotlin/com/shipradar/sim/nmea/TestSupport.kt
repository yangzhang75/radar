package com.shipradar.sim.nmea

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 断言 [actual] 是一条合法 NMEA 句,且 `$`与`*`之间的内容恰为 [expectedContent],
 * 校验和与 CRLF 正确。把"期望字段串 + 校验和正确"合并为一处断言。
 */
fun assertNmea(actual: String, expectedContent: String) {
    assertTrue(actual.startsWith("$"), "应以 \$ 开头: $actual")
    assertTrue(actual.endsWith("\r\n"), "应以 CRLF 结尾")
    val core = actual.substring(1, actual.length - 2) // 去掉 $ 与 \r\n
    val star = core.lastIndexOf('*')
    assertTrue(star >= 0, "缺少 * 校验和分隔: $actual")
    val content = core.substring(0, star)
    val cs = core.substring(star + 1)
    assertEquals(expectedContent, content, "字段内容不符")
    assertEquals(2, cs.length, "校验和应为两位十六进制")
    assertEquals(Nmea.checksum(expectedContent), cs, "校验和不符")
}
