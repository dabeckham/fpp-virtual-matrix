package app.fppvm.tv.proto

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Events surfaced by [MultiSyncClient]. Delivered on the receive thread — implementations must
 * not block (the socket is the show's timing source; a slow handler is a dropped sync packet).
 */
interface MultiSyncListener {
    fun onSequenceOpen(filename: String, masterIp: String)
    fun onSequenceStart(filename: String, masterIp: String)
    fun onSequenceStop(filename: String, masterIp: String)
    fun onSequenceSync(filename: String, frameNumber: Int, secondsElapsed: Float, masterIp: String)
    fun onBlank(masterIp: String)
    /** A raw FPP command packet (`CTRL_PKT_FPPCOMMAND`) — surfaced for diagnostics/extension. */
    fun onFppCommand(payload: ByteArray, masterIp: String)
    fun onSystemSeen(ping: PingPacket, sourceIp: String)
    fun onStats(stats: MultiSyncStats)
}

/** Rolling counters for the diagnostics screen. */
data class MultiSyncStats(
    val syncOpen: Int = 0,
    val syncStart: Int = 0,
    val syncStop: Int = 0,
    val syncFrames: Int = 0,
    val mediaPackets: Int = 0,
    val pings: Int = 0,
    val blanks: Int = 0,
    val commands: Int = 0,
    val ignored: Int = 0,
    val malformed: Int = 0,
    val lastPacketAtMs: Long = 0L,
    val lastMaster: String = ""
)

/**
 * Receives FPP MultiSync traffic and announces this device as a MultiSync remote.
 *
 * Mirrors what `fppd` does in remote mode: bind UDP/32320 on all interfaces with address reuse,
 * join 239.70.80.80 on every usable interface, and answer discovery pings so the player lists us.
 * Media sync packets are counted but not acted on — this device renders channel data, it does not
 * play the show's audio.
 */
class MultiSyncClient(
    private val context: Context?,
    private val identity: () -> FppCodec.Identity,
    private val listener: MultiSyncListener
) {
    companion object {
        private const val TAG = "FppMultiSync"
        private const val RCV_BUF = 2048

        /**
         * How often we re-announce. FPP itself only pings hourly, but a remote that has just
         * rebooted wants to reappear in the player's list quickly, and the packet is 301 bytes.
         */
        private const val PING_INTERVAL_MS = 5 * 60 * 1000L
    }

    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var rxThread: Thread? = null
    private var pingThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var stats = MultiSyncStats()

    @Volatile
    var lastMasterIp: String = ""
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acquireMulticastLock()
        val sock = try {
            // Unbound first, so SO_REUSEADDR can be set before the bind — fppd uses SO_REUSEPORT
            // for the same reason: several listeners share UDP/32320 on a busy box.
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(FppProtocol.CTRL_PORT))
                broadcast = true
                soTimeout = 1000
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not bind UDP ${FppProtocol.CTRL_PORT}", t)
            running.set(false)
            releaseMulticastLock()
            return
        }
        socket = sock
        joinMulticastGroups(sock)

        rxThread = Thread({ receiveLoop(sock) }, "fppvm-multisync-rx").apply {
            isDaemon = true
            // The sync clock's accuracy is bounded by how promptly we timestamp arrivals.
            priority = Thread.MAX_PRIORITY
            start()
        }
        pingThread = Thread({ pingLoop() }, "fppvm-multisync-ping").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        rxThread?.interrupt()
        pingThread?.interrupt()
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        rxThread = null
        pingThread = null
        releaseMulticastLock()
    }

    fun isRunning(): Boolean = running.get()

    fun currentStats(): MultiSyncStats = stats

    /** Announces this device. [discover] also asks other systems to announce themselves. */
    fun sendPing(discover: Boolean) {
        val sock = socket ?: return
        val data = try {
            FppCodec.buildPing(identity(), discover)
        } catch (t: Throwable) {
            Log.w(TAG, "ping build failed", t)
            return
        }
        sendToAll(sock, data)
    }

    /** Unicast a ping straight back at whoever just probed us. */
    private fun sendPingTo(ip: String, discover: Boolean) {
        val sock = socket ?: return
        try {
            val data = FppCodec.buildPing(identity(), discover)
            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), FppProtocol.CTRL_PORT))
        } catch (t: Throwable) {
            Log.w(TAG, "unicast ping to $ip failed: ${t.message}")
        }
    }

    private fun sendToAll(sock: MulticastSocket, data: ByteArray) {
        // Multicast first (what a modern FPP player listens on), then per-interface directed
        // broadcast, which is what reaches older installs and anything with IGMP snooping in the
        // way. Both are cheap; sending both is the difference between "appears in the list" and
        // an afternoon of wondering why.
        try {
            sock.send(
                DatagramPacket(
                    data, data.size,
                    InetAddress.getByName(FppProtocol.MULTICAST_ADDRESS), FppProtocol.CTRL_PORT
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "multicast ping failed: ${t.message}")
        }
        for (bcast in broadcastAddresses()) {
            try {
                sock.send(DatagramPacket(data, data.size, bcast, FppProtocol.CTRL_PORT))
            } catch (t: Throwable) {
                Log.w(TAG, "broadcast ping to $bcast failed: ${t.message}")
            }
        }
    }

    private fun receiveLoop(sock: MulticastSocket) {
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
            handle(buf, pkt.length, src)
        }
    }

    private fun handle(buf: ByteArray, len: Int, srcIp: String) {
        val header = FppCodec.parseHeader(buf, len)
        if (header == null) {
            bump { it.copy(malformed = it.malformed + 1) }
            return
        }
        val now = System.currentTimeMillis()
        when (header.pktType) {
            FppProtocol.PKT_SYNC -> {
                val s = FppCodec.parseSync(buf, len, header.extraDataLen)
                if (s == null) {
                    bump { it.copy(malformed = it.malformed + 1) }
                    return
                }
                if (s.fileType != FppProtocol.SYNC_FILE_SEQ) {
                    // Media sync: the master is playing audio. We render channel data only, so we
                    // just note it — the sequence sync packets carry the timing we follow.
                    bump { it.copy(mediaPackets = it.mediaPackets + 1, lastPacketAtMs = now, lastMaster = srcIp) }
                    return
                }
                lastMasterIp = srcIp
                when (s.operation) {
                    FppProtocol.SYNC_PKT_OPEN -> {
                        bump { it.copy(syncOpen = it.syncOpen + 1, lastPacketAtMs = now, lastMaster = srcIp) }
                        listener.onSequenceOpen(s.filename, srcIp)
                    }
                    FppProtocol.SYNC_PKT_START -> {
                        bump { it.copy(syncStart = it.syncStart + 1, lastPacketAtMs = now, lastMaster = srcIp) }
                        listener.onSequenceStart(s.filename, srcIp)
                    }
                    FppProtocol.SYNC_PKT_STOP -> {
                        bump { it.copy(syncStop = it.syncStop + 1, lastPacketAtMs = now, lastMaster = srcIp) }
                        listener.onSequenceStop(s.filename, srcIp)
                    }
                    FppProtocol.SYNC_PKT_SYNC -> {
                        bump { it.copy(syncFrames = it.syncFrames + 1, lastPacketAtMs = now, lastMaster = srcIp) }
                        listener.onSequenceSync(s.filename, s.frameNumber, s.secondsElapsed, srcIp)
                    }
                    else -> bump { it.copy(ignored = it.ignored + 1) }
                }
            }

            FppProtocol.PKT_BLANK -> {
                bump { it.copy(blanks = it.blanks + 1, lastPacketAtMs = now, lastMaster = srcIp) }
                listener.onBlank(srcIp)
            }

            FppProtocol.PKT_PING -> {
                bump { it.copy(pings = it.pings + 1, lastPacketAtMs = now) }
                val p = FppCodec.parsePing(buf, len, header.extraDataLen, srcIp)
                if (p == null) {
                    bump { it.copy(malformed = it.malformed + 1) }
                    return
                }
                if (p.hostname == identity().hostname) return // our own announcement, echoed back
                listener.onSystemSeen(p, srcIp)
                if (p.isDiscover) {
                    // Same courtesy fppd extends: answer a discovery probe with our own
                    // announcement, broadcast and unicast, so both listing paths find us.
                    sendPing(false)
                    sendPingTo(srcIp, false)
                }
            }

            FppProtocol.PKT_FPPCOMMAND -> {
                bump { it.copy(commands = it.commands + 1, lastPacketAtMs = now, lastMaster = srcIp) }
                val payload = buf.copyOfRange(
                    FppProtocol.HEADER_SIZE,
                    minOf(len, FppProtocol.HEADER_SIZE + header.extraDataLen)
                )
                listener.onFppCommand(payload, srcIp)
            }

            else -> bump { it.copy(ignored = it.ignored + 1) }
        }
    }

    private inline fun bump(f: (MultiSyncStats) -> MultiSyncStats) {
        val s = f(stats)
        stats = s
        listener.onStats(s)
    }

    private fun pingLoop() {
        // Announce immediately (twice — once as a discovery probe so we also learn who is out
        // there, once as a plain announcement), then keep a slow heartbeat.
        try {
            Thread.sleep(500)
            sendPing(true)
            Thread.sleep(250)
            sendPing(false)
        } catch (_: InterruptedException) {
            return
        }
        while (running.get()) {
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            sendPing(false)
        }
    }

    private fun joinMulticastGroups(sock: MulticastSocket) {
        val group = InetSocketAddress(InetAddress.getByName(FppProtocol.MULTICAST_ADDRESS), FppProtocol.CTRL_PORT)
        var joined = 0
        for (nif in usableInterfaces()) {
            try {
                sock.joinGroup(group, nif)
                joined++
            } catch (t: Throwable) {
                Log.d(TAG, "joinGroup on ${nif.name} failed: ${t.message}")
            }
        }
        if (joined == 0) {
            // Last resort: let the OS pick the interface. Broadcast still works either way.
            try {
                @Suppress("DEPRECATION")
                sock.joinGroup(InetAddress.getByName(FppProtocol.MULTICAST_ADDRESS))
            } catch (t: Throwable) {
                Log.w(TAG, "default joinGroup failed: ${t.message}")
            }
        }
    }

    private fun usableInterfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
            nif.isUp && !nif.isLoopback && !nif.name.startsWith("usb") &&
                !nif.name.startsWith("tether") && nif.inetAddresses.toList().any { it is Inet4Address }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>(2)
        for (nif in usableInterfaces()) {
            for (ia in nif.interfaceAddresses) {
                ia.broadcast?.let { out.add(it) }
            }
        }
        if (out.isEmpty()) {
            try {
                out.add(InetAddress.getByName("255.255.255.255"))
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /** This device's primary IPv4 address, for the ping packet's address field. */
    fun localIpv4(): String {
        for (nif in usableInterfaces()) {
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr.hostAddress ?: continue
                }
            }
        }
        return "0.0.0.0"
    }

    private fun acquireMulticastLock() {
        val ctx = context ?: return
        try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("fppvm-multisync").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.d(TAG, "multicast lock unavailable: ${t.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        multicastLock = null
    }
}
