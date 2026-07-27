package app.fppvm.tv.render

import app.fppvm.tv.config.MatrixConfig

/**
 * Generates channel data in exactly the layout an FSEQ would supply, so the idle pattern proves
 * the whole render path — channel order, row stride, flips, gamma — without a player attached.
 * If the test pattern looks right and a show does not, the fault is upstream of the renderer.
 */
class TestPattern(private var config: MatrixConfig) {

    private var buffer = ByteArray(config.channelCount)

    fun reconfigure(next: MatrixConfig) {
        config = next
        if (buffer.size != next.channelCount) buffer = ByteArray(next.channelCount)
    }

    /**
     * A drifting hue ramp with a one-pixel white border and a marked origin pixel.
     *
     * The border makes cropping/overscan obvious; the single red pixel at the top-left marks
     * channel 0 so a wrong flip or transpose is visible at a glance rather than looking merely
     * "different".
     */
    fun render(timeMs: Long): ByteArray {
        val w = config.width
        val h = config.height
        val phase = (timeMs % 4000L) / 4000.0
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val onBorder = x == 0 || y == 0 || x == w - 1 || y == h - 1
                val r: Int
                val g: Int
                val b: Int
                when {
                    x == 0 && y == 0 -> {
                        r = 255; g = 0; b = 0 // origin marker: first three channels
                    }
                    onBorder -> {
                        r = 255; g = 255; b = 255
                    }
                    else -> {
                        val hue = ((x.toDouble() / w) + phase) % 1.0
                        val v = 0.35 + 0.65 * (y.toDouble() / maxOf(1, h - 1))
                        val rgb = hsvToRgb(hue, 1.0, v)
                        r = rgb[0]; g = rgb[1]; b = rgb[2]
                    }
                }
                // Written in channel order (R,G,B as the sequence would store them); the raster's
                // colorOrder remap is applied on the way out, same as for a real frame.
                buffer[i] = r.toByte()
                buffer[i + 1] = g.toByte()
                buffer[i + 2] = b.toByte()
                i += 3
            }
        }
        return buffer
    }

    companion object {
        fun hsvToRgb(h: Double, s: Double, v: Double): IntArray {
            val i = (h * 6).toInt()
            val f = h * 6 - i
            val p = v * (1 - s)
            val q = v * (1 - f * s)
            val t = v * (1 - (1 - f) * s)
            val (r, g, b) = when (i % 6) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
            return intArrayOf((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }
    }
}
