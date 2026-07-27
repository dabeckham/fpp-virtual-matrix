package app.fppvm.tv.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's emitter figures are the SMD package's own dimensions — the naming convention is
 * the size in tenths of a millimetre, so SMD3535 is 3.5 mm. Pinning them here means a future edit
 * has to be a deliberate decision rather than a drift back towards someone's estimate.
 */
class LedProfileTest {

    private fun p(id: String) = LedProfiles.byId(id) ?: error("missing profile $id")

    @Test
    fun `SMD emitter sizes match the package they name`() {
        assertEquals("SMD1515", p("p2_5").packageName); assertEquals(1.5f, p("p2_5").emitterMm, 0.001f)
        assertEquals("SMD2121", p("p3").packageName);   assertEquals(2.1f, p("p3").emitterMm, 0.001f)
        assertEquals("SMD2121", p("p4").packageName);   assertEquals(2.1f, p("p4").emitterMm, 0.001f)
        assertEquals("SMD2727", p("p5").packageName);   assertEquals(2.7f, p("p5").emitterMm, 0.001f)
        assertEquals("SMD3535", p("p10").packageName);  assertEquals(3.5f, p("p10").emitterMm, 0.001f)
    }

    @Test
    fun `pitch matches the P number`() {
        assertEquals(2.5f, p("p2_5").pitchMm, 0.001f)
        assertEquals(5.0f, p("p5").pitchMm, 0.001f)
        assertEquals(10.0f, p("p10").pitchMm, 0.001f)
    }

    @Test
    fun `fill ratio rises as the pitch gets finer`() {
        // This is why a fine-pitch wall reads as a solid image and a P10 reads as discrete dots.
        assertEquals(0.60f, p("p2_5").fillRatio, 0.01f)
        assertEquals(0.54f, p("p5").fillRatio, 0.01f)
        assertEquals(0.35f, p("p10").fillRatio, 0.01f)
        assertTrue(p("p2_5").fillRatio > p("p5").fillRatio)
        assertTrue(p("p5").fillRatio > p("p10").fillRatio)
    }

    @Test
    fun `an emitter is never larger than its pitch`() {
        for (prof in LedProfiles.BUILT_IN) {
            assertTrue("${prof.id} emitter exceeds pitch", prof.emitterMm <= prof.pitchMm + 0.001f)
        }
    }

    @Test
    fun `only outdoor cabinets carry a louvre`() {
        assertTrue(p("p10").louvrePercent > 0)
        assertTrue(p("p8").louvrePercent > 0)
        assertEquals("indoor panels have no shade", 0, p("p2_5").louvrePercent)
        assertEquals("a strand of bullets has nothing to shade", 0, p("bullet_25").louvrePercent)
    }

    @Test
    fun `bullets are round and SMD cabinets are square, except DIP`() {
        assertEquals(EmitterShape.SQUARE, p("p10").shape)
        assertEquals(EmitterShape.ROUND, p("p10_dip").shape)
        assertEquals(EmitterShape.ROUND, p("bullet_25").shape)
    }

    @Test
    fun `every id is unique and resolvable`() {
        val ids = LedProfiles.BUILT_IN.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(LedProfiles.byId(it)) }
    }

    @Test
    fun `profiles survive a json round trip`() {
        val out = LedProfiles.listToJson(LedProfiles.BUILT_IN).toString()
        val back = LedProfiles.listFromJson(out)
        assertEquals(LedProfiles.BUILT_IN.size, back.size)
        assertEquals(LedProfiles.BUILT_IN, back)
    }

    @Test
    fun `a profile seeds the config without locking it`() {
        val base = app.fppvm.tv.config.MatrixConfig()
        val loaded = base.applyProfile(p("p10"))
        assertEquals(10.0f, loaded.pitchMm, 0.001f)
        assertEquals(3.5f, loaded.emitterMm, 0.001f)
        assertEquals(EmitterShape.SQUARE, loaded.emitterShape)
        assertTrue("a freshly loaded profile is not modified", !loaded.profileEdited)
        assertEquals("P10 outdoor SMD", loaded.profileLabel)

        // Every field stays independently editable afterwards — that is the whole point.
        // No edited() call: divergence is computed from the values, so any route that changes a
        // field — D-pad, adb or the web API — marks it the same way.
        val tweaked = loaded.copy(emitterShape = EmitterShape.ROUND)
        assertEquals(EmitterShape.ROUND, tweaked.emitterShape)
        assertEquals(10.0f, tweaked.pitchMm, 0.001f)
        assertTrue(tweaked.profileEdited)
        assertEquals("P10 outdoor SMD (modified)", tweaked.profileLabel)
    }
}
