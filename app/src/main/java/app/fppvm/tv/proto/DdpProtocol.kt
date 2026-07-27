package app.fppvm.tv.proto

/**
 * Distributed Display Protocol — the channel-data path xLights uses for live output.
 *
 * MultiSync tells this device *which* sequence to play and where the master is up to; DDP is the
 * other half, where a sequencer pushes pixels straight at the panel with no file involved. That is
 * what makes the matrix usable while you are still building a sequence, rather than only after it
 * has been rendered and copied.
 *
 * ```
 *   Header (10 bytes, 14 with a timecode)
 *     [0]      u8   flags1: version in bits 7-6, then TIMECODE/STORAGE/REPLY/QUERY/PUSH
 *     [1]      u8   flags2: sequence number in the low nibble, 0 meaning "not sequenced"
 *     [2]      u8   data type
 *     [3]      u8   destination id — 1 display, 250 config, 251 status, 255 all
 *     [4..7]   u32  offset into the destination's data, BIG-endian
 *     [8..9]   u16  length of the data that follows, BIG-endian
 *     [10..13] u32  timecode, only when the TIMECODE flag is set
 * ```
 *
 * Note the byte order: DDP is big-endian throughout, the exact opposite of the MultiSync structs
 * next door in [FppProtocol], which are the host's native little-endian.
 */
object DdpProtocol {
    const val PORT = 4048

    const val HEADER_SIZE = 10
    const val TIMECODE_SIZE = 4

    // flags1
    const val FLAG_VERSION_MASK = 0xC0
    const val FLAG_VERSION_1 = 0x40
    const val FLAG_TIMECODE = 0x10
    const val FLAG_STORAGE = 0x08
    const val FLAG_REPLY = 0x04
    const val FLAG_QUERY = 0x02
    const val FLAG_PUSH = 0x01

    // destination ids
    const val ID_DISPLAY = 1
    const val ID_CONTROL = 246
    const val ID_CONFIG = 250
    const val ID_STATUS = 251
    const val ID_DMX_TRANSIT = 254
    const val ID_ALL_DEVICES = 255

    /** Largest payload a sender will put in one datagram, plus room for the header. */
    const val MAX_PACKET = HEADER_SIZE + TIMECODE_SIZE + 1440
}

/** A decoded DDP header. [dataStart] already accounts for an optional timecode. */
data class DdpPacket(
    val flags: Int,
    val sequence: Int,
    val dataType: Int,
    val destinationId: Int,
    val offset: Int,
    val dataLength: Int,
    val dataStart: Int
) {
    val isPush: Boolean get() = (flags and DdpProtocol.FLAG_PUSH) != 0
    val isQuery: Boolean get() = (flags and DdpProtocol.FLAG_QUERY) != 0
    val isReply: Boolean get() = (flags and DdpProtocol.FLAG_REPLY) != 0

    /** True for the packets that carry pixels for us, as opposed to a probe or someone's answer. */
    val isDisplayData: Boolean
        get() = !isQuery && !isReply &&
            (destinationId == DdpProtocol.ID_DISPLAY || destinationId == DdpProtocol.ID_ALL_DEVICES)
}

object DdpCodec {

    fun u16be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun u32be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    fun putU16be(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    /**
     * Returns the header if [buf] holds a well-formed DDP packet, else null.
     *
     * [DdpPacket.dataLength] is clamped to what actually arrived. A sender that declares more than
     * it sends is either buggy or hostile, and either way the declared figure must never be used
     * to size a read.
     */
    fun parse(buf: ByteArray, len: Int): DdpPacket? {
        if (len < DdpProtocol.HEADER_SIZE) return null
        val flags = buf[0].toInt() and 0xFF
        if ((flags and DdpProtocol.FLAG_VERSION_MASK) != DdpProtocol.FLAG_VERSION_1) return null

        val dataStart = DdpProtocol.HEADER_SIZE +
            if ((flags and DdpProtocol.FLAG_TIMECODE) != 0) DdpProtocol.TIMECODE_SIZE else 0
        if (dataStart > len) return null

        val declared = u16be(buf, 8)
        val available = len - dataStart
        return DdpPacket(
            flags = flags,
            sequence = buf[1].toInt() and 0x0F,
            dataType = buf[2].toInt() and 0xFF,
            destinationId = buf[3].toInt() and 0xFF,
            offset = u32be(buf, 4),
            dataLength = if (declared < available) declared else available,
            dataStart = dataStart
        )
    }

    /**
     * Builds the answer to a STATUS query.
     *
     * The payload is JSON and the reader treats it as a C string, so it is NUL-terminated — xLights
     * parses from the first data byte to the terminator. `man` and `mod` become the Vendor and
     * Model columns on its Controllers tab, which is why they are the FPP names rather than ours:
     * "FPP" and "Virtual Matrix" resolve against a capability entry xLights already ships.
     *
     * The QUERY bit must be clear, or the sender discards this as an echo of its own probe.
     */
    fun buildStatusReply(manufacturer: String, model: String, version: String, mac: String = ""): ByteArray {
        val json = buildString {
            append("{\"status\":{\"man\":\"").append(escape(manufacturer))
            append("\",\"mod\":\"").append(escape(model))
            append("\",\"ver\":\"").append(escape(version))
            if (mac.isNotEmpty()) append("\",\"mac\":\"").append(escape(mac))
            append("\",\"push\":true}}")
        }
        val body = json.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(DdpProtocol.HEADER_SIZE + body.size + 1)
        out[0] = (DdpProtocol.FLAG_VERSION_1 or DdpProtocol.FLAG_REPLY).toByte()
        out[3] = DdpProtocol.ID_STATUS.toByte()
        putU16be(out, 8, body.size + 1)
        System.arraycopy(body, 0, out, DdpProtocol.HEADER_SIZE, body.size)
        // out[last] is already 0 => the terminator the reader expects.
        return out
    }

    private fun escape(s: String): String = s.replace("\\", "").replace("\"", "")
}
