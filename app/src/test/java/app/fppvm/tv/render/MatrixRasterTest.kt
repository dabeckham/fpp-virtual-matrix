package app.fppvm.tv.render

import app.fppvm.tv.config.MatrixConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixRasterTest {

    private fun cfg(
        w: Int = 4,
        h: Int = 3,
        order: MatrixConfig.ColorOrder = MatrixConfig.ColorOrder.RGB,
        flipH: Boolean = false,
        flipV: Boolean = false,
        transpose: Boolean = false,
        brightness: Int = 100,
        gamma: Float = 1.0f
    ) = MatrixConfig(
        width = w, height = h, colorOrder = order,
        flipHorizontal = flipH, flipVertical = flipV, transpose = transpose,
        brightness = brightness, gamma = gamma
    )

    /** Channel data where pixel index i is (i, i+1, i+2) so any mis-mapping is unmistakable. */
    private fun ramp(pixels: Int) = ByteArray(pixels * 3) { (it and 0xFF).toByte() }

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun `pixels are laid out row-major from the top-left, three channels each`() {
        // This is FBMatrix::PrepData's stride = width * 3 with the source read row by row.
        val c = cfg()
        val r = MatrixRaster(c)
        val out = r.render(ramp(12))
        assertEquals(12, out.size)
        assertEquals(Triple(0, 1, 2), rgb(out[0]))       // (0,0)
        assertEquals(Triple(3, 4, 5), rgb(out[1]))       // (1,0)
        assertEquals(Triple(12, 13, 14), rgb(out[4]))    // (0,1) -> pixel 4
        assertEquals(Triple(33, 34, 35), rgb(out[11]))   // (3,2) -> pixel 11
    }

    @Test
    fun `alpha is always opaque`() {
        val out = MatrixRaster(cfg()).render(ramp(12))
        for (p in out) assertEquals(0xFF, (p ushr 24) and 0xFF)
    }

    @Test
    fun `colour order remaps the triplet, not the pixel`() {
        val out = MatrixRaster(cfg(order = MatrixConfig.ColorOrder.GRB)).render(ramp(12))
        // GRB means the stored bytes are (G,R,B): byte0 -> green, byte1 -> red.
        assertEquals(Triple(1, 0, 2), rgb(out[0]))
        assertEquals(Triple(4, 3, 5), rgb(out[1]))
    }

    @Test
    fun `horizontal flip mirrors each row`() {
        val out = MatrixRaster(cfg(flipH = true)).render(ramp(12))
        assertEquals(Triple(9, 10, 11), rgb(out[0]))  // was pixel 3
        assertEquals(Triple(0, 1, 2), rgb(out[3]))    // was pixel 0
    }

    @Test
    fun `vertical flip mirrors rows, which is FPP's invert`() {
        val out = MatrixRaster(cfg(flipV = true)).render(ramp(12))
        assertEquals(Triple(24, 25, 26), rgb(out[0]))  // top row now shows the last row (pixel 8)
        assertEquals(Triple(0, 1, 2), rgb(out[8]))
    }

    @Test
    fun `both flips together rotate the image by 180 degrees`() {
        val out = MatrixRaster(cfg(flipH = true, flipV = true)).render(ramp(12))
        assertEquals(Triple(33, 34, 35), rgb(out[0]))  // was the last pixel
        assertEquals(Triple(0, 1, 2), rgb(out[11]))
    }

    @Test
    fun `transpose reads channels down columns`() {
        // 4x3 matrix whose channel data runs down each column: displayed (x,y) comes from
        // stored pixel (x * height + y).
        val out = MatrixRaster(cfg(transpose = true)).render(ramp(12))
        assertEquals(Triple(0, 1, 2), rgb(out[0]))     // (0,0) -> stored 0
        assertEquals(Triple(9, 10, 11), rgb(out[1]))   // (1,0) -> stored 3
        assertEquals(Triple(3, 4, 5), rgb(out[4]))     // (0,1) -> stored 1
    }

    @Test
    fun `brightness scales linearly and gamma 1 is a pass-through`() {
        val full = MatrixRaster(cfg(brightness = 100)).render(ramp(12))
        assertEquals(Triple(0, 1, 2), rgb(full[0]))

        val half = MatrixRaster(cfg(brightness = 50)).render(byteArrayOf(-1, -1, -1) + ByteArray(33))
        assertEquals(Triple(128, 128, 128), rgb(half[0]))
    }

    @Test
    fun `gamma bends the midtones and leaves the endpoints alone`() {
        val lut = MatrixRaster.buildLut(cfg(gamma = 2.2f))
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
        // 50% in should land well below 50% out at gamma 2.2.
        org.junit.Assert.assertTrue("mid was ${lut[128]}", lut[128] in 50..60)
    }

    @Test
    fun `a short frame renders black instead of throwing`() {
        // A truncated or partially-decoded frame must degrade, not crash the display thread.
        val r = MatrixRaster(cfg())
        val out = r.render(ramp(2)) // only 2 pixels of data for a 12-pixel matrix
        assertEquals(Triple(0, 1, 2), rgb(out[0]))
        assertEquals(Triple(0, 0, 0), rgb(out[5]))
        assertEquals(Triple(0, 0, 0), rgb(out[11]))
    }

    @Test
    fun `resizing reallocates the pixel buffer`() {
        val r = MatrixRaster(cfg(4, 3))
        assertEquals(12, r.render(ramp(12)).size)
        r.reconfigure(cfg(8, 8))
        assertEquals(64, r.render(ramp(64)).size)
        assertEquals(8, r.width)
        assertEquals(8, r.height)
    }

    @Test
    fun `an offset into the channel buffer is honoured`() {
        val r = MatrixRaster(cfg(2, 1))
        val data = ByteArray(20) { (it and 0xFF).toByte() }
        val out = r.render(data, offset = 5)
        assertEquals(Triple(5, 6, 7), rgb(out[0]))
        assertEquals(Triple(8, 9, 10), rgb(out[1]))
    }
}
