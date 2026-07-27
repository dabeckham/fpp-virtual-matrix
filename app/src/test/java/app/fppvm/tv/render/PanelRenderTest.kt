package app.fppvm.tv.render

import app.fppvm.tv.config.MatrixConfig
import app.fppvm.tv.panel.Downsample
import app.fppvm.tv.panel.EmitterShape
import app.fppvm.tv.panel.PanelGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelRenderTest {

    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /** A dark field with one lit pixel — the case a block mean destroys. */
    private fun sparse(w: Int, h: Int, litX: Int, litY: Int): ByteArray {
        val b = ByteArray(w * h * 3)
        val o = (litY * w + litX) * 3
        b[o] = 255.toByte(); b[o + 1] = 200.toByte(); b[o + 2] = 100.toByte()
        return b
    }

    @Test
    fun `MAX keeps a single lit pixel that MEAN would erase`() {
        // 64x32 show reduced to an 8x4 grid: each cell covers 64 source pixels.
        val cfg = MatrixConfig(width = 64, height = 32)
        val src = sparse(64, 32, litX = 3, litY = 2)

        val maxGrid = MatrixRaster(cfg).renderGrid(src, 8, 4, Downsample.MAX).copyOf()
        val meanGrid = MatrixRaster(cfg).renderGrid(src, 8, 4, Downsample.MEAN).copyOf()

        // The lit pixel is at source (3,2) -> cell (0,0).
        assertEquals(Triple(255, 200, 100), rgb(maxGrid[0]))
        val meanCell = rgb(meanGrid[0])
        assertTrue("mean must wash the pixel out — that is the point", meanCell.first < 10)
        // Neither should light any other cell.
        for (i in 1 until 32) assertEquals("cell $i", Triple(0, 0, 0), rgb(maxGrid[i]))
    }

    @Test
    fun `MEAN averages a uniform block back to its own value`() {
        val cfg = MatrixConfig(width = 8, height = 8)
        val src = ByteArray(8 * 8 * 3) { 120.toByte() }
        val grid = MatrixRaster(cfg).renderGrid(src, 2, 2, Downsample.MEAN)
        for (i in 0 until 4) assertEquals(Triple(120, 120, 120), rgb(grid[i]))
    }

    @Test
    fun `a grid finer than the source replicates rather than interpolating`() {
        // 2x2 show on a 4x4 grid: each source pixel should occupy exactly four cells, hard-edged.
        val cfg = MatrixConfig(width = 2, height = 2)
        val src = ByteArray(2 * 2 * 3)
        src[0] = 255.toByte()                       // (0,0) red
        src[(1 * 2 + 1) * 3 + 1] = 255.toByte()     // (1,1) green
        val grid = MatrixRaster(cfg).renderGrid(src, 4, 4, Downsample.MAX)
        assertEquals(Triple(255, 0, 0), rgb(grid[0]))
        assertEquals(Triple(255, 0, 0), rgb(grid[1]))
        assertEquals(Triple(0, 255, 0), rgb(grid[4 * 3 + 3]))
    }

    @Test
    fun `grid rendering honours flips`() {
        val cfg = MatrixConfig(width = 4, height = 1, flipHorizontal = true)
        val src = ByteArray(4 * 3)
        src[0] = 255.toByte()          // leftmost source pixel is red
        val grid = MatrixRaster(cfg).renderGrid(src, 4, 1, Downsample.MAX)
        assertEquals("flip should move it to the right-hand cell", Triple(255, 0, 0), rgb(grid[3]))
    }

    @Test
    fun `the grid buffer is reused across frames`() {
        val cfg = MatrixConfig(width = 64, height = 32)
        val r = MatrixRaster(cfg)
        val src = ByteArray(cfg.channelCount)
        val a = r.renderGrid(src, 27, 15, Downsample.MAX)
        val b = r.renderGrid(src, 27, 15, Downsample.MAX)
        assertTrue(a === b)
        assertEquals(27 * 15, a.size)
    }

    // ------------------------------------------------------------------ aperture mask

    private fun geometry(
        cols: Int = 4, rows: Int = 3, cellPx: Float = 46f, emitterPx: Float = 21.73f,
        shape: EmitterShape = EmitterShape.ROUND
    ) = PanelGeometry(
        cols = cols, rows = rows, cellPx = cellPx, emitterPx = emitterPx, shape = shape,
        substrate = app.fppvm.tv.panel.LedProfile.SUBSTRATE_NONE,
        dpi = 46f, achievedPitchMm = 25.4f, achievedEmitterMm = 12f,
        widthPx = (cols * cellPx).toInt(), heightPx = (rows * cellPx).toInt(),
        degraded = false, note = ""
    )

    @Test
    fun `the emitter centre is fully open and the field is fully masked`() {
        val g = geometry()
        val a = PanelMask.buildAlpha(g, bloomPercent = 0)!!
        fun at(x: Int, y: Int) = a[y * g.widthPx + x].toInt() and 0xFF

        // Centre of cell (0,0) is at (23,23); the aperture radius is ~10.9 px.
        assertEquals("aperture must be fully transparent", 0, at(23, 23))
        assertEquals(0, at(23 + 8, 23))
        // A cell corner is 32.5 px from the centre — well outside.
        assertEquals("field must be fully opaque", 255, at(0, 0))
        // Centre of cell (1,0) is at 46+23 = 69.
        assertEquals(0, at(69, 23))
    }

    @Test
    fun `bloom produces a graded ring instead of a hard edge`() {
        val g = geometry()
        val hard = PanelMask.buildAlpha(g, bloomPercent = 0)!!
        val soft = PanelMask.buildAlpha(g, bloomPercent = 80)!!
        fun at(a: ByteArray, x: Int, y: Int) = a[y * g.widthPx + x].toInt() and 0xFF

        // 16 px from the cell centre: outside the ~10.9 px aperture, inside a wide bloom.
        val x = 23 + 16
        assertEquals("no bloom means opaque immediately outside the aperture", 255, at(hard, x, 23))
        val v = at(soft, x, 23)
        assertTrue("bloom should be partially transparent here, was $v", v in 1..254)
        // Still fully open at the centre.
        assertEquals(0, at(soft, 23, 23))
    }

    @Test
    fun `a square emitter masks its corners where a round one does not`() {
        val round = PanelMask.buildAlpha(geometry(shape = EmitterShape.ROUND), 0)!!
        val square = PanelMask.buildAlpha(geometry(shape = EmitterShape.SQUARE), 0)!!
        val w = geometry().widthPx
        // Offset (7,7) from centre: Chebyshev distance 7 (inside a 10.9 px half-side) but
        // Euclidean 9.9 — also inside. Use (9,9): Chebyshev 9 inside, Euclidean 12.7 outside.
        val x = 23 + 9
        val y = 23 + 9
        assertEquals("square emitter still lit at its diagonal", 0, square[y * w + x].toInt() and 0xFF)
        assertEquals("round emitter has ended by there", 255, round[y * w + x].toInt() and 0xFF)
    }

    @Test
    fun `the mask covers exactly the lit area`() {
        val g = geometry(cols = 27, rows = 15, cellPx = 46f)
        val a = PanelMask.buildAlpha(g, 40)!!
        assertEquals(27 * 46 * 15 * 46, a.size)
    }

    @Test
    fun `a fractional pitch does not drift across the panel`() {
        // P10 on this panel is 18.11 px a cell — deliberately not an integer.
        val g = geometry(cols = 70, rows = 39, cellPx = 18.11f, emitterPx = 9.06f, shape = EmitterShape.SQUARE)
        val a = PanelMask.buildAlpha(g, 0)!!
        fun at(x: Int, y: Int) = a[y * g.widthPx + x].toInt() and 0xFF
        // Every cell centre must still be open, including the last one, which is where a
        // per-cell integer step would have accumulated its error.
        for (c in listOf(0, 1, 17, 34, 52, 69)) {
            val cx = ((c + 0.5f) * g.cellPx).toInt()
            val cy = (0.5f * g.cellPx).toInt()
            assertEquals("cell $c centre at x=$cx", 0, at(cx, cy))
        }
    }
}
