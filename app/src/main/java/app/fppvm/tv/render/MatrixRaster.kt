package app.fppvm.tv.render

import app.fppvm.tv.config.MatrixConfig
import kotlin.math.pow

/**
 * Turns a flat block of FSEQ channel bytes into an ARGB pixel grid.
 *
 * This is the actual port of FPP's Virtual Matrix output (`src/channeloutput/FBMatrix.cpp`
 * `PrepData`): three channels per pixel, laid out row-major from the top-left, with optional
 * vertical invert and horizontal flip. FPP writes that into a Linux framebuffer; here it becomes
 * an `IntArray` a `Bitmap` can consume.
 *
 * Deliberately free of Android types so the geometry and colour maths are covered by JVM tests —
 * an off-by-one in the row stride is invisible on a rainbow test pattern and glaring on a show.
 */
class MatrixRaster(config: MatrixConfig) {

    var config: MatrixConfig = config
        private set

    val width: Int get() = config.width
    val height: Int get() = config.height

    /** ARGB_8888 pixels, row-major, reused across frames. */
    var pixels: IntArray = IntArray(config.width * config.height)
        private set

    private var lut: IntArray = buildLut(config)
    private var identityTone: Boolean = isIdentityTone(config)

    fun reconfigure(next: MatrixConfig) {
        val sizeChanged = next.width != config.width || next.height != config.height
        val toneChanged = next.brightness != config.brightness || next.gamma != config.gamma
        config = next
        if (sizeChanged) pixels = IntArray(next.width * next.height)
        if (toneChanged || sizeChanged) lut = buildLut(next)
        identityTone = isIdentityTone(next)
    }

    /**
     * Renders `width * height * 3` bytes starting at [offset]. Missing bytes render black rather
     * than throwing, so a short frame degrades to a partial image instead of killing the show.
     */
    fun render(channels: ByteArray, offset: Int = 0): IntArray {
        // A 1280x720 matrix is 921 600 pixels a frame; at 20 fps the general loop's per-pixel
        // index arithmetic is most of the frame budget on this class of SoC. The overwhelmingly
        // common case — no flips, no transpose, RGB order — is a straight sequential walk, so it
        // gets its own loop rather than paying for generality 18 million times a second.
        if (!config.flipHorizontal && !config.flipVertical && !config.transpose &&
            config.colorOrder == MatrixConfig.ColorOrder.RGB
        ) {
            val n = config.width * config.height
            if (offset >= 0 && offset + n * 3 <= channels.size) return renderSequential(channels, offset, n)
        }
        return renderGeneral(channels, offset)
    }

    private fun renderSequential(channels: ByteArray, offset: Int, n: Int): IntArray {
        val out = pixels
        var si = offset
        if (identityTone) {
            // Gamma 1.0 at full brightness is a pass-through, so skip the table entirely.
            for (di in 0 until n) {
                out[di] = (0xFF shl 24) or
                    ((channels[si].toInt() and 0xFF) shl 16) or
                    ((channels[si + 1].toInt() and 0xFF) shl 8) or
                    (channels[si + 2].toInt() and 0xFF)
                si += 3
            }
        } else {
            val table = lut
            for (di in 0 until n) {
                out[di] = (0xFF shl 24) or
                    (table[channels[si].toInt() and 0xFF] shl 16) or
                    (table[channels[si + 1].toInt() and 0xFF] shl 8) or
                    table[channels[si + 2].toInt() and 0xFF]
                si += 3
            }
        }
        return out
    }

    private fun renderGeneral(channels: ByteArray, offset: Int = 0): IntArray {
        val w = config.width
        val h = config.height
        val order = config.colorOrder
        val flipH = config.flipHorizontal
        val flipV = config.flipVertical
        val transpose = config.transpose
        val out = pixels
        val table = lut
        val available = channels.size - offset

        var di = 0
        for (y in 0 until h) {
            val sy = if (flipV) h - 1 - y else y
            for (x in 0 until w) {
                val sx = if (flipH) w - 1 - x else x
                // Row-major across the matrix, unless the channels were wired down columns.
                val pixelIndex = if (transpose) sx * h + sy else sy * w + sx
                val si = offset + pixelIndex * 3
                if (si + 2 < offset + available) {
                    val r = table[channels[si + order.r].toInt() and 0xFF]
                    val g = table[channels[si + order.g].toInt() and 0xFF]
                    val b = table[channels[si + order.b].toInt() and 0xFF]
                    out[di] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    out[di] = 0xFF000000.toInt()
                }
                di++
            }
        }
        return out
    }

