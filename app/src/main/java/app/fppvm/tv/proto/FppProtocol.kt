package app.fppvm.tv.proto

/**
 * Wire constants and packet codecs for FPP's MultiSync control protocol.
 *
 * Ported from FPP `src/MultiSync.h` / `src/MultiSync.cpp` (LGPL v2.1). The C++ structs are
 * `__attribute__((packed))` and written with the host's native byte order, which on every
 * platform FPP runs on (Pi, BBB, x86, macOS) is little-endian — so every multi-byte field
 * here is little-endian, NOT network order. Getting that wrong is the classic way to make a
 * remote that looks correct and never syncs.
 *
 *   ControlPkt (7 bytes, packed)
 *     [0..3]  'F','P','P','D'
 *     [4]     u8   packet type
 *     [5..6]  u16  extraDataLen (length of everything after this header)
 *
 *   SyncPkt (11 bytes + filename, packed) — follows ControlPkt at offset 7
 *     [0]      u8   sync packet type   (start/stop/sync/open)
 *     [1]      u8   file type          (seq/media)
 *     [2..5]   u32  frame number on the master
 *     [6..9]   f32  seconds elapsed on the master
 *     [10..]   char NUL-terminated filename
 */
object FppProtocol {
    const val CTRL_PORT = 32320
    const val MULTICAST_ADDRESS = "239.70.80.80" // 239.F.P.P
    const val HEADER_SIZE = 7

    val MAGIC = byteArrayOf('F'.code.toByte(), 'P'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte())

    // Control packet types (MultiSync.h)
    const val PKT_CMD = 0 // deprecated in FPP in favour of FPP Commands
    const val PKT_SYNC = 1
    const val PKT_EVENT = 2 // deprecated
    const val PKT_BLANK = 3
    const val PKT_PING = 4
    const val PKT_PLUGIN = 5
    const val PKT_FPPCOMMAND = 6

    // Sync packet operations
    const val SYNC_PKT_START = 0
    const val SYNC_PKT_STOP = 1
    const val SYNC_PKT_SYNC = 2
    const val SYNC_PKT_OPEN = 3

    // Sync packet file types
    const val SYNC_FILE_SEQ = 0
    const val SYNC_FILE_MEDIA = 1

    // FPPMode bit values (settings.h)
    const val MODE_BRIDGE = 0x01
    const val MODE_PLAYER = 0x02
    const val MODE_REMOTE = 0x08

    /**
     * MultiSyncSystemType. We advertise [SYS_TYPE_FPP] ("FPP") because FPP only offers unicast
     * MultiSync to systems whose type is < 0x80 *and* whose mode is REMOTE — see
     * `MultiSyncSystem::update()`. Anything in the 0x80+ range (Falcon, ESPixelStick, WLED…) is
     * excluded from that path, so an honest-looking `kSysTypeOtherSystem` would silently drop us
     * out of unicast shows. The model string carries the truth about what this actually is.
     */
    const val SYS_TYPE_FPP = 0x01
    const val SYS_TYPE_OTHER = 0xC0

    const val PING_V3_EXTRA_LEN = 294
    const val PING_V3_PACKET_LEN = HEADER_SIZE + PING_V3_EXTRA_LEN // 301

    // Field offsets inside the ping v3 "extra data" block (i.e. relative to packet offset 7).
    const val PING_OFF_VERSION = 0
    const val PING_OFF_DISCOVER = 1
    const val PING_OFF_TYPE = 2
    const val PING_OFF_MAJOR = 3 // 2 bytes, BIG-endian (hand-packed byte pair in FPP)
    const val PING_OFF_MINOR = 5 // 2 bytes, big-endian
    const val PING_OFF_MODE = 7
    const val PING_OFF_IP = 8 // 4 bytes
    const val PING_OFF_HOSTNAME = 12 // max 64 chars
    const val PING_OFF_VERSION_STR = 77 // max 40 chars
    const val PING_OFF_MODEL = 118 // max 40 chars
    const val PING_OFF_RANGES = 159 // max 120 chars

    /** Set on the mode byte when the sender is itself sending MultiSync (i.e. is a master). */
    const val MODE_FLAG_SENDING_MULTISYNC = 0x04
}

/** A decoded FPP control-packet header. */
data class ControlHeader(val pktType: Int, val extraDataLen: Int)

/** A decoded MultiSync sync packet. */
data class SyncPacket(
    val operation: Int,
    val fileType: Int,
    val frameNumber: Int,
    val secondsElapsed: Float,
    val filename: String
)

