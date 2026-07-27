package app.fppvm.tv.config

import app.fppvm.tv.panel.Downsample
import app.fppvm.tv.panel.LedProfile
import app.fppvm.tv.panel.LedProfiles
import app.fppvm.tv.panel.EmitterShape
import app.fppvm.tv.panel.PanelMode
import org.json.JSONObject

/**
 * Everything that defines this device's virtual matrix. Pure data + JSON codec, no Android types,
 * so the whole thing is unit-testable and can be sideloaded with
 * `adb shell am start -n app.fppvm.tv/.MainActivity --es config '<json>'`.
 */
data class MatrixConfig(
    /** Matrix size in pixels. `width * height * 3` channels are consumed. */
    val width: Int = 64,
    val height: Int = 32,

    /**
     * First channel of the matrix, 1-based to match FPP's channel-output UI. FPP subtracts one
     * internally; so do we, in exactly one place ([startChannelZeroBased]).
     */
    val startChannel: Int = 1,

    /** Byte order of each pixel triplet in the channel data. */
    val colorOrder: ColorOrder = ColorOrder.RGB,

    val flipHorizontal: Boolean = false,
    /** FPP calls this "invert" on the Virtual Matrix output. */
    val flipVertical: Boolean = false,
    /** Swap rows and columns — for a matrix whose channels run down columns rather than across rows. */
    val transpose: Boolean = false,

    /** 0-100. Applied with [gamma] in the same lookup table, so it costs nothing per pixel. */
    val brightness: Int = 100,
    val gamma: Float = 1.0f,

    val scaleMode: ScaleMode = ScaleMode.FIT,
    val pixelStyle: PixelStyle = PixelStyle.SOLID,
    /** Percentage of each cell left dark when [pixelStyle] draws gaps. 0-90. */
    val pixelGapPercent: Int = 20,

    /** Listen for MultiSync and follow a player's timing. */
    val multiSyncEnabled: Boolean = true,
    /**
     * Accept live channel data pushed over DDP, and answer DDP discovery.
     *
     * Separate from MultiSync because they solve different halves of the problem: MultiSync
     * follows a rendered show, DDP shows what a sequencer is producing right now.
     */
    val ddpEnabled: Boolean = true,
    /** Hostname advertised in ping packets; blank means use the device's own. */
    val hostname: String = "",
    /**
     * Pull a sequence from the master's HTTP API when a sync packet names one we don't have.
     * This is what removes the "copy the fseq to every remote" chore.
     */
    val autoFetchSequences: Boolean = true,
    /** Overrides the master address learned from sync packets. Blank = learn it. */
    val masterHost: String = "",
    /** FPP's `remoteOffset`, milliseconds. Positive = render later than the master says. */
    val remoteOffsetMs: Int = 0,

    /** What to show when nothing is playing. */
    val idleMode: IdleMode = IdleMode.BLACK,
    /** Keep the screen on while the app is foreground. */
    val keepScreenOn: Boolean = true,
    /** Draw the stats overlay. */
    val showOverlay: Boolean = false,
    /** Colour depth of the render + upload path. See [ColorDepth]. */
    val colorDepth: ColorDepth = ColorDepth.AUTO,

    // --- Physical panel simulation. The look is specified in millimetres and the grid is solved
    // for, because panels differ between installs but "this should look like P10" does not.
    val panelMode: PanelMode = PanelMode.OFF,
    /**
     * Which profile these values were loaded from. A profile *seeds* the fields below rather than
     * overriding them, so every parameter stays independently adjustable afterwards; [profileEdited]
     * records that it no longer matches what was loaded.
     */
    val profileId: String = "bullet_25",
    val pitchMm: Float = 25.4f,
    val emitterMm: Float = 12.0f,
    val emitterShape: EmitterShape = EmitterShape.ROUND,
    /** Colour of the unlit surface between emitters. */
    val substrateColor: Int = LedProfile.SUBSTRATE_NONE,
    /** 0 = trust the display's reported dpi (after a sanity check). */
    val panelDpi: Float = 0f,
    /** 0 = hard-edged apertures; higher spreads the bloom towards the cell corner. */
    val bloomPercent: Int = 45,
    val downsample: Downsample = Downsample.MAX,

    /** Serve the config page and the FPP file API from this device. */
    val webServerEnabled: Boolean = true,
    val webPort: Int = 8080,
    /** Blank disables auth, matching how the rest of the show kit is normally run. */
    val webPassword: String = "",

    /** Write sequences to a USB stick when one is mounted. Reads always span every volume. */
    val preferRemovableStorage: Boolean = true,
    /**
     * Take the screen back if something steals focus mid-show. Only acts while a show is playing,
     * and a long BACK press stands it down — see FocusGuard.
     */
    val holdFocus: Boolean = false
) {
    /**
     * HIGH keeps the full ARGB_8888 pipeline. FAST renders straight to RGB565, halving the bytes
     * pushed per frame. AUTO picks FAST once the matrix is big enough for that to matter — on a
     * panel that composites at 16 bits it is visually identical, and on a large matrix it is the
     * difference between hitting the frame budget and not.
     */
    enum class ColorDepth { AUTO, HIGH, FAST }

    enum class ColorOrder(val r: Int, val g: Int, val b: Int) {
        RGB(0, 1, 2), RBG(0, 2, 1), GRB(1, 0, 2), GBR(2, 0, 1), BRG(1, 2, 0), BGR(2, 1, 0)
    }

    enum class ScaleMode { FIT, FILL, STRETCH }
    enum class PixelStyle { SOLID, GRID, DOTS }
    enum class IdleMode { BLACK, TEST_PATTERN, STATUS }

    /** Channel index FPP would use internally (0-based). */
    val startChannelZeroBased: Int get() = (startChannel - 1).coerceAtLeast(0)

    val pixelCount: Int get() = width * height

    val panelEnabled: Boolean get() = panelMode != PanelMode.OFF

    /**
     * True when the appearance no longer matches the profile it was loaded from.
     *
     * Derived rather than stored: it used to be a flag every caller had to remember to set, and
     * the D-pad set it while the web API and the intent extra did not, so the same edit showed as
     * modified one way and unmodified another.
     */
    val profileEdited: Boolean
        get() {
            val p = LedProfiles.byId(profileId) ?: return true
            return p.pitchMm != pitchMm || p.emitterMm != emitterMm || p.shape != emitterShape ||
                p.substrate != substrateColor ||
                p.bloomPercent != bloomPercent
        }

    /** Label for the current look, marked when it has drifted from the profile it came from. */
    val profileLabel: String
        get() {
            val base = LedProfiles.byId(profileId)?.label ?: profileId
            return if (profileEdited) "$base (modified)" else base
        }

    /** Loads a profile's appearance into the config without touching anything else. */
    fun applyProfile(p: LedProfile): MatrixConfig = copy(
        profileId = p.id,
        pitchMm = p.pitchMm,
        emitterMm = p.emitterMm,
        emitterShape = p.shape,
        substrateColor = p.substrate,
        bloomPercent = p.bloomPercent
    ).validated()

    /**
     * Retained so edit sites read the same everywhere. Divergence is now computed from the values,
     * so this no longer has to do anything.
     */
    fun edited(): MatrixConfig = this

    /** Resolved colour depth: AUTO becomes FAST above a quarter of a megapixel. */
    val useLowColor: Boolean
        get() = when (colorDepth) {
            ColorDepth.HIGH -> false
            ColorDepth.FAST -> true
            ColorDepth.AUTO -> pixelCount > 250_000
        }
    val channelCount: Int get() = width * height * 3

    /**
     * The channel range this device claims, as FPP writes it into a ping packet.
     *
     * Both ends are zero-based and inclusive — FPP builds it as
     * `snprintf("%d-%d", start, start + count - 1)` (`MultiSync.cpp`, `createRanges`). A count in
     * the second position happens to look right when the start channel is 1 and is nonsense
     * otherwise: start channel 10001 with 6144 channels would advertise "10000-6144", which every
     * reader parses as a negative span.
     */
    fun rangesString(): String {
        val first = startChannelZeroBased
        return "$first-${first + channelCount - 1}"
    }

    fun validated(): MatrixConfig = copy(
        width = width.coerceIn(1, MAX_DIMENSION),
        height = height.coerceIn(1, MAX_DIMENSION),
        startChannel = startChannel.coerceIn(1, MAX_CHANNEL),
        brightness = brightness.coerceIn(1, 100),
        gamma = gamma.coerceIn(0.1f, 4.0f),
        pixelGapPercent = pixelGapPercent.coerceIn(0, 90),
        remoteOffsetMs = remoteOffsetMs.coerceIn(-10_000, 10_000),
        pitchMm = pitchMm.coerceIn(0.5f, 200f),
        // An emitter can never be larger than its pitch; that is what makes the dark fraction.
        emitterMm = emitterMm.coerceIn(0.2f, pitchMm.coerceIn(0.5f, 200f)),
        panelDpi = if (panelDpi <= 0f) 0f else panelDpi.coerceIn(10f, 1200f),
        bloomPercent = bloomPercent.coerceIn(0, 100),
        webPort = webPort.coerceIn(1024, 65535)
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("width", width)
        put("height", height)
        put("startChannel", startChannel)
        put("colorOrder", colorOrder.name)
        put("flipHorizontal", flipHorizontal)
        put("flipVertical", flipVertical)
        put("transpose", transpose)
        put("brightness", brightness)
        put("gamma", gamma.toDouble())
        put("scaleMode", scaleMode.name)
        put("pixelStyle", pixelStyle.name)
        put("pixelGapPercent", pixelGapPercent)
        put("multiSyncEnabled", multiSyncEnabled)
        put("ddpEnabled", ddpEnabled)
        put("hostname", hostname)
        put("autoFetchSequences", autoFetchSequences)
        put("masterHost", masterHost)
        put("remoteOffsetMs", remoteOffsetMs)
        put("idleMode", idleMode.name)
        put("keepScreenOn", keepScreenOn)
        put("showOverlay", showOverlay)
        put("colorDepth", colorDepth.name)
        put("panelMode", panelMode.name)
        put("profileId", profileId)
        put("profileEdited", profileEdited)   // derived; ignored on the way back in
        put("substrateColor", substrateColor)
        put("webServerEnabled", webServerEnabled)
        put("webPort", webPort)
        put("preferRemovableStorage", preferRemovableStorage)
        put("holdFocus", holdFocus)
        // profileLabel is derived, and read-only: the page shows it, fromJson ignores it.
        put("profileLabel", profileLabel)
        put("pitchMm", pitchMm.toDouble())
        put("emitterMm", emitterMm.toDouble())
        put("emitterShape", emitterShape.name)
        put("panelDpi", panelDpi.toDouble())
        put("bloomPercent", bloomPercent)
        put("downsample", downsample.name)
    }

    companion object {
        /**
         * Upper bound per axis. 1280x720 (a 1:1 panel-resolution matrix) is 2 764 800 channels,
         * still inside FPP's own channel ceiling, so the limit is set by the device rather than
         * the protocol.
         */
        const val MAX_DIMENSION = 4096

        /** FPP's own channel ceiling (FPPD_MAX_CHANNELS). */
        const val MAX_CHANNEL = 8_388_608

        val DEFAULT = MatrixConfig()

        /**
         * Parses [json], falling back to [base] for anything missing or unparseable. Never throws
         * on a bad field — a typo in a sideloaded override should not brick the display.
         */
        fun fromJson(json: String, base: MatrixConfig = DEFAULT): MatrixConfig =
            try {
                fromJson(JSONObject(json), base)
            } catch (t: Throwable) {
                base
            }

        fun fromJson(o: JSONObject, base: MatrixConfig = DEFAULT): MatrixConfig = MatrixConfig(
            width = o.optInt("width", base.width),
            height = o.optInt("height", base.height),
            startChannel = o.optInt("startChannel", base.startChannel),
            colorOrder = enumOr(o.optString("colorOrder"), base.colorOrder),
            flipHorizontal = o.optBoolean("flipHorizontal", base.flipHorizontal),
            flipVertical = o.optBoolean("flipVertical", base.flipVertical),
            transpose = o.optBoolean("transpose", base.transpose),
            brightness = o.optInt("brightness", base.brightness),
            gamma = o.optDouble("gamma", base.gamma.toDouble()).toFloat(),
            scaleMode = enumOr(o.optString("scaleMode"), base.scaleMode),
            pixelStyle = enumOr(o.optString("pixelStyle"), base.pixelStyle),
            pixelGapPercent = o.optInt("pixelGapPercent", base.pixelGapPercent),
            multiSyncEnabled = o.optBoolean("multiSyncEnabled", base.multiSyncEnabled),
            ddpEnabled = o.optBoolean("ddpEnabled", base.ddpEnabled),
            hostname = o.optString("hostname", base.hostname),
            autoFetchSequences = o.optBoolean("autoFetchSequences", base.autoFetchSequences),
            masterHost = o.optString("masterHost", base.masterHost),
            remoteOffsetMs = o.optInt("remoteOffsetMs", base.remoteOffsetMs),
            idleMode = enumOr(o.optString("idleMode"), base.idleMode),
            keepScreenOn = o.optBoolean("keepScreenOn", base.keepScreenOn),
            showOverlay = o.optBoolean("showOverlay", base.showOverlay),
            colorDepth = enumOr(o.optString("colorDepth"), base.colorDepth),
            panelMode = enumOr(o.optString("panelMode"), base.panelMode),
            profileId = o.optString("profileId", base.profileId),
            substrateColor = o.optInt("substrateColor", base.substrateColor),
            pitchMm = o.optDouble("pitchMm", base.pitchMm.toDouble()).toFloat(),
            emitterMm = o.optDouble("emitterMm", base.emitterMm.toDouble()).toFloat(),
            emitterShape = enumOr(o.optString("emitterShape"), base.emitterShape),
            panelDpi = o.optDouble("panelDpi", base.panelDpi.toDouble()).toFloat(),
            bloomPercent = o.optInt("bloomPercent", base.bloomPercent),
            downsample = enumOr(o.optString("downsample"), base.downsample),
            webServerEnabled = o.optBoolean("webServerEnabled", base.webServerEnabled),
            webPort = o.optInt("webPort", base.webPort),
            webPassword = o.optString("webPassword", base.webPassword),
            preferRemovableStorage = o.optBoolean("preferRemovableStorage", base.preferRemovableStorage),
            holdFocus = o.optBoolean("holdFocus", base.holdFocus)
        ).validated()

        private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T {
            if (name.isNullOrBlank()) return fallback
            return try {
                enumValueOf<T>(name.uppercase())
            } catch (t: IllegalArgumentException) {
                fallback
            }
        }
    }
}
