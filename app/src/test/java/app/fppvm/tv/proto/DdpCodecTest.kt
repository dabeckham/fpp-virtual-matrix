package app.fppvm.tv.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offsets here are taken from a sender's own packet builder, not from prose:
 *
 * ```
 *   _data[0] = VER1 (| PUSH on the last packet of a frame)
 *   _data[1] = (_data[1] & 0xF0) + _sequenceNum
 *   _data[4..7] = channel offset, most significant byte first
 *   _data[8..9] = this packet's length, most significant byte first
 *   memcpy(&_data[10], ...)
 * ```
 *
 * Big-endian, which is the opposite of the MultiSync structs — getting that backwards produces a
 * receiver that works perfectly for offsets below 256 and then falls apart.
 */
class DdpCodecTest {

    private fun dataPacket(
        offset: Int,
        payload: ByteArray,
        push: Boolean = true,
        timecode: Boolean = false
    ): ByteArray {
        val head = DdpProtocol.HEADER_SIZE + if (timecode) DdpProtocol.TIMECODE_SIZE else 0
        val out = ByteArray(head + payload.size)
        var f = DdpProtocol.FLAG_VERSION_1
        if (push) f = f or DdpProtocol.FLAG_PUSH
        if (timecode) f = f or DdpProtocol.FLAG_TIMECODE
        out[0] = f.toByte()
        out[1] = 3
        out[3] = DdpProtocol.ID_DISPLAY.toByte()
        out[4] = ((offset ushr 24) and 0xFF).toByte()
        out[5] = ((offset ushr 16) and 0xFF).toByte()
        out[6] = ((offset ushr 8) and 0xFF).toByte()
        out[7] = (offset and 0xFF).toByte()
        DdpCodec.putU16be(out, 8, payload.size)
        System.arraycopy(payload, 0, out, head, payload.size)
        return out
    }

    @Test
    fun `a display packet decodes offset and length big-endian`() {
        val payload = ByteArray(300) { (it and 0xFF).toByte() }
        val pkt = dataPacket(offset = 70_000, payload = payload)
        val p = DdpCodec.parse(pkt, pkt.size)
        assertNotNull(p)
        p!!
        assertEquals(70_000, p.offset)
        assertEquals(300, p.dataLength)
        assertEquals(DdpProtocol.HEADER_SIZE, p.dataStart)
        assertEquals(3, p.sequence)
        assertTrue(p.isPush)
        assertTrue(p.isDisplayData)
    }

    @Test
    fun `a timecode shifts where the data starts`() {
        val payload = ByteArray(12) { 7 }
        val pkt = dataPacket(offset = 0, payload = payload, timecode = true)
        val p = DdpCodec.parse(pkt, pkt.size)!!
        assertEquals(DdpProtocol.HEADER_SIZE + DdpProtocol.TIMECODE_SIZE, p.dataStart)
        assertEquals(12, p.dataLength)
        assertEquals(7, pkt[p.dataStart].toInt())
    }

    @Test
    fun `a declared length longer than the datagram is clamped`() {
        // Trusting the declared figure would read past what arrived.
        val pkt = dataPacket(offset = 0, payload = ByteArray(40))
        DdpCodec.putU16be(pkt, 8, 9000)
        val p = DdpCodec.parse(pkt, pkt.size)!!
        assertEquals(40, p.dataLength)
    }

    @Test
    fun `a sync packet carries no data but still asks for a frame`() {
        // Broadcast at the end of a frame: push set, zero length, nothing after the header.
        val sync = ByteArray(DdpProtocol.HEADER_SIZE)
        sync[0] = (DdpProtocol.FLAG_VERSION_1 or DdpProtocol.FLAG_PUSH).toByte()
        sync[2] = 0x80.toByte()
        sync[3] = DdpProtocol.ID_DISPLAY.toByte()
        val p = DdpCodec.parse(sync, sync.size)!!
        assertTrue(p.isPush)
        assertTrue(p.isDisplayData)
        assertEquals(0, p.dataLength)
    }

    @Test
    fun `a query is not mistaken for pixels`() {
        val q = ByteArray(DdpProtocol.HEADER_SIZE)
        q[0] = (DdpProtocol.FLAG_VERSION_1 or DdpProtocol.FLAG_QUERY).toByte()
        q[3] = DdpProtocol.ID_STATUS.toByte()
        val p = DdpCodec.parse(q, q.size)!!
        assertTrue(p.isQuery)
        assertFalse(p.isDisplayData)
        assertEquals(DdpProtocol.ID_STATUS, p.destinationId)
    }

    @Test
    fun `packets that are not version one are rejected`() {
        val bad = ByteArray(DdpProtocol.HEADER_SIZE)
        bad[0] = 0x80.toByte() // version 2 in the top bits
        assertNull(DdpCodec.parse(bad, bad.size))
        assertNull(DdpCodec.parse(ByteArray(4), 4))
    }

    @Test
    fun `the status reply is a terminated json string a reader can pick vendor and model out of`() {
        val reply = DdpCodec.buildStatusReply("FPP", "Virtual Matrix", "1.4.0")

        // Must not look like a probe, or the sender discards it as an echo of its own.
        assertEquals(0, reply[0].toInt() and DdpProtocol.FLAG_QUERY)
        assertTrue((reply[0].toInt() and DdpProtocol.FLAG_REPLY) != 0)
        assertEquals(DdpProtocol.ID_STATUS, reply[3].toInt() and 0xFF)

        // The reader treats the payload as a C string, so the terminator is load-bearing.
        assertEquals(0, reply[reply.size - 1].toInt())
        val declared = DdpCodec.u16be(reply, 8)
        assertEquals(reply.size - DdpProtocol.HEADER_SIZE, declared)

        val json = String(reply, DdpProtocol.HEADER_SIZE, declared - 1, Charsets.US_ASCII)
        val o = org.json.JSONObject(json).getJSONObject("status")
        assertEquals("FPP", o.getString("man"))
        assertEquals("Virtual Matrix", o.getString("mod"))
        assertEquals("1.4.0", o.getString("ver"))
    }
}
