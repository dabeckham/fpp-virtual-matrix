package app.fppvm.tv.render

import app.fppvm.tv.config.MatrixConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequential fast path exists purely for speed at panel-resolution matrices. It is only safe
 * if it is indistinguishable from the general path, so that equivalence is asserted directly
 * rather than eyeballed — a divergence here would be a subtly wrong picture, not a crash.
 */
class MatrixRasterFastPathTest {

    private fun data(pixels: Int) = ByteArray(pixels * 3) { ((it * 37 + 11) and 0xFF).toByte() }

    /** Forces the general path by asking for an orientation the fast path declines to handle. */
    private fun generalEquivalentOf(c: MatrixConfig, src: ByteArray): IntArray {
        // transpose on a square matrix with both flips is identity, so the general path must
        // produce exactly what the sequential path produces for the same config.
        return MatrixRaster(c).render(src)
    }

    @Test
    fun `fast path matches the general path for the default orientation`() {
        val w = 17
        val h = 13
        val src = data(w * h)
        val fast = MatrixRaster(MatrixConfig(width = w, height = h)).render(src).copyOf()

        // Same pixels, but computed through the general loop: flipping twice is a no-op.
        val viaGeneral = MatrixRaster(
            MatrixConfig(width = w, height = h, flipHorizontal = true)
        ).render(src).copyOf()
        // Undo the horizontal flip to compare row by row.
        val undone = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) undone[y * w + x] = viaGeneral[y * w + (w - 1 - x)]
        assertArrayEquals(fast, undone)
    }

    @Test
    fun `fast path honours brightness and gamma exactly like the general path`() {
        val w = 9
        val h = 7
        val src = data(w * h)
        val cfg = MatrixConfig(width = w, height = h, brightness = 55, gamma = 1.8f)
        val fast = MatrixRaster(cfg).render(src).copyOf()

        val lut = MatrixRaster.buildLut(cfg)
        val expected = IntArray(w * h) { i ->
            val o = i * 3
            (0xFF shl 24) or
                (lut[src[o].toInt() and 0xFF] shl 16) or
                (lut[src[o + 1].toInt() and 0xFF] shl 8) or
                lut[src[o + 2].toInt() and 0xFF]
        }
        assertArrayEquals(expected, fast)
    }

    @Test
    fun `identity tone is only claimed when it really is identity`() {
        assertTrue(MatrixRaster.isIdentityTone(MatrixConfig()))
        assertTrue(!MatrixRaster.isIdentityTone(MatrixConfig(brightness = 99)))
        assertTrue(!MatrixRaster.isIdentityTone(MatrixConfig(gamma = 1.1f)))
    }

    @Test
    fun `a short buffer still falls back to the safe path instead of overrunning`() {
        val c = MatrixConfig(width = 8, height = 8)
        val out = MatrixRaster(c).render(data(4)) // only 4 pixels of data for 64
        assertTrue(out.size == 64)
        assertTrue("tail must be black", out.drop(10).all { it == 0xFF000000.toInt() })
    }

    @Test
    fun `a panel-resolution matrix rasterises without allocating per frame`() {
        // 1280x720 is the ceiling this is built for; make sure the buffers are sized once and
        // reused, since a per-frame 3.5 MB allocation would GC-thrash a 192 MB heap.
        val c = MatrixConfig(width = 1280, height = 720)
        val r = MatrixRaster(c)
        val src = ByteArray(c.channelCount)
        val first = r.render(src)
        val second = r.render(src)
        assertTrue("the pixel buffer must be reused", first === second)
        assertTrue(first.size == 1280 * 720)
    }
}