    /** RGB565 output, half the bytes of [pixels]. Allocated only if the 565 path is used. */
    var pixels565: ShortArray = ShortArray(0)
        private set

    /**
     * Renders straight to RGB565.
     *
     * The test panel composites at 16 bits (proven by comparing a screenshot against known source
     * values), so an ARGB_8888 intermediate buys nothing there and costs twice the bytes through
     * the upload and the blit. At 921 600 pixels a frame that is the difference between fitting a
     * 50 ms budget and not.
     */
    fun render565(channels: ByteArray, offset: Int = 0): ShortArray {
        val n = config.width * config.height
        if (pixels565.size != n) pixels565 = ShortArray(n)
        val out = pixels565
        if (!config.flipHorizontal && !config.flipVertical && !config.transpose &&
            config.colorOrder == MatrixConfig.ColorOrder.RGB &&
            offset >= 0 && offset + n * 3 <= channels.size
        ) {
            var si = offset
            if (identityTone) {
                for (di in 0 until n) {
                    val r = channels[si].toInt() and 0xF8
                    val g = channels[si + 1].toInt() and 0xFC
                    val b = (channels[si + 2].toInt() and 0xFF) ushr 3
                    out[di] = ((r shl 8) or (g shl 3) or b).toShort()
                    si += 3
                }
            } else {
                val table = lut
                for (di in 0 until n) {
                    val r = table[channels[si].toInt() and 0xFF] and 0xF8
                    val g = table[channels[si + 1].toInt() and 0xFF] and 0xFC
                    val b = table[channels[si + 2].toInt() and 0xFF] ushr 3
                    out[di] = ((r shl 8) or (g shl 3) or b).toShort()
                    si += 3
                }
            }
            return out
        }
        // Any non-trivial orientation: reuse the general ARGB path and pack down, which keeps one
        // implementation of the geometry rather than two that can drift apart.
        val argb = renderGeneral(channels, offset)
        for (i in 0 until n) {
            val p = argb[i]
            out[i] = ((((p ushr 16) and 0xF8) shl 8) or (((p ushr 8) and 0xFC) shl 3) or ((p and 0xFF) ushr 3)).toShort()
        }
        return out
    }

    fun blank565(): ShortArray {
        val n = config.width * config.height
        if (pixels565.size != n) pixels565 = ShortArray(n)
        java.util.Arrays.fill(pixels565, 0)
        return pixels565
    }

    fun blank(): IntArray {
        java.util.Arrays.fill(pixels, 0xFF000000.toInt())
        return pixels
    }

    companion object {
        /** True when the tone controls would leave every value untouched. */
        fun isIdentityTone(c: MatrixConfig): Boolean = c.brightness >= 100 && c.gamma == 1.0f

        /**
         * Combined gamma + brightness table.
         *
         * `out = 255 * (in/255)^gamma * brightness/100`. Gamma 1.0 is a straight pass-through, so
         * by default the panel shows exactly the byte values the sequence contains — matching
         * FPP's Virtual Matrix, which applies no correction of its own (any gamma the show wants
         * is baked in by FPP's output processors upstream). The knob is here because a TV's
         * transfer curve is nothing like a string of WS2811s and a show usually needs taming.
         */
        fun buildLut(config: MatrixConfig): IntArray {
            val scale = config.brightness.coerceIn(0, 100) / 100.0
            val gamma = config.gamma.coerceIn(0.1f, 4.0f).toDouble()
            return IntArray(256) { i ->
                val linear = if (gamma == 1.0) i / 255.0 else (i / 255.0).pow(gamma)
                (linear * scale * 255.0 + 0.5).toInt().coerceIn(0, 255)
            }
        }
    }
}
