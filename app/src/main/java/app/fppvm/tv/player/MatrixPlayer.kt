package app.fppvm.tv.player

import android.util.Log
import app.fppvm.tv.config.MatrixConfig
import app.fppvm.tv.fseq.FseqReader
import app.fppvm.tv.fseq.SequenceStore
import app.fppvm.tv.proto.MultiSyncStats
import app.fppvm.tv.proto.PingPacket
import app.fppvm.tv.proto.MultiSyncListener
import app.fppvm.tv.render.MatrixRaster
import app.fppvm.tv.render.MatrixSurfaceView
import app.fppvm.tv.render.TestPattern
import app.fppvm.tv.sync.SyncClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the display: follows MultiSync timing, pulls the right FSEQ frame, and paints it.
 *
 * Threading:
 *  - MultiSync packets arrive on the socket thread and only touch [clock] and a few volatiles.
 *  - A dedicated playback thread owns the [FseqReader] and does all decoding and drawing.
 *  - Sequence downloads run on a single background executor so a slow fetch never stalls either.
 *
 * The playback thread paces itself off the sync clock rather than the display refresh: it sleeps
 * until the next sequence frame is actually due. A 20 fps show therefore costs 20 wakeups a
 * second, not 60, which matters on a passively-cooled TV box.
 */
