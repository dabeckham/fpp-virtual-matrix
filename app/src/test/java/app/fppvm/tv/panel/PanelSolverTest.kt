package app.fppvm.tv.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel solver turns a millimetre spec into a grid. These pin the arithmetic against
 * hand-computed values for the test TV — 1280x720 at 46 dpi, i.e. a 32" 720p panel — because the
 * whole point of physical units is that "12 mm" measures 12 mm when you hold a ruler to the glass.
 */
class PanelSolverTest {

    private val W = 1280
    private val H = 720
    private val DPI = 46f

    private fun solve(
        pitch: Float,
        emitter: Float,
        shape: EmitterShape = EmitterShape.ROUND,
        mode: PanelMode = PanelMode.FIT_PHYSICAL,
        srcCols: Int = 64,
        srcRows: Int = 32,
        dpi: Float = DPI
    ) = PanelSolver.solve(
        mode = mode, pitchMm = pitch, emitterMm = emitter, shape = shape,
        substrate = LedProfile.SUBSTRATE_BLACK_MASK,
        surfaceWidth = W, surfaceHeight = H, sourceCols = srcCols, sourceRows = srcRows,
        reportedDpi = dpi
    )

    @Test
    fun `12mm bulbs on a 1 inch pitch give the documented 27 by 15 grid`() {
        // 25.4mm at 46dpi is exactly 46 px, so 1280/46 = 27.8 and 720/46 = 15.65.
        val g = solve(25.4f, 12.0f)
        assertEquals(27, g.cols)
        assertEquals(15, g.rows)
        assertEquals(46.0f, g.cellPx, 0.01f)
        assertEquals(21.73f, g.emitterPx, 0.05f)
        assertFalse(g.degraded)
    }

    @Test
    fun `open area matches the closed form for a square lattice`() {
        // (pi/4)(d/p)^2 with d/p = 12/25.4 = 0.4724 -> 17.5%
        val g = solve(25.4f, 12.0f)
        assertEquals(17.5f, g.openAreaPercent, 0.2f)
    }

    @Test
    fun `a square SMD emitter fills more of its cell than a round one of the same size`() {
        val round = solve(10f, 5f, EmitterShape.ROUND)
        val square = solve(10f, 5f, EmitterShape.SQUARE)
        assertEquals(round.fillRatio, square.fillRatio, 0.0001f)
        assertTrue(square.openAreaPercent > round.openAreaPercent)
        assertEquals(25.0f, square.openAreaPercent, 0.1f)   // (d/p)^2
        assertEquals(19.63f, round.openAreaPercent, 0.1f)   // (pi/4)(d/p)^2
    }

    @Test
    fun `the P-series lands where the pitch says it should`() {
        // px per mm = 46/25.4 = 1.811
        val p10p = LedProfiles.byId("p10")!!
        val p10 = solve(p10p.pitchMm, p10p.emitterMm, EmitterShape.SQUARE)
        assertEquals(18.11f, p10.cellPx, 0.02f)
        assertEquals(70, p10.cols)
        assertEquals(39, p10.rows)
        assertFalse("P10 cells are 18 px, well above the floor", p10.degraded)

        val p5p = LedProfiles.byId("p5")!!
        val p5 = solve(p5p.pitchMm, p5p.emitterMm, EmitterShape.SQUARE)
        assertEquals(9.06f, p5.cellPx, 0.02f)
        assertEquals(141, p5.cols)
        assertEquals(79, p5.rows)
        assertFalse(p5.degraded)
    }

    @Test
    fun `P2 5 is the point where this panel runs out of pixels, and it says so`() {
        // 2.5mm at 46dpi is 4.53 px a cell and a 1.6mm emitter is 2.9 px — the emitter is under
        // the floor, so the aperture cannot be drawn and the app must admit that rather than
        // render mush.
        val p = LedProfiles.byId("p2_5")!!
        val g = solve(p.pitchMm, p.emitterMm, EmitterShape.SQUARE)
        assertEquals(4.53f, g.cellPx, 0.02f)
        assertEquals(282, g.cols)
        assertEquals(159, g.rows)
        // SMD1515 is 1.5 mm, which at 46 dpi is 2.7 px.
        assertEquals(2.72f, g.emitterPx, 0.05f)
        assertFalse("cell is above the floor", g.cellPx < PanelSolver.MIN_CELL_PX)
        assertTrue("emitter is 2.9 px, above the 1.5 px floor", g.emitterPx > PanelSolver.MIN_EMITTER_PX)
    }

