package app.fppvm.tv.ui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.fppvm.tv.MainActivity
import app.fppvm.tv.config.ConfigStore
import app.fppvm.tv.config.MatrixConfig

/**
 * D-pad settings screen.
 *
 * Built in code rather than XML on purpose: every row is the same shape (label, value, left/right
 * to change it) so a declarative list of [Setting] descriptors is both shorter and harder to get
 * out of step with [MatrixConfig] than a screenful of layout files would be.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var store: ConfigStore
    private var config: MatrixConfig = MatrixConfig.DEFAULT
    private lateinit var container: LinearLayout
    private val rows = ArrayList<Pair<Setting, TextView>>()

    /** One editable field: how to show it and how left/right/OK change it. */
    private class Setting(
        val label: String,
        val hint: String = "",
        val show: (MatrixConfig) -> String,
        val step: (MatrixConfig, Int) -> MatrixConfig
    )

    private val settings: List<Setting> = listOf(
        Setting("Matrix width", "pixels across", { "${it.width}" }) { c, d -> c.copy(width = c.width + d) },
        Setting("Matrix height", "pixels down", { "${it.height}" }) { c, d -> c.copy(height = c.height + d) },
        Setting(
            "Start channel",
            "1-based, as in FPP",
            { "${it.startChannel}  (uses ${it.channelCount} channels)" }
        ) { c, d -> c.copy(startChannel = c.startChannel + d * channelStep(c)) },
        Setting("Colour order", "channel order per pixel", { it.colorOrder.name }) { c, d ->
            c.copy(colorOrder = cycle(MatrixConfig.ColorOrder.entries, c.colorOrder, d))
        },
        Setting("Flip horizontal", "", { yesNo(it.flipHorizontal) }) { c, _ ->
            c.copy(flipHorizontal = !c.flipHorizontal)
        },
        Setting("Flip vertical", "FPP calls this invert", { yesNo(it.flipVertical) }) { c, _ ->
            c.copy(flipVertical = !c.flipVertical)
        },
        Setting("Transpose", "channels run down columns", { yesNo(it.transpose) }) { c, _ ->
            c.copy(transpose = !c.transpose)
        },
        Setting("Brightness", "%", { "${it.brightness}" }) { c, d -> c.copy(brightness = c.brightness + d * 5) },
        Setting("Gamma", "1.0 = untouched", { "%.2f".format(it.gamma) }) { c, d ->
            c.copy(gamma = c.gamma + d * 0.05f)
        },
        Setting("Scale", "", { it.scaleMode.name }) { c, d ->
            c.copy(scaleMode = cycle(MatrixConfig.ScaleMode.entries, c.scaleMode, d))
        },
        Setting("Pixel style", "", { it.pixelStyle.name }) { c, d ->
            c.copy(pixelStyle = cycle(MatrixConfig.PixelStyle.entries, c.pixelStyle, d))
        },
        Setting("Pixel gap", "% of each cell", { "${it.pixelGapPercent}" }) { c, d ->
            c.copy(pixelGapPercent = c.pixelGapPercent + d * 5)
        },
        Setting("MultiSync", "follow an FPP player", { yesNo(it.multiSyncEnabled) }) { c, _ ->
            c.copy(multiSyncEnabled = !c.multiSyncEnabled)
        },
        Setting("Sync offset", "ms, + renders later", { "${it.remoteOffsetMs}" }) { c, d ->
            c.copy(remoteOffsetMs = c.remoteOffsetMs + d * 10)
        },
        Setting("Auto-fetch sequences", "pull FSEQ from the master", { yesNo(it.autoFetchSequences) }) { c, _ ->
            c.copy(autoFetchSequences = !c.autoFetchSequences)
        },
        Setting("When idle", "", { it.idleMode.name }) { c, d ->
            c.copy(idleMode = cycle(MatrixConfig.IdleMode.entries, c.idleMode, d))
        },
        Setting("Stats overlay", "", { yesNo(it.showOverlay) }) { c, _ ->
            c.copy(showOverlay = !c.showOverlay)
        },
        Setting("Panel simulation", "look like real LEDs", { it.panelMode.name }) { c, d ->
            c.copy(panelMode = cycle(app.fppvm.tv.panel.PanelMode.entries, c.panelMode, d))
        },
        Setting("LED profile", "loads values, does not lock them", { it.profileLabel }) { c, d ->
            val lib = app.fppvm.tv.panel.LedProfiles.BUILT_IN
            val i = lib.indexOfFirst { p -> p.id == c.profileId }.coerceAtLeast(0)
            c.applyProfile(lib[(((i + d) % lib.size) + lib.size) % lib.size])
        },
        Setting("Pitch", "mm, centre to centre", { "%.2f".format(it.pitchMm) }) { c, d ->
            c.copy(pitchMm = c.pitchMm + d * 0.5f).edited()
        },
        Setting("Emitter", "mm, visible package face", { "%.2f".format(it.emitterMm) }) { c, d ->
            c.copy(emitterMm = c.emitterMm + d * 0.1f).edited()
        },
        Setting("Emitter shape", "round bulb / square SMD", { it.emitterShape.name }) { c, d ->
            c.copy(emitterShape = cycle(app.fppvm.tv.panel.EmitterShape.entries, c.emitterShape, d)).edited()
        },
        Setting("Substrate", "unlit surface colour", { substrateName(it.substrateColor) }) { c, d ->
            c.copy(substrateColor = cycle(SUBSTRATES, c.substrateColor, d)).edited()
        },
        Setting("Louvre", "outdoor shade, % of cell", { "${it.louvrePercent}" }) { c, d ->
            c.copy(louvrePercent = c.louvrePercent + d * 2).edited()
        },
        Setting("Bloom", "% toward the cell corner", { "${it.bloomPercent}" }) { c, d ->
            c.copy(bloomPercent = c.bloomPercent + d * 5).edited()
        },
        Setting("Resample", "MAX keeps single lit pixels", { it.downsample.name }) { c, d ->
            c.copy(downsample = cycle(app.fppvm.tv.panel.Downsample.entries, c.downsample, d))
        },
        Setting("Panel dpi", "0 = use the reported value", { if (it.panelDpi <= 0f) "auto" else "%.0f".format(it.panelDpi) }) { c, d ->
            val next = if (c.panelDpi <= 0f && d > 0) 46f else c.panelDpi + d
            c.copy(panelDpi = if (next < 10f) 0f else next)
        },
        Setting("Web config", "browse to this TV to configure it", { yesNo(it.webServerEnabled) }) { c, _ ->
            c.copy(webServerEnabled = !c.webServerEnabled)
        },
        Setting("Use USB storage", "when a stick is plugged in", { yesNo(it.preferRemovableStorage) }) { c, _ ->
            c.copy(preferRemovableStorage = !c.preferRemovableStorage)
        },
        Setting("Hold focus", "hold BACK to release", { yesNo(it.holdFocus) }) { c, _ ->
            c.copy(holdFocus = !c.holdFocus)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        config = store.load()

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
            setBackgroundColor(BACKGROUND)
        }
        container.addView(header("FPP Virtual Matrix — settings"))
        container.addView(
            hint("Left / Right change a value.  BACK returns to the matrix.  Saved as you go.")
        )

        for (s in settings) {
            val row = TextView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(Color.WHITE)
                setPadding(24, 18, 24, 18)
                setOnFocusChangeListener { v, has ->
                    v.setBackgroundColor(if (has) ACCENT_DIM else Color.TRANSPARENT)
                }
            }
            container.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            rows.add(s to row)
        }

        container.addView(hint("Cached sequences and live sync figures are on the diagnostics screen (INFO)."))

        setContentView(ScrollView(this).apply { addView(container) })
        refresh()
        rows.firstOrNull()?.second?.requestFocus()
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setTextColor(ACCENT)
        setPadding(24, 0, 24, 12)
        gravity = Gravity.START
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.parseColor("#9AA0A6"))
        setPadding(24, 8, 24, 20)
    }

    private fun refresh() {
        for ((s, view) in rows) {
            val suffix = if (s.hint.isEmpty()) "" else "   ${s.hint}"
            view.text = "${s.label.padEnd(22)}${s.show(config)}$suffix"
        }
    }

    private fun adjust(delta: Int) {
        val idx = rows.indexOfFirst { it.second.hasFocus() }
        if (idx < 0) return
        config = rows[idx].first.step(config, delta).validated()
        store.save(config)
        MainActivity.APP_PLAYER?.applyConfig(config)
        refresh()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                adjust(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                adjust(1); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private val BACKGROUND = Color.parseColor("#101214")
        private val ACCENT = Color.parseColor("#4DA3FF")
        private val ACCENT_DIM = Color.parseColor("#25405C")

        private fun yesNo(b: Boolean) = if (b) "on" else "off"

        private val SUBSTRATES = listOf(
            app.fppvm.tv.panel.LedProfile.SUBSTRATE_NONE,
            app.fppvm.tv.panel.LedProfile.SUBSTRATE_BLACK_MASK,
            app.fppvm.tv.panel.LedProfile.SUBSTRATE_GREY_PCB
        )

        private fun substrateName(c: Int) = when (c) {
            app.fppvm.tv.panel.LedProfile.SUBSTRATE_GREY_PCB -> "grey PCB"
            app.fppvm.tv.panel.LedProfile.SUBSTRATE_BLACK_MASK -> "black mask"
            else -> "black"
        }

        private fun <T> cycle(values: List<T>, current: T, delta: Int): T {
            if (values.isEmpty()) return current
            val i = values.indexOf(current)
            val n = values.size
            return values[(((i + delta) % n) + n) % n]
        }

        /**
         * Channel numbers span millions, so a fixed step of 1 is useless on a remote control.
         * Step by whole matrices instead: one press moves to the next panel's worth of channels,
         * which is how a show is actually laid out.
         */
        private fun channelStep(c: MatrixConfig): Int = c.channelCount.coerceAtLeast(3)
    }
}
