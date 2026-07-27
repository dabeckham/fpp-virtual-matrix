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

    fun reconfigure(next: MatrixConfig) {
        val sizeChanged = next.width != config.width || next.height != config.height
        val toneChanged = next.brightness != config.brightness || next.gamma != config.gamma
        config = next
        if (sizeChanged) pixels = IntArray(next.width * next.height)
        if (toneChanged || sizeChanged) lut = buildLut(next)
    }

    /**
     * Renders `width * height * 3` bytes starting at [offset]. Missing bytes render black rather
     * than throwing, so a short frame degrades to a partial image instead of killing the show.
     */
    fun render(channels: ByteArray, offset: Int = 0): IntArray {
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

    fun blank(): IntArray {
        java.util.Arrays.fill(pixels, 0xFF000000.toInt())
        return pixels
    }

    companion object {
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