class MatrixPlayer(
    private val store: SequenceStore,
    private val view: MatrixSurfaceView,
    initialConfig: MatrixConfig
) : MultiSyncListener {

    companion object {
        private const val TAG = "FppMatrixPlayer"

        /** Blank the panel if the master goes quiet for this long mid-sequence. */
        const val MASTER_TIMEOUT_MS = 10_000L

        /** Idle repaint cadence (test pattern / status). */
        private const val IDLE_FRAME_MS = 50L
    }

    enum class State { IDLE, WAITING_FOR_FILE, PLAYING, BLANKED, ERROR }

    data class Status(
        val state: State = State.IDLE,
        val sequence: String = "",
        val master: String = "",
        val frame: Int = -1,
        val totalFrames: Int = 0,
        val stepTimeMs: Int = 0,
        val driftFrames: Float = 0f,
        val resyncJumps: Int = 0,
        val syncPackets: Int = 0,
        val renderedFrames: Long = 0,
        val droppedFrames: Long = 0,
        val decodeErrors: Int = 0,
        val message: String = "",
        val multiSync: MultiSyncStats = MultiSyncStats()
    )

    @Volatile
    var config: MatrixConfig = initialConfig
        private set

    private val clock = SyncClock().apply { remoteOffsetMs = initialConfig.remoteOffsetMs }
    private val raster = MatrixRaster(initialConfig)
    private val testPattern = TestPattern(initialConfig)
    private val fetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fppvm-fetch").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Guards [reader]/[window], which only the playback thread reads but the socket thread swaps. */
    private val readerLock = Any()
    private var reader: FseqReader? = null
    private var window: FseqReader.Window? = null

    @Volatile
    private var frameBuffer = ByteArray(initialConfig.channelCount)

    @Volatile
    private var status = Status()

    @Volatile
    var onStatus: ((Status) -> Unit)? = null

    @Volatile
    private var seenSystems = LinkedHashMap<String, PingPacket>()

    fun currentStatus(): Status = status
    fun knownSystems(): List<PingPacket> = seenSystems.values.toList()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        view.setConfig(config)
        thread = Thread({ playbackLoop() }, "fppvm-playback").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
        synchronized(readerLock) {
            reader?.close()
            reader = null
            window = null
        }
    }

    fun applyConfig(next: MatrixConfig) {
        config = next
        clock.remoteOffsetMs = next.remoteOffsetMs
        raster.reconfigure(next)
        testPattern.reconfigure(next)
        if (frameBuffer.size != next.channelCount) frameBuffer = ByteArray(next.channelCount)
        view.setConfig(next)
        // The channel window is derived from the geometry, so re-open it against the same file.
        synchronized(readerLock) {
            val r = reader
            if (r != null) window = r.openWindow(next.startChannelZeroBased, next.channelCount)
        }
    }

    // ---------------------------------------------------------------- MultiSync callbacks

    override fun onSequenceOpen(filename: String, masterIp: String) {
        openSequence(filename, masterIp, startImmediately = false)
    }

    override fun onSequenceStart(filename: String, masterIp: String) {
        openSequence(filename, masterIp, startImmediately = true)
    }

    override fun onSequenceStop(filename: String, masterIp: String) {
        clock.stop()
        synchronized(readerLock) {
            reader?.close()
            reader = null
            window = null
        }
        publish(status.copy(state = State.IDLE, sequence = "", frame = -1, message = "stopped by $masterIp"))
    }

    override fun onSequenceSync(filename: String, frameNumber: Int, secondsElapsed: Float, masterIp: String) {
        val now = System.nanoTime()
        val current = synchronized(readerLock) { reader?.file?.name }
        if (current == null || !filenameMatches(current, filename)) {
            // FPP remotes start a sequence on a bare sync packet too, not just on start/open —
            // a remote that boots mid-show has to be able to join from one.
            openSequence(filename, masterIp, startImmediately = true, joinFrame = frameNumber, joinSeconds = secondsElapsed)
            return
        }
        clock.onSync(frameNumber, secondsElapsed, now)
    }

    override fun onBlank(masterIp: String) {
        clock.stop()
        publish(status.copy(state = State.BLANKED, frame = -1, message = "blanked by $masterIp"))
    }

    override fun onFppCommand(payload: ByteArray, masterIp: String) {
        // Commands are FPP-internal control (start playlist, set volume…). We log them for the
        // diagnostics screen but deliberately do not act: this device's job is to follow the
        // channel data it is told to render, and inventing behaviour here would put it out of
        // step with every other remote in the show.
        val text = String(payload, Charsets.US_ASCII).trim { it <= ' ' }
        Log.d(TAG, "FPP command from $masterIp: $text")
    }

    override fun onSystemSeen(ping: PingPacket, sourceIp: String) {
        val map = LinkedHashMap(seenSystems)
        map[ping.address.ifBlank { sourceIp }] = ping
        seenSystems = map
    }

    override fun onStats(stats: MultiSyncStats) {
        status = status.copy(multiSync = stats)
    }

    // ---------------------------------------------------------------- sequence loading

    private fun filenameMatches(localName: String, wireName: String): Boolean =
        localName.equals(SequenceStore.sanitize(wireName) ?: return false, ignoreCase = true)

    private fun openSequence(
        filename: String,
        masterIp: String,
        startImmediately: Boolean,
        joinFrame: Int = 0,
        joinSeconds: Float = 0f
    ) {
        val name = SequenceStore.sanitize(filename)
        if (name == null) {
            publish(status.copy(state = State.ERROR, message = "rejected sequence name '$filename'"))
            return
        }
        val already = synchronized(readerLock) { reader?.file?.name }
        if (already != null && already.equals(name, ignoreCase = true)) {
            if (startImmediately && !clock.isRunning) {
                clock.start(0, System.nanoTime())
                if (joinSeconds > 0f || joinFrame > 0) clock.onSync(joinFrame, joinSeconds, System.nanoTime())
            }
            return
        }

        val local = store.localFile(name)
        if (local == null) {
            requestFetch(name, masterIp, startImmediately)
            return
        }
        loadLocal(local, masterIp, startImmediately, joinFrame, joinSeconds)
    }

    private fun loadLocal(
        file: File,
        masterIp: String,
        startImmediately: Boolean,
        joinFrame: Int = 0,
        joinSeconds: Float = 0f
    ) {
        try {
            val newReader = FseqReader.open(file)
            store.touch(file)
            val newWindow = newReader.openWindow(config.startChannelZeroBased, config.channelCount)
            synchronized(readerLock) {
                reader?.close()
                reader = newReader
                window = newWindow
            }
            clock.configure(newReader.header.stepTimeMs, newReader.header.numFrames)
            if (startImmediately) {
                clock.start(0, System.nanoTime())
                if (joinSeconds > 0f || joinFrame > 0) clock.onSync(joinFrame, joinSeconds, System.nanoTime())
            }
            val note = if (newWindow.hasData) {
                ""
            } else {
                "sequence carries no data for channels " +
                    "${config.startChannel}-${config.startChannel + config.channelCount - 1}"
            }
            publish(
                status.copy(
                    state = if (startImmediately) State.PLAYING else State.IDLE,
                    sequence = file.name,
                    master = masterIp,
                    totalFrames = newReader.header.numFrames,
                    stepTimeMs = newReader.header.stepTimeMs,
                    message = note
                )
            )
            Log.i(
                TAG,
                "opened ${file.name}: ${newReader.header.numFrames} frames @ ${newReader.header.stepTimeMs}ms, " +
                    "${newReader.header.frameSize} ch/frame, ${newReader.header.compression}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "failed to open ${file.name}", t)
            publish(status.copy(state = State.ERROR, sequence = file.name, message = "open failed: ${t.message}"))
        }
    }

    private fun requestFetch(name: String, masterIp: String, startWhenReady: Boolean) {
        if (!config.autoFetchSequences) {
            publish(
                status.copy(
                    state = State.WAITING_FOR_FILE,
                    sequence = name,
                    master = masterIp,
                    message = "not cached and auto-fetch is off"
                )
            )
            return
        }
        val host = config.masterHost.ifBlank { masterIp }
        if (store.isFetching(name) || !store.beginFetch(name)) return
        publish(
            status.copy(
                state = State.WAITING_FOR_FILE,
                sequence = name,
                master = host,
                message = "downloading from $host"
            )
        )
        fetchExecutor.execute {
            try {
                when (val r = store.fetchFromMaster(host, name)) {
                    is SequenceStore.Result.Ready -> loadLocal(r.file, host, startWhenReady)
                    is SequenceStore.Result.Missing ->
                        publish(
                            status.copy(
                                state = State.ERROR,
                                sequence = name,
                                message = "fetch failed: ${r.reason}"
                            )
                        )
                    is SequenceStore.Result.Fetching -> Unit
                }
            } finally {
                store.endFetch(name)
            }
        }
    }

    // ---------------------------------------------------------------- playback

    private fun playbackLoop() {
        var lastFrame = Int.MIN_VALUE
        var rendered = 0L
        var dropped = 0L
        val startedAt = System.currentTimeMillis()

        while (running.get()) {
            val now = System.nanoTime()
            val cfg = config

            val frame = if (clock.isRunning) clock.frameAt(now) else -1
            val masterQuiet = clock.millisSinceSync(now) > MASTER_TIMEOUT_MS

            if (frame >= 0 && !masterQuiet) {
                if (frame != lastFrame) {
                    val win = synchronized(readerLock) { window }
                    if (win != null) {
                        if (frameBuffer.size < cfg.channelCount) frameBuffer = ByteArray(cfg.channelCount)
                        val ok = win.readFrame(frame, frameBuffer)
                        val pixels = if (ok) raster.render(frameBuffer) else raster.blank()
                        if (view.present(pixels, raster.width, raster.height)) rendered++ else dropped++
                        lastFrame = frame
                        if (rendered % 40L == 0L) {
                            publish(
                                status.copy(
                                    state = State.PLAYING,
                                    frame = frame,
                                    driftFrames = clock.lastErrorFrames,
                                    resyncJumps = clock.resyncJumps,
                                    syncPackets = clock.syncPackets,
                                    renderedFrames = rendered,
                                    droppedFrames = dropped,
                                    decodeErrors = win.decodeErrors
                                )
                            )
                        }
                    }
                }
                sleepUntilNextFrame(now)
            } else {
                if (clock.isRunning && masterQuiet) {
                    clock.stop()
                    publish(status.copy(state = State.IDLE, frame = -1, message = "master silent"))
                }
                lastFrame = Int.MIN_VALUE
                renderIdle(cfg, System.currentTimeMillis() - startedAt)
                try {
                    Thread.sleep(IDLE_FRAME_MS)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun renderIdle(cfg: MatrixConfig, elapsedMs: Long) {
        when (cfg.idleMode) {
            MatrixConfig.IdleMode.TEST_PATTERN -> {
                val data = testPattern.render(elapsedMs)
                view.present(raster.render(data), raster.width, raster.height)
            }
            MatrixConfig.IdleMode.STATUS -> {
                view.statusText = describeIdle()
                view.presentBlank()
            }
            MatrixConfig.IdleMode.BLACK -> {
                view.present(raster.blank(), raster.width, raster.height)
            }
        }
    }

    private fun describeIdle(): String {
        val s = status
        return buildString {
            append("FPP Virtual Matrix — ${config.width}x${config.height} @ ch ${config.startChannel}\n")
            append("state: ${s.state}")
            if (s.sequence.isNotEmpty()) append("  seq: ${s.sequence}")
            append('\n')
            append("multisync: ${if (config.multiSyncEnabled) "listening on 32320" else "disabled"}")
            if (s.multiSync.lastMaster.isNotEmpty()) append("  master: ${s.multiSync.lastMaster}")
            append('\n')
            if (s.message.isNotEmpty()) append(s.message)
        }
    }

    /**
     * Sleeps until the next sequence frame is due, so the loop wakes once per show frame instead
     * of spinning. Capped at the idle cadence so a config change is picked up promptly.
     */
    private fun sleepUntilNextFrame(nowNanos: Long) {
        val step = clock.stepTimeMs.coerceAtLeast(1)
        val pos = clock.positionAt(nowNanos)
        val fractionRemaining = 1.0 - (pos - Math.floor(pos))
        val waitMs = (fractionRemaining * step).toLong().coerceIn(1L, step.toLong())
        try {
            Thread.sleep(waitMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun publish(next: Status) {
        status = next
        onStatus?.invoke(next)
    }
}
