package app.fppvm.tv.proto

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Events from [DdpReceiver]. Delivered on the receive thread, so handlers must not block. */
interface DdpListener {
    /**
     * Channel data arrived. [offset] is a zero-based absolute channel index — the same coordinate
     * space FPP and this app's start channel use.
     */
    fun onDdpData(offset: Int, buf: ByteArray, start: Int, length: Int, sourceIp: String)

    /** The sender finished a frame and wants it shown. */
    fun onDdpPush(sourceIp: String)

    /** Delivered once per frame rather than once per packet — see [DdpReceiver.snapshot]. */
    fun onDdpStats(stats: DdpStats)
}

data class DdpStats(
    val packets: Int = 0,
    val pushes: Int = 0,
    val queries: Int = 0,
    val bytes: Long = 0,
    val malformed: Int = 0,
    val lastPacketAtMs: Long = 0L,
    val lastSender: String = ""
)

/**
 * Listens for DDP channel data and answers discovery probes.
 *
 * Two jobs, and the second is not obvious. Answering a STATUS query is also how this device gets a
 * vendor and model onto xLights' Controllers tab: xLights takes those straight from the reply.
 * The MultiSync ping cannot do it — for anything identifying as a full FPP instance xLights fills
 * those columns from HTTP on port 80, and an Android app cannot bind a privileged port.
 *
 * A CONFIG query is deliberately *not* answered. The reader's config branch calls nlohmann's
 * static `array()` factory rather than reading the ports array it just checked for, so the loop
 * never runs and it ends up calling `SetChannels(0)` — overwriting the correct channel count the
 * MultiSync ping already established. Staying quiet leaves the good value in place.
 */
class DdpReceiver(
    private val identity: () -> Identity,
    private val listener: DdpListener
) {
    /** What this device answers a STATUS probe with. */
    data class Identity(
        val manufacturer: String,
        val model: String,
        val version: String,
        val mac: String = ""
    )

    companion object {
        private const val TAG = "FppDdp"

        /**
         * Generous enough for a jumbo frame. Senders default to 1440 bytes of payload, but the
         * figure is configurable and a truncated datagram would silently corrupt part of a row.
         */
        private const val RCV_BUF = 9216
    }

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    /**
     * Counters rather than a snapshot object.
     *
     * A full matrix is hundreds of packets per frame at up to 40 frames a second, so allocating a
     * new stats record for each one — and calling back for each one — would cost tens of thousands
     * of short-lived objects a second on a device whose whole job is to keep painting on time.
     * Only the receive thread writes them; volatile is for the readers.
     */
    @Volatile private var nPackets = 0
    @Volatile private var nPushes = 0
    @Volatile private var nQueries = 0
    @Volatile private var nBytes = 0L
    @Volatile private var nMalformed = 0
    @Volatile private var lastAtMs = 0L
    @Volatile private var lastSender = ""

    private fun snapshot(): DdpStats =
        DdpStats(nPackets, nPushes, nQueries, nBytes, nMalformed, lastAtMs, lastSender)

    fun currentStats(): DdpStats = snapshot()

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val sock = try {
            // Unbound first, so address reuse is set before the bind — same reason the MultiSync
            // socket does it: several listeners may share the port on a busy box.
            DatagramSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(DdpProtocol.PORT))
                broadcast = true
                soTimeout = 1000
                // Live output is a stream of full-matrix frames; a shallow buffer drops rows
                // whenever the playback thread is mid-paint.
                receiveBufferSize = 512 * 1024
            }
        } catch (t: Throwable) {
            Log.e(TAG, "could not bind UDP ${DdpProtocol.PORT}", t)
            running.set(false)
            return
        }
        socket = sock
        thread = Thread({ receiveLoop(sock) }, "fppvm-ddp-rx").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "listening for DDP on ${DdpProtocol.PORT}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        thread = null
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(RCV_BUF)
        val pkt = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try {
                pkt.setData(buf, 0, buf.size)
                sock.receive(pkt)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "receive error: ${t.message}")
                continue
            }
            val src = (pkt.address as? Inet4Address)?.hostAddress ?: pkt.address?.hostAddress ?: ""
            handle(sock, buf, pkt.length, src, pkt.port)
        }
    }

    private fun handle(sock: DatagramSocket, buf: ByteArray, len: Int, srcIp: String, srcPort: Int) {
        val p = DdpCodec.parse(buf, len)
        if (p == null) {
            nMalformed++
            return
        }
        lastAtMs = System.currentTimeMillis()
        lastSender = srcIp

        if (p.isQuery) {
            nQueries++
            if (p.destinationId == DdpProtocol.ID_STATUS || p.destinationId == DdpProtocol.ID_ALL_DEVICES) {
                replyStatus(sock, srcIp, srcPort)
            }
            listener.onDdpStats(snapshot())
            return
        }
        if (p.isReply) return // another device answering someone else's probe
        nPackets++
        if (!p.isDisplayData) return

        nBytes += p.dataLength
        if (p.dataLength > 0) {
            listener.onDdpData(p.offset, buf, p.dataStart, p.dataLength, srcIp)
        }
        if (p.isPush) {
            nPushes++
            // Once a frame, which is where the reporting cost belongs.
            listener.onDdpStats(snapshot())
            listener.onDdpPush(srcIp)
        }
    }

    private fun replyStatus(sock: DatagramSocket, ip: String, port: Int) {
        try {
            val id = identity()
            val data = DdpCodec.buildStatusReply(id.manufacturer, id.model, id.version, id.mac)
            // Senders listen on the DDP port rather than on an ephemeral source port, so answer
            // there; falling back to the observed port keeps a stricter sender working too.
            val dest = if (port in 1..65535) port else DdpProtocol.PORT
            sock.send(DatagramPacket(data, data.size, java.net.InetAddress.getByName(ip), dest))
        } catch (t: Throwable) {
            Log.w(TAG, "status reply to $ip failed: ${t.message}")
        }
    }

}
