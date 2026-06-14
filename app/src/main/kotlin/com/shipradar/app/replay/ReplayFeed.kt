package com.shipradar.app.replay

import android.content.res.AssetManager
import com.shipradar.comms.halo.handshake.LinkEvent
import com.shipradar.comms.service.CommsRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * W8-D — App 内**真雷达数据回放**。读取 assets 里的文本抓包(整 IP 报文 hex),解析出每个 UDP 包的 HALO
 * 载荷,按目的端口分通道喂给 [CommsRouter],让 app 渲染真实回波。第三数据源(SIM / **REPLAY** / LIVE)。
 *
 * 抓包格式(每包):头行 `(UDP)src->dst:port ,N Bytes` + hex 行(`XX XX … ; ASCII` 注释需剥离)。
 * 载荷 = 去 IP 头(ihl*4)+ UDP 头(8B):`payload = datagram[ihl*4 + 8 until end]`,以 HALO 8 字节帧头
 * `01 00 00 00 00 20 00 02` 开头。本录像仅含少量包,故循环回放以便持续显示。
 */
object ReplayFeed {

    /** assets 内的真录像文件名(源:`~/Desktop/雷达开发资料/image`)。 */
    const val ASSET_NAME: String = "real_image.txt"

    /** 一个抓包的目的端点(用目的端口判定通道)。 */
    data class Endpoint(val host: String, val port: Int)

    /** 逻辑通道。 */
    enum class ReplayChannel { IMAGE, STATUS, TARGET, UNKNOWN }

    private val HEADER_DST = Regex("""->\s*([0-9.]+):(\d+)""")

    /** 目的端口 → 通道(6678/6656=图像,6679=状态,6688=目标)。 */
    fun channelOf(port: Int): ReplayChannel = when (port) {
        6678, 6656 -> ReplayChannel.IMAGE
        6679 -> ReplayChannel.STATUS
        6688 -> ReplayChannel.TARGET
        else -> ReplayChannel.UNKNOWN
    }

    /**
     * 解析抓包文本 → 每个 UDP 包的 (目的端点, HALO 载荷)。纯函数,可单测。坏块跳过不抛。
     */
    fun parseCapture(text: String): List<Pair<Endpoint, ByteArray>> {
        val result = ArrayList<Pair<Endpoint, ByteArray>>()
        var header: String? = null
        val body = StringBuilder()

        fun flush() {
            val h = header ?: return
            try {
                val m = HEADER_DST.find(h) ?: return
                val ep = Endpoint(m.groupValues[1], m.groupValues[2].toInt())
                val payload = ipUdpPayload(hexToBytes(body.toString())) ?: return
                result += ep to payload
            } catch (_: Exception) {
                // 坏块:跳过,不影响其余包。
            }
        }

        for (line in text.lineSequence()) {
            if (line.contains("(UDP)")) {
                flush()
                header = line
                body.setLength(0)
            } else if (header != null) {
                // 剥离 "; ASCII" 注释后追加 hex。
                body.append(' ').append(line.substringBefore(';'))
            }
        }
        flush()
        return result
    }

    /** 两位十六进制(空格分隔)→ 字节。非 2-hex token 忽略。 */
    fun hexToBytes(hex: String): ByteArray {
        val tokens = hex.trim().split(Regex("\\s+")).filter { it.length == 2 && it.all { c -> c.isHex() } }
        val out = ByteArray(tokens.size)
        for (i in tokens.indices) out[i] = tokens[i].toInt(16).toByte()
        return out
    }

    /** 从整 IP 报文取 UDP 载荷:`ihl=(b[0]&0x0F)*4`,载荷=`b[ihl+8 until end]`。不合法返回 null。 */
    fun ipUdpPayload(datagram: ByteArray): ByteArray? {
        if (datagram.size < 28) return null
        val ihl = (datagram[0].toInt() and 0x0F) * 4
        val start = ihl + 8
        if (ihl < 20 || start >= datagram.size) return null
        return datagram.copyOfRange(start, datagram.size)
    }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /**
     * 读取 assets 录像并**循环**回放到 [router]。先置链路 CONNECTED(`AllowReceived`),每轮以
     * `System.currentTimeMillis()` 为 now 喂全部包,轮间隔 [loopDelayMs]。协程取消即停。单包异常不致命。
     */
    suspend fun run(router: CommsRouter, assets: AssetManager, loopDelayMs: Long = 1000) {
        val text = withContext(Dispatchers.IO) {
            assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }
        val packets = parseCapture(text)
        router.applyLinkEvent(LinkEvent.AllowReceived)

        while (coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            for ((ep, payload) in packets) {
                try {
                    when (channelOf(ep.port)) {
                        ReplayChannel.IMAGE -> router.onHaloImage(payload, now)
                        ReplayChannel.STATUS -> router.onHaloStatus(payload, now)
                        ReplayChannel.TARGET -> router.onHaloTarget(payload, now)
                        ReplayChannel.UNKNOWN -> Unit
                    }
                } catch (_: Exception) {
                    // 单个坏包不应中断整段回放。
                }
            }
            router.applyLinkEvent(LinkEvent.AllowReceived) // 保持链路 CONNECTED(防 supervisor 判超时)
            delay(loopDelayMs)
        }
    }
}
