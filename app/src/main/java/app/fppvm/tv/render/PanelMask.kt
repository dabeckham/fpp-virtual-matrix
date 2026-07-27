package app.fppvm.tv.render

import android.graphics.Bitmap
import app.fppvm.tv.panel.EmitterShape
import app.fppvm.tv.panel.PanelGeometry
import java.nio.ByteBuffer

/**
 * Builds the aperture-and-bloom overlay that turns a grid of flat colour blocks into something
 * that reads as physical emitters.
 *
 * Alpha 0 lets a cell's colour through untouched, 255 paints it black, and the ramp between is the
 * bloom. Because the bloom is the cell's own colour showing through a partial mask, it is tinted
 * correctly for free — no per-cell tinting work at all.
 *
 * The obvious alternative is a pre-rendered radial glow sprite blended additively over each lit
 * cell. That is the right answer on a GPU. `SurfaceHolder.lockCanvas` hands back a *software*
 * canvas, so on a 27x15 grid that would be 405 alpha-blended sprite blits — about 3.5 million
 * blended pixels — every frame, on the CPU that is already the bottleneck here. This costs one
 * cached blit no matter how many cells there are.
 *
 * The trade-off: bloom cannot spill past a cell boundary, because beyond it lies the neighbour's
 * colour. At any realistic bloom radius (well under half the pitch) it never wants to.
 */
object PanelMask {

    private const val PROFILE_STEPS = 256

    /** 8x8 ordered dither, centred on zero and scaled to roughly +/-2 alpha levels. */
    private val BAYER8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21
    ).map { (it - 32) / 16 }.toIntArray()

    /** Cache key for a built mask; rebuild only when one of these changes. */
    fun keyFor(g: PanelGeometry, bloomPercent: Int): String =
        "%d:%d:%.3f:%.3f:%s:%d".format(g.cols, g.rows, g.cellPx, g.emitterPx, g.shape, bloomPercent)

    /**
     * Computes the alpha map. Separated from bitmap creation so the maths is testable on the JVM.
     *
     * No `sqrt` in the hot loop for square emitters, and for round ones it is only reached inside
     * the bloom ring. Two precomputed axis tables supply the offset from the nearest cell centre,
     * which is what makes a fractional pitch work without accumulating drift.
     */
    fun buildAlpha(g: PanelGeometry, bloomPercent: Int): ByteArray? {
        val w = g.widthPx
        val h = g.heightPx
        if (w <= 0 || h <= 0 || g.cellPx <= 0f) return null

        val rEmit = g.emitterPx / 2f
        // The bloom may reach the cell corner but no further.
        val rMax = g.cellPx * 0.70f
        val rBloom = (rEmit + (rMax - rEmit) * (bloomPercent.coerceIn(0, 100) / 100f))
            .coerceAtLeast(rEmit + 0.5f)

        // Squared rather than linear falloff: bright close in, fading fast, which reads as a lamp.
        // A linear ramp reads as fog.
        val profile = IntArray(PROFILE_STEPS + 1) { i ->
            val t = i.toFloat() / PROFILE_STEPS
            val brightness = (1f - t) * (1f - t)
            ((1f - brightness) * 255f).toInt().coerceIn(0, 255)
        }

        val round = g.shape == EmitterShape.ROUND
        val half = g.cellPx / 2f
        val cell = g.cellPx

        val ax = FloatArray(w)
        for (x in 0 until w) {
            val within = x - Math.floor(x / cell.toDouble()).toFloat() * cell
            ax[x] = Math.abs(within - half)
        }
        val ay = FloatArray(h)
        for (y in 0 until h) {
            val within = y - Math.floor(y / cell.toDouble()).toFloat() * cell
            ay[y] = Math.abs(within - half)
        }

        val alpha = ByteArray(w * h)
        val span = rBloom - rEmit
        val rEmit2 = rEmit * rEmit
        val rBloom2 = rBloom * rBloom

        var i = 0
        for (y in 0 until h) {
            val dy = ay[y]
            val dy2 = dy * dy
            val bayerRow = (y and 7) shl 3
            for (x in 0 until w) {
                val dx = ax[x]
                var a: Int
                if (round) {
                    val d2 = dx * dx + dy2
                    a = when {
                        d2 <= rEmit2 -> 0
                        d2 >= rBloom2 -> 255
                        else -> {
                            val d = Math.sqrt(d2.toDouble()).toFloat()
                            profile[(((d - rEmit) / span) * PROFILE_STEPS).toInt().coerceIn(0, PROFILE_STEPS)]
                        }
                    }
                } else {
                    val d = if (dx > dy) dx else dy
                    a = when {
                        d <= rEmit -> 0
                        d >= rBloom -> 255
                        else -> profile[(((d - rEmit) / span) * PROFILE_STEPS).toInt().coerceIn(0, PROFILE_STEPS)]
                    }
                }
                // Ordered dither. The panel composites at 16 bits, so a smooth ramp across tens of
                // pixels lands on only ~32 distinguishable steps of R/B and shows as concentric
                // contour rings. Breaking it up costs nothing per frame because this is cached.
                if (a in 1..254) a = (a + BAYER8[bayerRow or (x and 7)]).coerceIn(0, 255)
                alpha[i++] = a.toByte()
            }
        }
        return alpha
    }

    /** Wraps [buildAlpha] in an ALPHA_8 bitmap ready to blit. Null if it will not fit. */
    fun build(g: PanelGeometry, bloomPercent: Int): Bitmap? {
        val alpha = buildAlpha(g, bloomPercent) ?: return null
        return try {
            Bitmap.createBitmap(g.widthPx, g.heightPx, Bitmap.Config.ALPHA_8).apply {
                copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
            }
        } catch (t: OutOfMemoryError) {
            null
        }
    }
}
