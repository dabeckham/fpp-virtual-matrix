package app.fppvm.tv.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.fppvm.tv.MainActivity
import app.fppvm.tv.config.ConfigStore
import app.fppvm.tv.fseq.SequenceStore
import app.fppvm.tv.fseq.ZstdSupport
import app.fppvm.tv.player.MatrixPlayer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Live sync/render figures, cached sequences and the device's own MultiSync view of the show.
 *
 * This screen exists because the failure modes here are invisible from the picture alone: a matrix
 * that is one frame behind, or reading the wrong channel block, or quietly falling back to a
 * sequence it downloaded yesterday, all look like "it works" until they don't.
 */
class DiagnosticsActivity : ComponentActivity() {

    private lateinit var text: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 36, 48, 36)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#101214"))
                addView(
                    LinearLayout(this@DiagnosticsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text)
                    }
                )
            }
        )
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun render() {
        val config = ConfigStore(this).load()
        val player = MainActivity.APP_PLAYER
        val status = player?.currentStatus() ?: MatrixPlayer.Status()
        val ms = status.multiSync
        val store = SequenceStore(File(filesDir, "sequences"))

        val sb = StringBuilder()
        sb.appendLine("FPP VIRTUAL MATRIX — DIAGNOSTICS")
        sb.appendLine(stamp())
        sb.appendLine()

        sb.appendLine("MATRIX")
        sb.appendLine("  geometry      ${config.width} x ${config.height}  (${config.pixelCount} pixels)")
        sb.appendLine(
            "  channels      ${config.startChannel} .. " +
                "${config.startChannel + config.channelCount - 1}  (${config.channelCount})"
        )
        sb.appendLine("  colour order  ${config.colorOrder}   flipH=${config.flipHorizontal} flipV=${config.flipVertical} transpose=${config.transpose}")
        sb.appendLine("  brightness    ${config.brightness}%   gamma ${"%.2f".format(config.gamma)}")
        if (config.panelEnabled) {
            sb.appendLine()
            sb.appendLine("PANEL SIMULATION")
            sb.appendLine("  mode          ${config.panelMode}   profile ${config.profileLabel}")
            sb.appendLine("  requested     pitch ${"%.2f".format(config.pitchMm)} mm   emitter ${"%.2f".format(config.emitterMm)} mm   ${config.emitterShape}")
            sb.appendLine("  surface       substrate #${Integer.toHexString(config.substrateColor)}   louvre ${config.louvrePercent}%")
            val g = status.panel
            if (g == null) {
                sb.appendLine("  achieved      (not solved yet)")
            } else {
                sb.appendLine("  achieved      pitch ${"%.2f".format(g.achievedPitchMm)} mm   emitter ${"%.2f".format(g.achievedEmitterMm)} mm")
                sb.appendLine("  grid          ${g.cols} x ${g.rows} = ${g.cellCount} cells   cell ${"%.2f".format(g.cellPx)} px   emitter ${"%.2f".format(g.emitterPx)} px")
                sb.appendLine("  lit area      ${"%.1f".format(g.openAreaPercent)}%   dpi ${"%.1f".format(g.dpi)}${if (config.panelDpi > 0f) " (manual)" else " (reported)"}")
                sb.appendLine("  resample      ${config.downsample} from ${config.width} x ${config.height}   bloom ${config.bloomPercent}%")
                if (g.degraded) sb.appendLine("  DEGRADED      ${g.note}")
                else if (g.note.isNotEmpty()) sb.appendLine("  note          ${g.note}")
            }
        }
        sb.appendLine()

        sb.appendLine("PLAYBACK")
        sb.appendLine("  state         ${status.state}")
        sb.appendLine("  sequence      ${status.sequence.ifBlank { "-" }}")
        sb.appendLine("  frame         ${status.frame} / ${status.totalFrames}   step ${status.stepTimeMs} ms")
        sb.appendLine("  drift         ${"%.2f".format(status.driftFrames)} frames   hard resyncs ${status.resyncJumps}")
        sb.appendLine("  rendered      ${status.renderedFrames}   dropped ${status.droppedFrames}   decode errors ${status.decodeErrors}")
        sb.appendLine("  throughput    ${"%.1f".format(status.fps)} fps   decode ${"%.1f".format(status.decodeMs)} ms   paint ${"%.1f".format(status.paintMs)} ms")
        sb.appendLine("  budget        ${status.stepTimeMs} ms per frame; used ${"%.0f".format(if (status.stepTimeMs > 0) (status.decodeMs + status.paintMs) * 100f / status.stepTimeMs else 0f)}%")
        if (status.message.isNotBlank()) sb.appendLine("  note          ${status.message}")
        sb.appendLine()

        sb.appendLine("MULTISYNC  (UDP 32320, group 239.70.80.80)")
        sb.appendLine("  enabled       ${config.multiSyncEnabled}   listening ${MainActivity.APP_CLIENT?.isRunning() ?: false}")
        sb.appendLine("  local address ${MainActivity.APP_CLIENT?.localIpv4() ?: "?"}")
        sb.appendLine("  master        ${ms.lastMaster.ifBlank { "-" }}")
        sb.appendLine("  seq open/start/stop   ${ms.syncOpen} / ${ms.syncStart} / ${ms.syncStop}")
        sb.appendLine("  seq sync packets      ${ms.syncFrames}")
        sb.appendLine("  media sync (ignored)  ${ms.mediaPackets}")
        sb.appendLine("  pings ${ms.pings}   blanks ${ms.blanks}   commands ${ms.commands}")
        sb.appendLine("  ignored ${ms.ignored}   malformed ${ms.malformed}")
        sb.appendLine()

        val systems = player?.knownSystems().orEmpty()
        sb.appendLine("SYSTEMS SEEN (${systems.size})")
        if (systems.isEmpty()) {
            sb.appendLine("  none yet — a player announces itself on discovery or roughly hourly")
        } else {
            for (s in systems) {
                sb.appendLine(
                    "  ${s.address.padEnd(16)} ${s.hostname.padEnd(18)} ${s.model.take(24).padEnd(25)} " +
                        "v${s.majorVersion}.${s.minorVersion} mode=${s.fppMode}" +
                        if (s.isSendingMultiSync) " [master]" else ""
                )
            }
        }
        sb.appendLine()

        val cached = store.listCached()
        sb.appendLine("CACHED SEQUENCES (${cached.size}, ${fmtBytes(store.totalBytes())})")
        for (f in cached.take(25)) {
            sb.appendLine("  ${fmtBytes(f.length()).padStart(10)}  ${f.name}")
        }
        if (cached.size > 25) sb.appendLine("  ... ${cached.size - 25} more")
        sb.appendLine()

        sb.appendLine("RUNTIME")
        val rt = Runtime.getRuntime()
        sb.appendLine("  heap          ${fmtBytes(rt.totalMemory() - rt.freeMemory())} used of ${fmtBytes(rt.maxMemory())}")
        sb.appendLine("  zstd native   ${if (ZstdSupport.isAvailable()) "ok" else "UNAVAILABLE (${ZstdSupport.lastError})"}")
        sb.appendLine("  device        ${android.os.Build.MODEL}  Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("  abi           ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")

        text.text = sb.toString()
    }

    /**
     * UTC plus the local offset. Reading a drift figure from a screenshot is worthless if you
     * cannot tell which instant it belongs to, and an offset-free local time is ambiguous twice a
     * year.
     */
    private fun stamp(): String {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val local = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val now = Date()
        return "${utc.format(now)}   local ${local.format(now)} (${TimeZone.getDefault().id})"
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GiB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MiB".format(b.toDouble() / (1L shl 20))
        b >= 1L shl 10 -> "%.1f KiB".format(b.toDouble() / (1L shl 10))
        else -> "$b B"
    }
}
