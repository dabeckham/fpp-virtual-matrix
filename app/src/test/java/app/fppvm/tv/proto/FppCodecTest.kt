package app.fppvm.tv.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for FPP's MultiSync control protocol.
 *
 * These assert the exact byte layout the C++ structs in `src/MultiSync.h` produce on a
 * little-endian host, field offset by field offset. A "looks reasonable" decoder that has the
 * frame number two bytes out will follow a show almost correctly and be impossible to debug from
 * the picture, so the byte positions are pinned here rather than inferred at runtime.
 *
 * The golden packets below are built from the struct definitions by hand — they are the
 * specification this port is written against. They are not a substitute for capturing traffic
 * from a live player, which is the check that proves the specification was read correctly.
 */
class FppCodecTest {

    private fun syncPacketBytes(
        op: Int,
        fileType: Int,
        frame: Int,
        seconds: Float,
        name: String
    ): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val extra = 11 + nameBytes.size
        val out = ByteArray(7 + extra)
        out[0] = 'F'.code.toByte(); out[1] = 'P'.code.toByte()
        out[2] = 'P'.code.toByte(); out[3] = 'D'.code.toByte()
        out[4] = 1 // CTRL_PKT_SYNC
        out[5] = (extra and 0xFF).toByte()
        out[6] = ((extra ushr 8) and 0xFF).toByte()
        out[7] = op.toByte()
        out[8] = fileType.toByte()
        var v = frame
        for (i in 0..3) {
            out[9 + i] = (v and 0xFF).toByte(); v = v ushr 8
        }
        var f = seconds.toRawBits()
        for (i in 0..3) {
            out[13 + i] = (f and 0xFF).toByte(); f = f ushr 8
        }
        System.arraycopy(nameBytes, 0, out, 17, nameBytes.size)
        return out
    }

    @Test
    fun `control header is seven bytes with a little-endian length`() {
        val pkt = syncPacketBytes(2, 0, 1, 0.05f, "x.fseq")
        val h = FppCodec.parseHeader(pkt, pkt.size)!!
        assertEquals(FppProtocol.PKT_SYNC, h.pktType)
        assertEquals(11 + 6, h.extraDataLen)
    }

    @Test
    fun `non-FPPD payloads are rejected rather than misparsed`() {
        val junk = ByteArray(64) { 0x41 }
        assertNull(FppCodec.parseHeader(junk, junk.size))
        assertNull(FppCodec.parseHeader(ByteArray(3), 3))
    }

    @Test
    fun `a truncated datagram never reads past what actually arrived`() {
        val pkt = syncPacketBytes(2, 0, 900, 45.0f, "show.fseq")
        // Claim the full length but deliver only part of it, as a clipped datagram would.
        val h = FppCodec.parseHeader(pkt, 20)!!
        assertEquals(20 - 7, h.extraDataLen)
    }

    @Test
    fun `sync packet fields decode at the documented offsets`() {
        val pkt = syncPacketBytes(
            op = FppProtocol.SYNC_PKT_SYNC,
            fileType = FppProtocol.SYNC_FILE_SEQ,
            frame = 1234,
            seconds = 61.7f,
            name = "Wizards In Winter.fseq"
        )
        val h = FppCodec.parseHeader(pkt, pkt.size)!!
        val s = FppCodec.parseSync(pkt, pkt.size, h.extraDataLen)!!
        assertEquals(FppProtocol.SYNC_PKT_SYNC, s.operation)
        assertEquals(FppProtocol.SYNC_FILE_SEQ, s.fileType)
        assertEquals(1234, s.frameNumber)
        assertEquals(61.7f, s.secondsElapsed, 0.0001f)
        assertEquals("Wizards In Winter.fseq", s.filename)
    }

    @Test
    fun `our sync encoder round-trips through the hand-built golden layout`() {
        val golden = syncPacketBytes(FppProtocol.SYNC_PKT_START, 0, 0, 0f, "a.fseq")
        val ours = FppCodec.buildSync(FppProtocol.SYNC_PKT_START, 0, 0, 0f, "a.fseq")
        assertArrayEquals(golden, ours)
    }

    @Test
    fun `sync filenames survive spaces and are NUL terminated`() {
        val ours = FppCodec.buildSync(FppProtocol.SYNC_PKT_SYNC, 0, 7, 0.35f, "Deck The Halls.fseq")
        // 7 header + 11 struct + 19 name = 37; the last byte is the terminator.
        assertEquals(7 + 11 + 19, ours.size)
        assertEquals(0, ours[ours.size - 1].toInt())
        val h = FppCodec.parseHeader(ours, ours.size)!!
        assertEquals("Deck The Halls.fseq", FppCodec.parseSync(ours, ours.size, h.extraDataLen)!!.filename)
    }

    @Test
    fun `ping v3 is 301 bytes and every advertised field lands where FPP reads it`() {
        val id = FppCodec.Identity(
            hostname = "fppvm-livingroom",
            version = "FPPVM 0.1.0",
            model = "Android TV Virtual Matrix",
            ranges = "0-9216",
            ipv4 = "192.168.55.19"
        )
        val pkt = FppCodec.buildPing(id, discover = false)
        assertEquals(FppProtocol.PING_V3_PACKET_LEN, pkt.size)
        assertEquals(301, pkt.size)

        val h = FppCodec.parseHeader(pkt, pkt.size)!!
        assertEquals(FppProtocol.PKT_PING, h.pktType)
        assertEquals(294, h.extraDataLen)

        // The offsets FPP's ProcessPingPacket() reads, checked directly against the buffer.
        val e = FppProtocol.HEADER_SIZE
        assertEquals(3, pkt[e].toInt())        // ping version
        assertEquals(0, pkt[e + 1].toInt())    // discover flag
        assertEquals(FppProtocol.SYS_TYPE_FPP, pkt[e + 2].toInt())
        assertEquals(192, pkt[e + 8].toInt() and 0xFF)
        assertEquals(168, pkt[e + 9].toInt() and 0xFF)
        assertEquals(55, pkt[e + 10].toInt() and 0xFF)
        assertEquals(19, pkt[e + 11].toInt() and 0xFF)

        val p = FppCodec.parsePing(pkt, pkt.size, h.extraDataLen, "192.168.55.19")!!
        assertEquals(3, p.pingVersion)
        assertFalse(p.isDiscover)
        assertEquals("fppvm-livingroom", p.hostname)
        assertEquals("FPPVM 0.1.0", p.version)
        assertEquals("Android TV Virtual Matrix", p.model)
        assertEquals("0-9216", p.ranges)
        assertEquals("192.168.55.19", p.address)
    }

    @Test
    fun `version numbers are big-endian in the ping body`() {
        // FPP packs these by hand as (v >> 8), (v & 0xFF) — the one place the protocol is NOT
        // little-endian, and an easy field to get backwards.
        val pkt = FppCodec.buildPing(
            FppCodec.Identity("h", "v", "m", "", "1.2.3.4", majorVersion = 0x0105),
            discover = true
        )
        val e = FppProtocol.HEADER_SIZE
        assertEquals(0x01, pkt[e + 3].toInt())
        assertEquals(0x05, pkt[e + 4].toInt())
        assertEquals(1, pkt[e + 1].toInt()) // discover
    }

    @Test
    fun `we advertise as a unicast-eligible remote`() {
        // MultiSyncSystem::update() sets supportsUnicast = type < 0x80 && mode == REMOTE_MODE.
        // Fail this and a player configured for unicast MultiSync silently skips us.
        val id = FppCodec.Identity("h", "v", "m", "", "1.2.3.4")
        assertTrue(id.systemType < 0x80)
        assertEquals(FppProtocol.MODE_REMOTE, id.fppMode)
    }

    @Test
    fun `a ping with a zero address falls back to the datagram source`() {
        // xLights probes with 0.0.0.0 to get a listing; FPP treats that as "not an instance".
        val id = FppCodec.Identity("probe", "x", "y", "", "0.0.0.0")
        val pkt = FppCodec.buildPing(id, discover = true)
        val h = FppCodec.parseHeader(pkt, pkt.size)!!
        val p = FppCodec.parsePing(pkt, pkt.size, h.extraDataLen, "10.0.0.7")!!
        assertEquals("10.0.0.7", p.address)
    }

    @Test
    fun `strings longer than their field are truncated instead of overrunning`() {
        val long = "h".repeat(200)
        val pkt = FppCodec.buildPing(
            FppCodec.Identity(long, long, long, long, "1.1.1.1"),
            discover = false
        )
        assertEquals(FppProtocol.PING_V3_PACKET_LEN, pkt.size)
        val h = FppCodec.parseHeader(pkt, pkt.size)!!
        val p = FppCodec.parsePing(pkt, pkt.size, h.extraDataLen, "1.1.1.1")!!
        assertEquals(64, p.hostname.length)
        assertEquals(40, p.version.length)
        assertEquals(40, p.model.length)
        assertEquals(120, p.ranges.length)
    }
}
