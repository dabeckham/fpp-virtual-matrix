package app.fppvm.tv

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import app.fppvm.tv.config.ConfigStore
import app.fppvm.tv.config.MatrixConfig
import app.fppvm.tv.focus.FocusGuard
import app.fppvm.tv.fseq.SequenceStorage
import app.fppvm.tv.fseq.SequenceStore
import app.fppvm.tv.player.MatrixPlayer
import app.fppvm.tv.proto.FppCodec
import app.fppvm.tv.proto.MultiSyncClient
import app.fppvm.tv.render.MatrixSurfaceView
import app.fppvm.tv.web.WebConfigServer
import org.json.JSONObject
import app.fppvm.tv.ui.DiagnosticsActivity
import app.fppvm.tv.ui.SettingsActivity
import java.io.File

/**
 * Full-screen virtual matrix.
 *
 * D-pad: MENU opens settings, INFO opens diagnostics, OK toggles the stats overlay, BACK leaves.
 * Nothing else is bound — this is a display, not an app to browse.
 */
class MainActivity : ComponentActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var matrixView: MatrixSurfaceView
    private lateinit var videoView: app.fppvm.tv.render.VideoLayerView
    private lateinit var store: SequenceStore
    private lateinit var player: MatrixPlayer
    private var client: MultiSyncClient? = null
    private var ddp: app.fppvm.tv.proto.DdpReceiver? = null
    private var web: WebConfigServer? = null
    private var focusGuard: FocusGuard? = null
    private var storageWatcher: android.content.BroadcastReceiver? = null

    private var config: MatrixConfig = MatrixConfig.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        // Saved profiles live in preferences, which the pure profile library cannot reach. Install
        // the lookup before anything resolves a profile id, or your own profiles silently do
        // nothing and every one of them reports as modified.
        app.fppvm.tv.panel.LedProfiles.userProfiles = { app.fppvm.tv.web.ProfileStore(this).load() }
        config = applyIntentOverride(intent) ?: configStore.load()

        matrixView = MatrixSurfaceView(this)
        videoView = app.fppvm.tv.render.VideoLayerView(this).apply { visibility = View.GONE }
        setContentView(
            FrameLayout(this).apply {
                // Video first, so it sits underneath. The matrix surface goes translucent while a
                // video is playing, which is what lets the picture through the gaps between the
                // emitters instead of a grid of dark dots covering it.
                addView(
                    videoView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    matrixView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        )

        store = SequenceStore(File(filesDir, SequenceStorage.SEQ_SUBDIR))
        player = MatrixPlayer(store, matrixView, config)
        player.onVideoPair = { f -> runOnUiThread { setVideo(f) } }
        APP_PLAYER = player

        if (config.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        publishSurfaceMetrics()
        applyStorage()
        focusGuard = FocusGuard(
            activity = this,
            showPlaying = { player.currentStatus().state == MatrixPlayer.State.PLAYING },
            enabled = { config.holdFocus }
        )
        registerStorageWatcher()
        // Tied to the process, not the foreground. A TV that has dropped to its launcher is
        // exactly when you need to reach it, and stopping the server in onStop meant losing the
        // remote surface at the only moment it mattered.
        startWebServer()
        goImmersive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntentOverride(intent)?.let {
            config = it
            player.applyConfig(it)
            applyStorage()
            reconcileDdp()
            updateOverlay()
        }
    }

    /** Picks up `--es config '<json>'`, merged over whatever is stored. */
    private fun applyIntentOverride(intent: Intent?): MatrixConfig? {
        val json = intent?.getStringExtra(ConfigStore.EXTRA_CONFIG) ?: return null
        return configStore.applyOverride(json)
    }

    /**
     * Physical units only mean something if the dpi is right, and `xdpi` is a vendor constant
     * rather than a measurement, so the solver sanity-checks it and the user can override.
     */
    private fun publishSurfaceMetrics() {
        val dm = resources.displayMetrics
        val w = if (dm.widthPixels > 0) dm.widthPixels else 1280
        val h = if (dm.heightPixels > 0) dm.heightPixels else 720
        val dpi = if (dm.xdpi > 1f) dm.xdpi else dm.densityDpi.toFloat()
        player.surfaceMetrics = Triple(w, h, dpi)
    }

    /**
     * Points the sequence cache at a USB stick when one is present.
     *
     * Reads always span every volume, so pulling the stick loses only what was on it — a sequence
     * cached internally keeps playing.
     */
    private fun applyStorage() {
        val write = SequenceStorage.writeDir(this, config.preferRemovableStorage)
        store.useDirectories(write, SequenceStorage.searchDirs(this))
        android.util.Log.i("FppVm", "sequences write to " + write.absolutePath)
    }

    /** A stick appearing or disappearing changes where sequences should be written. */
    private fun registerStorageWatcher() {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: Intent?) = applyStorage()
        }
        try {
            registerReceiver(r, filter)
            storageWatcher = r
        } catch (t: Throwable) {
            android.util.Log.w("FppVm", "could not watch storage", t)
        }
    }

    override fun onStart() {
        super.onStart()
        config = configStore.load()
        applyStorage()
        player.applyConfig(config)
        publishSurfaceMetrics()
        player.start()
        startMultiSync()
        startDdp()
        updateOverlay()
    }

    override fun onStop() {
        super.onStop()
        // Ask for the screen back before tearing anything down, so a launcher grabbing focus
        // mid-show does not end the show.
        focusGuard?.onFocusLost()
        videoView.stop()
        client?.stop()
        client = null
        ddp?.stop()
        ddp = null
        player.stop()
    }

    /**
     * Brings up the config page and the FPP-compatible file API.
     *
     * Started here rather than in [onStart] so it lives as long as the process. A TV that has
     * dropped to its launcher is precisely when you need to reach it, and tying the server to the
     * foreground meant losing the remote surface at the only moment it mattered.
     */
    private fun startWebServer() {
        if (!config.webServerEnabled) return
        try {
            val s = WebConfigServer(
                context = this,
                port = config.webPort,
                onConfigChanged = { next ->
                    runOnUiThread {
                        config = next
                        player.applyConfig(next)
                        // Both of these used to wait for the next onStart, so switching storage or
                        // turning live output on from the browser appeared to do nothing.
                        applyStorage()
                        reconcileDdp()
                        updateOverlay()
                    }
                },
                statusJson = { statusJson() },
                identityJson = { identityJson() },
                onBringToFront = { bringToFront() },
                storageJson = { storageJson() },
                onPlayLocal = { name, loop -> player.playLocal(name, loop) },
                onStopLocal = { player.stopLocal() },
                onPlayVideo = { name ->
                    val f = if (name.isNullOrBlank()) null else store.localFile(name)
                    runOnUiThread { setVideo(f) }
                    name.isNullOrBlank() || f != null
                },
                channelOutputsJson = { channelOutputsJson() }
            )
            s.password = config.webPassword
            s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            web = s
            android.util.Log.i("FppVm", "web config on http://${client?.localIpv4() ?: "?"}:${config.webPort}")
        } catch (t: Throwable) {
            android.util.Log.w("FppVm", "web server failed to start", t)
        }
    }

    /**
     * Starts or stops the video layer, and puts the matrix into the matching mode.
     *
     * The two have to move together: a translucent matrix over no video would show the launcher
     * through the gaps, and an opaque matrix over a video would hide it completely.
     */
    private fun setVideo(file: java.io.File?) {
        if (file == null) {
            videoView.stop()
            videoView.visibility = View.GONE
            matrixView.videoUnderlay = false
            player.videoUnderlay = false
            matrixView.requestLowColorSurface(config.useLowColor)
            return
        }
        videoView.visibility = View.VISIBLE
        matrixView.videoUnderlay = true
        player.videoUnderlay = true
        videoView.play(file)
        android.util.Log.i("FppVm", "video layer: ${file.name}")
    }

    /** The video file currently behind the panel, for the status page. */
    private fun videoJson(): JSONObject = JSONObject().apply {
        put("playing", videoView.isPlaying())
        put("positionMs", videoView.positionMs())
        put("durationMs", videoView.durationMs())
        put("underlay", matrixView.videoUnderlay)
        put("error", videoView.lastError)
    }

    private fun statusJson(): JSONObject {
        val st = player.currentStatus()
        return JSONObject().apply {
            put("state", st.state.name)
            put("sequence", st.sequence)
            put("frame", st.frame)
            put("totalFrames", st.totalFrames)
            put("stepTimeMs", st.stepTimeMs)
            put("fps", String.format("%.1f", st.fps))
            put("decodeMs", String.format("%.1f", st.decodeMs))
            put("paintMs", String.format("%.1f", st.paintMs))
            put("driftFrames", String.format("%.2f", st.driftFrames))
            put("resyncJumps", st.resyncJumps)
            put("master", st.multiSync.lastMaster)
            put("syncPackets", st.syncPackets)
            put("ddpEnabled", config.ddpEnabled)
            put("ddpListening", ddp?.isRunning() == true)
            put("ddpPackets", st.ddp.packets)
            put("ddpPushes", st.ddp.pushes)
            put("ddpSender", st.ddp.lastSender)
            put("video", videoJson())
            // Header fields, mirroring what FPP's own status API exposes.
            put("host_name", config.hostname.ifBlank { defaultHostname() })
            put("host_description", HOST_DESCRIPTION)
            put("platform", "Android " + Build.VERSION.RELEASE)
            put("version", BuildInfo.VERSION)
            put("mode_name", "remote")
            put("status_name", st.state.name.lowercase())
            put("source", st.source.name)
            put("current_sequence", st.sequence)
            put("seconds_played", if (st.stepTimeMs > 0 && st.frame >= 0) st.frame * st.stepTimeMs / 1000 else 0)
            put("seconds_remaining",
                if (st.stepTimeMs > 0 && st.frame >= 0) (st.totalFrames - st.frame) * st.stepTimeMs / 1000 else 0)
            put("uptimeSeconds", (android.os.SystemClock.elapsedRealtime() - startedAtElapsed) / 1000)
            put("time", timeStamp())
            put("holdFocus", config.holdFocus)
            put("focusReleasedMinutes", focusGuard?.releaseMinutesRemaining() ?: 0L)
            // Live geometry rather than the last published copy: the achieved figures matter most
            // while idle, which is when you are adjusting them.
            (player.panelGeometry() ?: st.panel)?.let {
                put("panel", it.describe())
                put("degraded", it.degraded)
                put("cells", it.cellCount)
            }
        }
    }

    private val startedAtElapsed = android.os.SystemClock.elapsedRealtime()

    /**
     * UTC plus the collecting zone's offset. A time without its offset is ambiguous twice a year,
     * and a status line you cannot place in time is not much of a status line.
     */
    private fun timeStamp(): String {
        val utc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val local = java.text.SimpleDateFormat("HH:mm:ssXXX", java.util.Locale.US)
        val now = java.util.Date()
        return utc.format(now) + " (" + local.format(now) + " " + java.util.TimeZone.getDefault().id + ")"
    }

    private fun storageJson(): JSONObject = JSONObject().apply {
        val arr = org.json.JSONArray()
        for (v in SequenceStorage.volumes(this@MainActivity)) {
            arr.put(
                JSONObject()
                    .put("label", v.label)
                    .put("removable", v.removable)
                    .put("path", v.dir.absolutePath)
                    .put("freeBytes", v.freeBytes)
                    .put("totalBytes", v.totalBytes)
                    .put("active", v.dir.absolutePath == store.dir.absolutePath)
            )
        }
        put("volumes", arr)
        put("preferRemovable", config.preferRemovableStorage)
        put("writeDir", store.dir.absolutePath)
    }

    /**
     * FPP-shaped identity. xLights finds a device via the MultiSync ping and then asks this over
     * HTTP to work out what it is talking to, so the field names have to be FPP's, not ours.
     */
    private fun identityJson(): JSONObject = JSONObject().apply {
        put("HostName", config.hostname.ifBlank { defaultHostname() })
        put("HostDescription", HOST_DESCRIPTION)
        put("Platform", "Android")
        put("Variant", Build.MODEL)
        put("Mode", "remote")
        put("Version", BuildInfo.VERSION)
        put("majorVersion", 8)
        put("minorVersion", 0)
        put("typeId", app.fppvm.tv.proto.FppProtocol.SYS_TYPE_FPP)
        put("channelRanges", config.rangesString())
        put("IPs", org.json.JSONArray().put(client?.localIpv4() ?: "0.0.0.0"))
        put("multisync", config.multiSyncEnabled)
        // Mandatory. A reader with no UUID abandons the whole exchange and marks the device as
        // unreachable over HTTP, which then excludes it from FPP Connect entirely.
        put("uuid", deviceUuid())
        put("UUID", deviceUuid())
        // Names the output so a reader resolves this to its existing FPP / Virtual Matrix entry
        // rather than leaving the model blank.
        put("capeInfo", JSONObject().put("id", FPP_MODEL).put("name", FPP_MODEL).put("vendor", FPP_VENDOR))
    }

    /**
     * FPP's channel-output configuration, reduced to the one output this device has.
     *
     * xLights reads this to work out what kind of controller it is talking to: an entry of type
     * `VirtualMatrix` is what makes it choose the FPP / Virtual Matrix capability entry it already
     * ships, which is exactly what this is.
     */
    private fun channelOutputsJson(): JSONObject = JSONObject().apply {
        put(
            "channelOutputs",
            org.json.JSONArray().put(
                JSONObject()
                    .put("type", "VirtualMatrix")
                    .put("enabled", 1)
                    .put("startChannel", config.startChannel)
                    .put("channelCount", config.channelCount)
                    .put("width", config.width)
                    .put("height", config.height)
                    .put("device", "Android TV")
            )
        )
    }

    /**
     * Stable per install, and derived rather than stored so it survives a cleared preferences file
     * without the device appearing to be a different one.
     */
    private fun deviceUuid(): String = cachedUuid ?: synchronized(this) {
        cachedUuid ?: run {
            val androidId = try {
                android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            } catch (t: Throwable) {
                null
            } ?: Build.FINGERPRINT
            java.util.UUID.nameUUIDFromBytes("fppvm:$androidId".toByteArray()).toString()
                .also { cachedUuid = it }
        }
    }

    @Volatile
    private var cachedUuid: String? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            web?.stop()
        } catch (_: Throwable) {
        }
        web = null
        focusGuard?.cancel()
        focusGuard = null
        storageWatcher?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Throwable) {
            }
        }
        storageWatcher = null
        if (APP_PLAYER === player) APP_PLAYER = null
    }

    /**
     * Live output from a sequencer, plus the discovery reply that gives this device a vendor and
     * model in xLights. That reply is the only route to those columns: for anything identifying as
     * a full FPP instance xLights fills them from HTTP on port 80, which an Android app cannot bind.
     */
    /** Brings the listener into line with the setting, whichever way it was just changed. */
    private fun reconcileDdp() {
        val running = ddp != null
        if (config.ddpEnabled && !running) {
            startDdp()
        } else if (!config.ddpEnabled && running) {
            ddp?.stop()
            ddp = null
        }
    }

    private fun startDdp() {
        if (!config.ddpEnabled) return
        val r = app.fppvm.tv.proto.DdpReceiver(
            identity = {
                app.fppvm.tv.proto.DdpReceiver.Identity(
                    manufacturer = FPP_VENDOR,
                    model = FPP_MODEL,
                    version = BuildInfo.VERSION
                )
            },
            listener = player
        )
        ddp = r
        r.start()
    }

    private fun startMultiSync() {
        if (!config.multiSyncEnabled) return
        val c = MultiSyncClient(
            context = this,
            identity = { identity() },
            listener = player
        )
        client = c
        c.start()
        APP_CLIENT = c
    }

    /**
     * What this device announces to the show. The advertised channel range is exactly the block
     * the matrix consumes, so the player's MultiSync page shows what this panel is responsible for
     * rather than a made-up whole-universe claim.
     */
    private fun identity(): FppCodec.Identity {
        val name = config.hostname.ifBlank { defaultHostname() }
        return FppCodec.Identity(
            hostname = name,
            version = "${BuildInfo.NAME} ${BuildInfo.VERSION}",
            // The hardware, not the function. This field sits beside "Raspberry Pi 4 Model B" and
            // "BeagleBone Black" in a player's system list, and FPP maps it back to a system type,
            // so it answers "what is this box". What it *does* is carried by the model name in the
            // DDP status reply and by the channel-output type over HTTP.
            model = "Android TV (${Build.MODEL})".take(40),
            ranges = config.rangesString(),
            ipv4 = client?.localIpv4() ?: "0.0.0.0"
        )
    }

    private fun defaultHostname(): String {
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-')
        return if (model.isBlank()) "fpp-matrix" else "fppvm-$model"
    }

    private fun updateOverlay() {
        matrixView.statusText = if (config.showOverlay) "" else null
        player.onStatus = { s ->
            if (config.showOverlay) {
                matrixView.statusText = buildString {
                    append("${s.state}  ${s.sequence}\n")
                    append("frame ${s.frame}/${s.totalFrames}  step ${s.stepTimeMs}ms\n")
                    append(
                        "drift %.2f f  jumps %d  sync %d\n".format(
                            s.driftFrames, s.resyncJumps, s.syncPackets
                        )
                    )
                    appendLine("%.1f fps  decode %.1f ms  paint %.1f ms".format(s.fps, s.decodeMs, s.paintMs))
                    s.panel?.let { appendLine(it.describe()) }
                    append("master ${s.multiSync.lastMaster}  rendered ${s.renderedFrames}  dropped ${s.droppedFrames}")
                    if (s.message.isNotEmpty()) append("\n${s.message}")
                }
            } else {
                matrixView.statusText = null
            }
        }
    }

    private fun onKeyDownInner(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_YELLOW -> {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                config = config.copy(showOverlay = !config.showOverlay)
                configStore.save(config)
                player.applyConfig(config)
                updateOverlay()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * BACK is tracked rather than acted on immediately so a long press can release the focus hold.
     * That escape has to exist: an app that is both the home screen and takes focus back could
     * otherwise lock someone out of their own television.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && config.holdFocus) {
            event.startTracking()
            return true
        }
        return onKeyDownInner(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            focusGuard?.let { g ->
                g.release()
                android.widget.Toast.makeText(
                    this,
                    "Focus hold released for " + FocusGuard.RELEASE_MINUTES + " minutes",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // A short press still means back; only the hold releases the guard.
        if (keyCode == KeyEvent.KEYCODE_BACK && config.holdFocus && !event.isCanceled) {
            finish()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Brings the show back to the front, for the web UI's button. */
    fun bringToFront() {
        runOnUiThread {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            } catch (t: Throwable) {
                android.util.Log.w("FppVm", "bringToFront failed", t)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            focusGuard?.onFocusGained()
            goImmersive()
        } else {
            focusGuard?.onFocusLost()
        }
    }

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    companion object {
        /**
         * The settings and diagnostics screens read live state from the running player. Holding it
         * statically is deliberate and bounded: it is cleared in [onDestroy], and the alternative
         * (a bound service) buys nothing for a single-activity display app.
         */
        @Volatile
        var APP_PLAYER: MatrixPlayer? = null

        @Volatile
        var APP_CLIENT: MultiSyncClient? = null

        /**
         * How this device names itself to xLights.
         *
         * These two strings are not decoration — they are looked up. xLights ships a capability
         * entry for vendor "FPP", controller "Virtual Matrix", and matching it is what fills the
         * Vendor and Model columns and gives the controller the right capabilities. Anything else,
         * however accurate, leaves those columns empty.
         */
        const val FPP_VENDOR = "FPP"
        const val FPP_MODEL = "Virtual Matrix"

        const val HOST_DESCRIPTION = "Android TV Virtual Matrix"
    }
}

/** Build identity used in MultiSync announcements. */
object BuildInfo {
    const val NAME = "FPPVM"
    const val VERSION = "0.1.0"
}
