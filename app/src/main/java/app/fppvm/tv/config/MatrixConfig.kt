package app.fppvm.tv.config

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
    val showOverlay: Boolean = false
) {
    enum class ColorOrder(val r: Int, val g: Int, val b: Int) {
        RGB(0, 1, 2), RBG(0, 2, 1), GRB(1, 0, 2), GBR(2, 0, 1), BRG(1, 2, 0), BGR(2, 1, 0)
    }

    enum class ScaleMode { FIT, FILL, STRETCH }
    enum class PixelStyle { SOLID, GRID, DOTS }
    enum class IdleMode { BLACK, TEST_PATTERN, STATUS }

    /** Channel index FPP would use internally (0-based). */
    val startChannelZeroBased: Int get() = (startChannel - 1).coerceAtLeast(0)

    val pixelCount: Int get() = width * height
    val channelCount: Int get() = width * height * 3

    /** The `"start-count"` string FPP expects in a ping packet's ranges field. */
    fun rangesString(): String = "${startChannelZeroBased}-${channelCount}"

    fun validated(): MatrixConfig = copy(
        width = width.coerceIn(1, MAX_DIMENSION),
        height = height.coerceIn(1, MAX_DIMENSION),
        startChannel = startChannel.coerceIn(1, MAX_CHANNEL),
        brightness = brightness.coerceIn(1, 100),
        gamma = gamma.coerceIn(0.1f, 4.0f),
        pixelGapPercent = pixelGapPercent.coerceIn(0, 90),
        remoteOffsetMs = remoteOffsetMs.coerceIn(-10_000, 10_000)
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
        put("hostname", hostname)
        put("autoFetchSequences", autoFetchSequences)
        put("masterHost", masterHost)
        put("remoteOffsetMs", remoteOffsetMs)
        put("idleMode", idleMode.name)
        put("keepScreenOn", keepScreenOn)
        put("showOverlay", showOverlay)
    }

    companion object {
        const val MAX_DIMENSION = 1024

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
            hostname = o.optString("hostname", base.hostname),
            autoFetchSequences = o.optBoolean("autoFetchSequences", base.autoFetchSequences),
            masterHost = o.optString("masterHost", base.masterHost),
            remoteOffsetMs = o.optInt("remoteOffsetMs", base.remoteOffsetMs),
            idleMode = enumOr(o.optString("idleMode"), base.idleMode),
            keepScreenOn = o.optBoolean("keepScreenOn", base.keepScreenOn),
            showOverlay = o.optBoolean("showOverlay", base.showOverlay)
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