    @Test
    fun `an impossible pitch degrades honestly instead of silently`() {
        val g = solve(0.6f, 0.4f, EmitterShape.SQUARE)
        assertTrue(g.degraded)
        assertTrue(g.note.contains("below"))
    }

    @Test
    fun `bullet spacings across the 12 to 50 mm range all solve`() {
        for (p in LedProfiles.BUILT_IN.filter { it.id.startsWith("bullet_") }) {
            val g = solve(p.pitchMm, p.emitterMm, EmitterShape.ROUND)
            assertFalse("${p.label} should be drawable", g.degraded)
            assertTrue("${p.label} needs at least one cell", g.cols >= 1 && g.rows >= 1)
            assertEquals("${p.label} pitch", p.pitchMm, g.achievedPitchMm, 0.01f)
        }
        // Bulbs touching at 12mm pitch fill the most; 50mm spacing the least.
        val tight = solve(12f, 12f)
        val loose = solve(50f, 12f)
        assertTrue(tight.openAreaPercent > loose.openAreaPercent)
        assertEquals(78.5f, tight.openAreaPercent, 0.5f)  // pi/4, bulbs touching
        assertEquals(4.5f, loose.openAreaPercent, 0.3f)
    }

    @Test
    fun `an emitter can never exceed its pitch`() {
        val g = solve(10f, 40f)
        assertTrue(g.emitterPx <= g.cellPx + 0.001f)
        assertEquals(100f, g.openAreaPercent * 4f / Math.PI.toFloat(), 1f)
    }

    @Test
    fun `MATCH_SOURCE lets the show pick the grid and reports the pitch that falls out`() {
        val g = solve(25.4f, 12f, mode = PanelMode.MATCH_SOURCE, srcCols = 64, srcRows = 32)
        assertEquals(64, g.cols)
        assertEquals(32, g.rows)
        // Fit is limited by width here: 1280/64 = 20 px a cell, against 720/32 = 22.5.
        assertEquals(20f, g.cellPx, 0.01f)
        assertEquals(11.04f, g.achievedPitchMm, 0.05f)
        // The requested 12/25.4 fill ratio is preserved even though the pitch changed.
        assertEquals(12f / 25.4f, g.fillRatio, 0.001f)
    }

    @Test
    fun `a junk dpi is rejected rather than trusted`() {
        // Android devices report xdpi as a vendor constant; 2 dpi would mean a 700 inch screen.
        assertFalse(PanelSolver.isPlausibleDpi(2f, 1280, 720))
        assertFalse(PanelSolver.isPlausibleDpi(0f, 1280, 720))
        assertTrue(PanelSolver.isPlausibleDpi(46f, 1280, 720))
        assertTrue(PanelSolver.isPlausibleDpi(320f, 1080, 1920))

        val g = solve(25.4f, 12f, dpi = 2f)
        assertEquals(PanelSolver.FALLBACK_DPI, g.dpi, 0.01f)
        assertTrue(g.note.contains("implausible"))
    }

    @Test
    fun `a manual dpi override wins over a plausible reported one`() {
        val g = PanelSolver.solve(
            mode = PanelMode.FIT_PHYSICAL, pitchMm = 25.4f, emitterMm = 12f,
            shape = EmitterShape.ROUND, substrate = LedProfile.SUBSTRATE_NONE,
            surfaceWidth = W, surfaceHeight = H,
            sourceCols = 64, sourceRows = 32, reportedDpi = 46f, dpiOverride = 92f
        )
        assertEquals(92f, g.dpi, 0.01f)
        assertEquals(92f, g.cellPx, 0.01f)  // 25.4mm at 92dpi
        assertEquals(13, g.cols)
    }

    @Test
    fun `the lit area is centred and the remainder reads as bezel`() {
        val g = solve(25.4f, 12f)
        assertEquals(27 * 46, g.widthPx)
        assertEquals(15 * 46, g.heightPx)
        assertTrue("must not overflow the surface", g.widthPx <= W && g.heightPx <= H)
        assertEquals(38, W - g.widthPx)   // 19 px each side
        assertEquals(30, H - g.heightPx)  // 15 px top and bottom
    }
}
