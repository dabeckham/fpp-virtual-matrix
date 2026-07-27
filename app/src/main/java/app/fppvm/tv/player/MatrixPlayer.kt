package app.fppvm.tv.player

import android.util.Log
import app.fppvm.tv.config.MatrixConfig
import app.fppvm.tv.panel.PanelGeometry
import app.fppvm.tv.panel.PanelSolver
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
) : MultiSyncListener, app.fppvm.tv.proto.DdpListener {

    companion object {
        private const val TAG = "FppMatrixPlayer"

        /** Blank the panel if the master goes quiet for this long mid-sequence. */
        const val MASTER_TIMEOUT_MS = 10_000L

        /**
         * Give up on live DDP after this long. Much shorter than the master timeout: DDP is a
         * continuous stream at the sequencer's frame rate, so a gap this long means the sequencer
         * stopped rather than that a packet went missing.
         */
        const val DDP_TIMEOUT_MS = 2_000L

        /** Idle repaint cadence (test pattern / status). */
        private const val IDLE_FRAME_MS = 50L
    }

    enum class State { IDLE, WAITING_FOR_FILE, PLAYING, BLANKED, ERROR }

    /** Where the pixels are coming from. */
    enum class Source { MASTER, LOCAL, DDP }

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
        /** Frames actually painted in the last second. */
        val fps: Float = 0f,
        /** Moving average of FSEQ decode time per frame, milliseconds. */
        val decodeMs: Float = 0f,
        /** Moving average of raster + blit time per frame, milliseconds. */
        val paintMs: Float = 0f,
        val message: String = "",
        val source: Source = Source.MASTER,
        val loop: Boolean = false,
        val ddp: app.fppvm.tv.proto.DdpStats = app.fppvm.tv.proto.DdpStats(),
        /** Solved panel layout, when panel simulation is on. Null otherwise. */
        val panel: PanelGeometry? = null,
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

    @Volatile
    private var panelGeometry: PanelGeometry? = null

    /** Surface size and reported dpi, supplied by the activity; the solver needs both. */
    @Volatile
    var surfaceMetrics: Triple<Int, Int, Float> = Triple(1280, 720, 46f)
        set(value) {
            field = value
            resolvePanel()
        }

    fun panelGeometry(): PanelGeometry? = panelGeometry

    private fun resolvePanel() {
        val c = config
        if (!c.panelEnabled) {
            panelGeometry = null
            return
        }
        val (sw, sh, dpi) = surfaceMetrics
        panelGeometry = PanelSolver.solve(
            mode = c.panelMode,
            pitchMm = c.pitchMm,
            emitterMm = c.emitterMm,
            shape = c.emitterShape,
            substrate = c.substrateColor,
            louvrePercent = c.louvrePercent,
            surfaceWidth = sw,
            surfaceHeight = sh,
            sourceCols = c.width,
            sourceRows = c.height,
            reportedDpi = dpi,
            dpiOverride = c.panelDpi
        )
    }

    /** Guards [reader]/[window], which only the playback thread reads but the socket thread swaps. */
    private val readerLock = Any()
    private var reader: FseqReader? = null
    private var window: FseqReader.Window? = null

    @Volatile
    private var frameBuffer = ByteArray(initialConfig.channelCount)

    /** Playing a file on our own clock, with no master. Cleared the moment one appears. */
    @Volatile
    private var localPlayback = false

    @Volatile
    private var localLoop = true

    /**
     * Live channel data pushed straight at us, with no sequence behind it.
     *
     * The receive thread fills this while the playback thread paints from it. A frame can therefore
     * tear if a push lands mid-paint — which is the same bargain every DMX-style device makes, and
     * is the right one here: the alternative is a copy of the whole matrix on every packet.
     */
    @Volatile
    private var ddpBuffer = ByteArray(initialConfig.channelCount)

    @Volatile
    private var ddpLastDataMs = 0L

    @Volatile
    private var ddpSender = ""

    /**
     * Lets a push wake the playback thread immediately instead of waiting out the idle tick.
     * Explicitly a `java.lang.Object`: Kotlin hides `wait`/`notifyAll` on [Any].
     */
    private val ddpWake = java.lang.Object()
    private var ddpFrameReady = false

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
        if (ddpBuffer.size != next.channelCount) ddpBuffer = ByteArray(next.channelCount)
        view.setConfig(next)
        view.requestLowColorSurface(next.useLowColor || next.panelEnabled)
        resolvePanel()
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
        localPlayback = false
        clock.onSync(frameNumber, secondsElapsed, now)
    }

    override fun onBlank(masterIp: String) {
        // A blank is an instruction, not an absence of one. Falling back to the idle pattern
        // here would light this panel up at the exact moment every other controller in the
        // show goes dark.
        clock.stop()
        localPlayback = false
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

    // ---------------------------------------------------------------- DDP (live output)

    /**
     * Live channel data from a sequencer.
     *
     * [offset] is an absolute zero-based channel index, so it is shifted into this matrix's slice
     * before being stored. Data addressed outside the slice is not an error — a sequencer pushes
     * the whole show and every controller takes its own part — so the overlap is copied and the
     * rest dropped without complaint.
     */
    override fun onDdpData(offset: Int, buf: ByteArray, start: Int, length: Int, sourceIp: String) {
        val dst = ddpBuffer
        var d = offset - config.startChannelZeroBased
        var s = start
        var n = length
        if (d < 0) {
            val skip = -d
            if (skip >= n) return
            s += skip
            n -= skip
            d = 0
        }
        if (d >= dst.size) return
        if (d + n > dst.size) n = dst.size - d
        if (n <= 0) return
        System.arraycopy(buf, s, dst, d, n)
        ddpSender = sourceIp
        ddpLastDataMs = System.currentTimeMillis()
    }

    /**
     * A push only wakes the painter; it deliberately does not mark live output as active.
     *
     * The end-of-frame sync packet is *broadcast*, so this panel sees one whenever a sequencer is
     * driving anything at all on the network. Treating that as "someone is sending to me" would let
     * an unrelated show take the screen away from the idle pattern — or from a blank — while not a
     * single channel of the data was ever addressed to this device. Only [onDdpData] landing bytes
     * inside our own slice counts.
     */
    override fun onDdpPush(sourceIp: String) {
        synchronized(ddpWake) {
            ddpFrameReady = true
            ddpWake.notifyAll()
        }
    }

    override fun onDdpStats(stats: app.fppvm.tv.proto.DdpStats) {
        status = status.copy(ddp = stats)
    }

    /** True while a sequencer is actively driving this panel. */
    private fun ddpActive(nowMs: Long): Boolean =
        ddpLastDataMs != 0L && nowMs - ddpLastDataMs < DDP_TIMEOUT_MS

    /**
     * Waits for the next push, or for the idle tick, whichever comes first.
     *
     * Sleeping a fixed interval would cap live output at 20 fps no matter how fast the sequencer
     * sends; the timeout is only there so a config change and the source going quiet are still
     * noticed promptly.
     */
    private fun awaitDdpFrame() {
        synchronized(ddpWake) {
            if (!ddpFrameReady) {
                try {
                    ddpWake.wait(IDLE_FRAME_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            ddpFrameReady = false
        }
    }

    // ---------------------------------------------------------------- local playback

    /**
     * Plays a cached sequence on our own clock, with no master.
     *
     * For checking a file, or running a panel standalone. A master always wins: the first sync
     * packet for anything else drops local playback, so this can never fight a live show.
     */
    fun playLocal(filename: String, loop: Boolean = true): Boolean {
        val file = store.localFile(filename) ?: return false
        loadLocal(file, masterIp = "", startImmediately = true)
        localLoop = loop
        localPlayback = true
        publish(
            status.copy(
                state = State.PLAYING, sequence = file.name,
                source = Source.LOCAL, loop = loop, message = "playing locally"
            )
        )
        return true
    }

    fun stopLocal() {
        localPlayback = false
        clock.stop()
        synchronized(readerLock) {
            reader?.close()
            reader = null
            window = null
        }
        publish(status.copy(state = State.IDLE, sequence = "", frame = -1, source = Source.MASTER, message = ""))
    }

    fun isPlayingLocally(): Boolean = localPlayback

    // ---------------------------------------------------------------- sequence loading

    private fun filenameMatches(localName: String, wireName: String): Boolean {
        val wanted = SequenceStore.sanitize(wireName) ?: return false
        return localName.equals(wanted, ignoreCase = true)
    }

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
            decodeMsAvg = 0.0
            paintMsAvg = 0.0
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

    private var decodeMsAvg = 0.0
    private var paintMsAvg = 0.0
    /** Playback-thread only: whether the last pass painted live data, so transitions publish once. */
    private var ddpDriving = false
    private var lastFps = 0f
    private var fpsWindow = 0
    private var fpsWindowStart = 0L

    private fun ema(prev: Double, sample: Double): Double =
        if (prev == 0.0) sample else prev * 0.9 + sample * 0.1

    private fun playbackLoop() {
        var lastFrame = Int.MIN_VALUE
        var rendered = 0L
        var dropped = 0L
        fpsWindowStart = System.nanoTime()
        fpsWindow = 0
        val startedAt = System.currentTimeMillis()

        while (running.get()) {
            val now = System.nanoTime()
            val cfg = config

            var frame = if (clock.isRunning) clock.frameAt(now) else -1
            // A local file has no master, so the silence timeout must not apply to it.
            val masterQuiet = !localPlayback && clock.millisSinceSync(now) > MASTER_TIMEOUT_MS
            if (frame < 0 && localPlayback && clock.isRunning) {
                if (localLoop) {
                    clock.start(0, now)
                    frame = 0
                } else {
                    localPlayback = false
                }
            }

            if (frame >= 0 && !masterQuiet) {
                if (frame != lastFrame) {
                    val win = synchronized(readerLock) { window }
                    if (win != null) {
                        if (frameBuffer.size < cfg.channelCount) frameBuffer = ByteArray(cfg.channelCount)
                        val tDecode0 = System.nanoTime()
                        val ok = win.readFrame(frame, frameBuffer)
                        val tPaint0 = System.nanoTime()
                        val geo = panelGeometry
                        val painted = if (geo != null) {
                            val px = if (ok) {
                                raster.renderGrid(frameBuffer, geo.cols, geo.rows, cfg.downsample)
                            } else {
                                raster.renderGrid(ByteArray(0), geo.cols, geo.rows, cfg.downsample)
                            }
                            val packed = raster.packGridTo565(geo.cols * geo.rows)
                            view.presentPanel(px, packed, geo, cfg.bloomPercent)
                        } else if (cfg.useLowColor) {
                            val px = if (ok) raster.render565(frameBuffer) else raster.blank565()
                            view.present565(px, raster.width, raster.height)
                        } else {
                            val px = if (ok) raster.render(frameBuffer) else raster.blank()
                            view.present(px, raster.width, raster.height)
                        }
                        val tEnd = System.nanoTime()
                        if (painted) rendered++ else dropped++
                        lastFrame = frame

                        // Exponential average: one slow frame should show up without a single GC
                        // pause dominating the reading.
                        decodeMsAvg = ema(decodeMsAvg, (tPaint0 - tDecode0) / 1e6)
                        paintMsAvg = ema(paintMsAvg, (tEnd - tPaint0) / 1e6)
                        fpsWindow++
                        if (tEnd - fpsWindowStart >= 1_000_000_000L) {
                            lastFps = fpsWindow * 1e9f / (tEnd - fpsWindowStart)
                            fpsWindow = 0
                            fpsWindowStart = tEnd
                        }

                        if (rendered % 20L == 0L) {
                            publish(
                                status.copy(
                                    state = State.PLAYING,
                                    frame = frame,
                                    driftFrames = clock.lastErrorFrames,
                                    resyncJumps = clock.resyncJumps,
                                    syncPackets = clock.syncPackets,
                                    renderedFrames = rendered,
                                    droppedFrames = dropped,
                                    decodeErrors = win.decodeErrors,
                                    fps = lastFps,
                                    decodeMs = decodeMsAvg.toFloat(),
                                    paintMs = paintMsAvg.toFloat(),
                                    panel = panelGeometry
                                )
                            )
                        }
                    }
                }
                // Re-read the clock: `now` was taken before decode and paint, and on a large
                // matrix that work is most of a frame. Sleeping on the stale value adds a whole
                // extra frame time per frame, which at 64x32 is invisible and at 1280x720 halves
                // the rate.
                sleepUntilNextFrame(System.nanoTime())
            } else {
                if (clock.isRunning && masterQuiet) {
                    clock.stop()
                    publish(status.copy(state = State.IDLE, frame = -1, message = "master silent"))
                }
                lastFrame = Int.MIN_VALUE
                val nowMs = System.currentTimeMillis()
                // Live output outranks the idle pattern, and outranks a blank: a sequencer pushing
                // frames at this panel is someone standing in front of it, right now, expecting to
                // see what they are building.
                if (ddpActive(nowMs)) {
                    if (!ddpDriving) {
                        ddpDriving = true
                        publish(status.copy(state = State.PLAYING, sequence = "", frame = -1, message = "live from $ddpSender"))
                    }
                    paintChannels(cfg, ddpBuffer)
                    awaitDdpFrame()
                } else {
                    if (ddpDriving) {
                        ddpDriving = false
                        publish(status.copy(state = State.IDLE, frame = -1, message = "live output stopped"))
                    }
                    renderIdle(cfg, nowMs - startedAt)
                    try {
                        Thread.sleep(IDLE_FRAME_MS)
                    } catch (e: InterruptedException) {
                        return
                    }
                }
            }
        }
    }

    private fun renderIdle(cfg: MatrixConfig, elapsedMs: Long) {
        // BLANKED came from the master telling the show to go dark. Honour it over idleMode.
        val mode = if (status.state == State.BLANKED) MatrixConfig.IdleMode.BLACK else cfg.idleMode
        when (mode) {
            MatrixConfig.IdleMode.TEST_PATTERN -> paintChannels(cfg, testPattern.render(elapsedMs))
            MatrixConfig.IdleMode.STATUS -> {
                view.statusText = describeIdle()
                view.presentBlank()
            }
            MatrixConfig.IdleMode.BLACK -> {
                if (cfg.useLowColor) {
                    view.present565(raster.blank565(), raster.width, raster.height)
                } else {
                    view.present(raster.blank(), raster.width, raster.height)
                }
            }
        }
    }

    /**
     * Paints one frame of channel data through whichever of the three output paths is configured:
     * the panel simulation, the packed 16-bit path, or plain ARGB.
     */
    private fun paintChannels(cfg: MatrixConfig, data: ByteArray): Boolean {
        val geo = panelGeometry
        return if (geo != null) {
            val px = raster.renderGrid(data, geo.cols, geo.rows, cfg.downsample)
            view.presentPanel(px, raster.packGridTo565(geo.cols * geo.rows), geo, cfg.bloomPercent)
        } else if (cfg.useLowColor) {
            view.present565(raster.render565(data), raster.width, raster.height)
        } else {
            view.present(raster.render(data), raster.width, raster.height)
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
        // Zero is a legitimate answer: when the frame took longer than its step time we are
        // already late for the next one and must not add to it.
        val waitMs = (fractionRemaining * step).toLong().coerceIn(0L, step.toLong())
        if (waitMs <= 0L) return
        try {
            Thread.sleep(waitMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Publishes status, deriving the fields that must never be allowed to go stale.
     *
     * [Status.source] and [Status.panel] are computed here rather than set by whichever call site
     * happened to change them. A stored flag only stays right if every path remembers to update
     * it, and one already did not.
     */
    private fun publish(next: Status) {
        val derived = next.copy(
            source = when {
                localPlayback -> Source.LOCAL
                ddpActive(System.currentTimeMillis()) -> Source.DDP
                else -> Source.MASTER
            },
            panel = panelGeometry
        )
        status = derived
        onStatus?.invoke(derived)
    }
}