/** A decoded MultiSync ping/discovery packet. */
data class PingPacket(
    val pingVersion: Int,
    val isDiscover: Boolean,
    val systemType: Int,
    val majorVersion: Int,
    val minorVersion: Int,
    val fppMode: Int,
    val address: String,
    val hostname: String,
    val version: String,
    val model: String,
    val ranges: String
) {
    val isSendingMultiSync: Boolean get() = (fppMode and FppProtocol.MODE_FLAG_SENDING_MULTISYNC) != 0
    val isPlayer: Boolean get() = (fppMode and FppProtocol.MODE_PLAYER) != 0
}

/**
 * Pure, allocation-light codec for the packets a remote needs. Kept free of Android types so the
 * whole wire format is exercised by JVM unit tests.
 */
object FppCodec {

    fun u16le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun u32le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun f32le(b: ByteArray, off: Int): Float = Float.fromBits(u32le(b, off))

    fun putU16le(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun putU32le(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /** Reads a NUL-terminated ASCII string, stopping at [max] bytes or the end of [b]. */
    fun cString(b: ByteArray, off: Int, max: Int): String {
        if (off >= b.size) return ""
        var end = off
        val limit = minOf(b.size, off + max)
        while (end < limit && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.US_ASCII)
    }

    /** Writes [s] as ASCII at [off], NUL-terminated, truncated to [max] characters. */
    fun putCString(b: ByteArray, off: Int, s: String, max: Int) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        val n = minOf(bytes.size, max)
        System.arraycopy(bytes, 0, b, off, n)
        b[off + n] = 0
    }

    /** Returns the header if [buf] is a well-formed FPPD control packet, else null. */
    fun parseHeader(buf: ByteArray, len: Int): ControlHeader? {
        if (len < FppProtocol.HEADER_SIZE) return null
        for (i in 0..3) if (buf[i] != FppProtocol.MAGIC[i]) return null
        val extra = u16le(buf, 5)
        // Trust the shorter of the declared and actual lengths: FPP's own sync sender declares
        // sizeof(SyncPkt)+strlen(name) but transmits exactly that many bytes, so they agree —
        // a mismatch means a truncated datagram and we must not read past what arrived.
        val usable = minOf(extra, len - FppProtocol.HEADER_SIZE)
        if (usable < 0) return null
        return ControlHeader(buf[4].toInt() and 0xFF, usable)
    }

    fun parseSync(buf: ByteArray, len: Int, extraDataLen: Int): SyncPacket? {
        val base = FppProtocol.HEADER_SIZE
        // 11 = sizeof(SyncPkt): pktType, fileType, u32 frame, f32 seconds, 1 byte of filename.
        if (extraDataLen < 11 || base + 11 > len) return null
        val nameMax = extraDataLen - 10
        return SyncPacket(
            operation = buf[base].toInt() and 0xFF,
            fileType = buf[base + 1].toInt() and 0xFF,
            frameNumber = u32le(buf, base + 2),
            secondsElapsed = f32le(buf, base + 6),
            filename = cString(buf, base + 10, nameMax)
        )
    }

    fun parsePing(buf: ByteArray, len: Int, extraDataLen: Int, sourceIp: String): PingPacket? {
        // v1 packets are 169 bytes of extra data; anything shorter is malformed.
        if (extraDataLen < 169 || FppProtocol.HEADER_SIZE + 169 > len) return null
        val e = FppProtocol.HEADER_SIZE
        val a = buf[e + FppProtocol.PING_OFF_IP].toInt() and 0xFF
        val b = buf[e + FppProtocol.PING_OFF_IP + 1].toInt() and 0xFF
        val c = buf[e + FppProtocol.PING_OFF_IP + 2].toInt() and 0xFF
        val d = buf[e + FppProtocol.PING_OFF_IP + 3].toInt() and 0xFF
        // FPP sends 0.0.0.0 from tools (xLights) that only want a listing back; fall back to the
        // datagram's source address so we still know who to talk to.
        val address = if (a or b or c or d == 0) sourceIp else "$a.$b.$c.$d"
        // The ranges field only exists in v2+ (extra data longer than the v1 169 bytes).
        val ranges = if (extraDataLen > 169) {
            cString(buf, e + FppProtocol.PING_OFF_RANGES, 120)
        } else {
            ""
        }
        return PingPacket(
            pingVersion = buf[e + FppProtocol.PING_OFF_VERSION].toInt() and 0xFF,
            isDiscover = buf[e + FppProtocol.PING_OFF_DISCOVER].toInt() != 0,
            systemType = buf[e + FppProtocol.PING_OFF_TYPE].toInt() and 0xFF,
            majorVersion = ((buf[e + FppProtocol.PING_OFF_MAJOR].toInt() and 0xFF) shl 8) or
                (buf[e + FppProtocol.PING_OFF_MAJOR + 1].toInt() and 0xFF),
            minorVersion = ((buf[e + FppProtocol.PING_OFF_MINOR].toInt() and 0xFF) shl 8) or
                (buf[e + FppProtocol.PING_OFF_MINOR + 1].toInt() and 0xFF),
            fppMode = buf[e + FppProtocol.PING_OFF_MODE].toInt() and 0xFF,
            address = address,
            hostname = cString(buf, e + FppProtocol.PING_OFF_HOSTNAME, 65),
            version = cString(buf, e + FppProtocol.PING_OFF_VERSION_STR, 41),
            model = cString(buf, e + FppProtocol.PING_OFF_MODEL, 41),
            ranges = ranges
        )
    }

    /** Identity this device advertises to the rest of the show. */
    data class Identity(
        val hostname: String,
        val version: String,
        val model: String,
        /** "start-count" pairs, comma separated, exactly as FPP formats them. */
        val ranges: String,
        val ipv4: String,
        val systemType: Int = FppProtocol.SYS_TYPE_FPP,
        val majorVersion: Int = 8,
        val minorVersion: Int = 0,
        val fppMode: Int = FppProtocol.MODE_REMOTE
    )

    /**
     * Builds a ping v3 packet (301 bytes). [discover] true asks everyone else to announce
     * themselves; false is the plain "I am here" announcement that puts us in a player's
     * MultiSync remote list.
     */
    fun buildPing(id: Identity, discover: Boolean): ByteArray {
        val out = ByteArray(FppProtocol.PING_V3_PACKET_LEN)
        System.arraycopy(FppProtocol.MAGIC, 0, out, 0, 4)
        out[4] = FppProtocol.PKT_PING.toByte()
        putU16le(out, 5, FppProtocol.PING_V3_EXTRA_LEN)

        val e = FppProtocol.HEADER_SIZE
        out[e + FppProtocol.PING_OFF_VERSION] = 3
        out[e + FppProtocol.PING_OFF_DISCOVER] = if (discover) 1 else 0
        out[e + FppProtocol.PING_OFF_TYPE] = id.systemType.toByte()
        out[e + FppProtocol.PING_OFF_MAJOR] = ((id.majorVersion ushr 8) and 0xFF).toByte()
        out[e + FppProtocol.PING_OFF_MAJOR + 1] = (id.majorVersion and 0xFF).toByte()
        out[e + FppProtocol.PING_OFF_MINOR] = ((id.minorVersion ushr 8) and 0xFF).toByte()
        out[e + FppProtocol.PING_OFF_MINOR + 1] = (id.minorVersion and 0xFF).toByte()
        out[e + FppProtocol.PING_OFF_MODE] = id.fppMode.toByte()

        val octets = id.ipv4.split(".")
        if (octets.size == 4) {
            for (i in 0..3) {
                out[e + FppProtocol.PING_OFF_IP + i] = (octets[i].toIntOrNull() ?: 0).toByte()
            }
        }

        putCString(out, e + FppProtocol.PING_OFF_HOSTNAME, id.hostname, 64)
        putCString(out, e + FppProtocol.PING_OFF_VERSION_STR, id.version, 40)
        putCString(out, e + FppProtocol.PING_OFF_MODEL, id.model, 40)
        putCString(out, e + FppProtocol.PING_OFF_RANGES, id.ranges, 120)
        return out
    }

    /**
     * Builds a sync packet. Only needed to drive our own integration tests and the
     * "act as master" mode, but it lives here so encode and decode stay in one place.
     */
    fun buildSync(
        operation: Int,
        fileType: Int,
        frameNumber: Int,
        secondsElapsed: Float,
        filename: String
    ): ByteArray {
        val nameBytes = filename.toByteArray(Charsets.US_ASCII)
        // FPP declares sizeof(SyncPkt) + strlen(filename) and sends exactly that many bytes; the
        // struct's trailing filename[1] is what holds the NUL terminator.
        val extra = 11 + nameBytes.size
        val out = ByteArray(FppProtocol.HEADER_SIZE + extra)
        System.arraycopy(FppProtocol.MAGIC, 0, out, 0, 4)
        out[4] = FppProtocol.PKT_SYNC.toByte()
        putU16le(out, 5, extra)

        val b = FppProtocol.HEADER_SIZE
        out[b] = operation.toByte()
        out[b + 1] = fileType.toByte()
        putU32le(out, b + 2, frameNumber)
        putU32le(out, b + 6, secondsElapsed.toRawBits())
        System.arraycopy(nameBytes, 0, out, b + 10, nameBytes.size)
        // out[b + 10 + nameBytes.size] is already 0 => NUL terminator.
        return out
    }
}
