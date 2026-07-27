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
import app.fppvm.tv.fseq.SequenceStore
import app.fppvm.tv.player.MatrixPlayer
import app.fppvm.tv.proto.FppCodec
import app.fppvm.tv.proto.MultiSyncClient
import app.fppvm.tv.render.MatrixSurfaceView
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
    private lateinit var store: SequenceStore
    private lateinit var player: MatrixPlayer
    private var client: MultiSyncClient? = null

    private var config: MatrixConfig = MatrixConfig.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        config = applyIntentOverride(intent) ?: configStore.load()

        matrixView = MatrixSurfaceView(this)
        setContentView(
            FrameLayout(this).apply {
                addView(
                    matrixView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        )

        store = SequenceStore(File(filesDir, "sequences"))
        player = MatrixPlayer(store, matrixView, config)
        APP_PLAYER = player

        if (config.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntentOverride(intent)?.let {
            config = it
            player.applyConfig(it)
            updateOverlay()
        }
    }

    /** Picks up `--es config '<json>'`, merged over whatever is stored. */
    private fun applyIntentOverride(intent: Intent?): MatrixConfig? {
        val json = intent?.getStringExtra(ConfigStore.EXTRA_CONFIG) ?: return null
        return configStore.applyOverride(json)
    }

    override fun onStart() {
        super.onStart()
        config = configStore.load()
        player.applyConfig(config)
        player.start()
        startMultiSync()
        updateOverlay()
    }

    override fun onStop() {
        super.onStop()
        client?.stop()
        client = null
        player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (APP_PLAYER === player) APP_PLAYER = null
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
            model = "Android TV Virtual Matrix (${Build.MODEL})",
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
                    append(
                        "%.1f fps  decode %.1f ms  paint %.1f ms
".format(s.fps, s.decodeMs, s.paintMs)
                    )
                    append("master ${s.multiSync.lastMaster}  rendered ${s.renderedFrames}  dropped ${s.droppedFrames}")
                    if (s.message.isNotEmpty()) append("\n${s.message}")
                }
            } else {
                matrixView.statusText = null
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
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
    }
}

/** Build identity used in MultiSync announcements. */
object BuildInfo {
    const val NAME = "FPPVM"
    const val VERSION = "0.1.0"
}
