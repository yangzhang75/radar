package com.shipradar.halofeed

import com.shipradar.constants.Endpoint
import com.shipradar.constants.HaloEndpoints
import com.shipradar.constants.Iec450Groups
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Collects sent datagrams instead of touching the network. */
private class FakeTransport : Transport {
    val sent = ArrayList<Datagram>()
    override fun send(d: Datagram) { sent.add(Datagram(d.endpoint, d.payload.copyOf())) }
    override fun close() {}
}

class RecordReplayTest {

    @Test fun writerReaderRoundTrip() {
        val entries = listOf(
            RecordEntry(0, HaloEndpoints.IMAGE, byteArrayOf(1, 2, 3)),
            RecordEntry(1500, Iec450Groups.NAVD, "\$GPGGA,x*00\r\n".toByteArray()),
            RecordEntry(4200, HaloEndpoints.STATUS, ByteArray(18) { it.toByte() }),
        )
        val buf = ByteArrayOutputStream()
        RecordWriter(buf).use { w -> entries.forEach { w.append(it) } }
        val back = RecordReader(ByteArrayInputStream(buf.toByteArray())).use { it.readAll() }
        assertEquals(entries, back)
    }

    @Test fun rejectsNonRecording() {
        val bad = ByteArrayInputStream("not a recording".toByteArray())
        assertTrue(runCatching { RecordReader(bad) }.isFailure)
    }

    @Test fun replayResendsAllDatagramsInOrder() {
        val buf = ByteArrayOutputStream()
        RecordWriter(buf).use { w ->
            w.append(RecordEntry(0, HaloEndpoints.IMAGE, byteArrayOf(9)))
            w.append(RecordEntry(1000, HaloEndpoints.TARGET, byteArrayOf(8, 7)))
        }
        val out = FakeTransport()
        val n = replay(RecordReader(ByteArrayInputStream(buf.toByteArray())), out, paced = false)
        assertEquals(2, n)
        assertEquals(HaloEndpoints.IMAGE, out.sent[0].endpoint)
        assertContentEquals(byteArrayOf(8, 7), out.sent[1].payload)
    }

    @Test fun pacedReplaySleepsByDelta() {
        val buf = ByteArrayOutputStream()
        RecordWriter(buf).use { w ->
            w.append(RecordEntry(0, HaloEndpoints.IMAGE, byteArrayOf(1)))
            w.append(RecordEntry(2_000, HaloEndpoints.IMAGE, byteArrayOf(2)))
            w.append(RecordEntry(5_000, HaloEndpoints.IMAGE, byteArrayOf(3)))
        }
        val waits = ArrayList<Long>()
        replay(RecordReader(ByteArrayInputStream(buf.toByteArray())), FakeTransport(), paced = true) { waits.add(it) }
        assertEquals(listOf(2_000L, 3_000L), waits) // deltas between successive timestamps (first delta 0 skipped)
    }

    @Test fun endToEndLiveRecordThenReplayIsByteIdentical() {
        // 1 scan, all channels. Constant clock + no-op sleeper => runs instantly & deterministically.
        val cfg = FeedConfig(scans = 1, spokesPerPacket = 2, statusPeriodSec = 0.5,
            targetPeriodSec = 0.5, ownshipPeriodSec = 0.5)

        val buf = ByteArrayOutputStream()
        val live = FakeTransport()
        var tick = 0L
        val recorder = RecordingTransport(live, RecordWriter(buf), clockNanos = { tick += 1_000_000; tick })
        val emitters = emittersFor(cfg)
        runLive(emitters, recorder, primary = emitters.first { it.label == "image" },
            clockNanos = { 0L }, sleeper = {})
        recorder.close()

        // image datagrams: 4096 spokes / 2 per packet = 2048
        val imageCount = live.sent.count { it.endpoint == HaloEndpoints.IMAGE }
        assertEquals(2048, imageCount)
        // every channel represented
        assertTrue(live.sent.any { it.endpoint == HaloEndpoints.STATUS })
        assertTrue(live.sent.any { it.endpoint == HaloEndpoints.TARGET })
        assertTrue(live.sent.any { it.endpoint == Iec450Groups.SATD || it.endpoint == Iec450Groups.NAVD })

        // replay the recording and confirm it reproduces the live stream exactly
        val replayed = FakeTransport()
        val n = replay(RecordReader(ByteArrayInputStream(buf.toByteArray())), replayed, paced = false)
        assertEquals(live.sent.size, n)
        for (i in live.sent.indices) {
            assertEquals(live.sent[i].endpoint, replayed.sent[i].endpoint, "endpoint @$i")
            assertContentEquals(live.sent[i].payload, replayed.sent[i].payload, "payload @$i")
        }
    }

    @Test fun channelTogglesDropChannels() {
        val cfg = FeedConfig(scans = 1, emitStatus = false, emitTarget = false, emitOwnship = false)
        val out = FakeTransport()
        val emitters = emittersFor(cfg)
        runLive(emitters, out, primary = emitters.first { it.label == "image" }, clockNanos = { 0L }, sleeper = {})
        assertTrue(out.sent.all { it.endpoint == HaloEndpoints.IMAGE })
    }
}
