package app.fppvm.tv.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixConfigTest {

    @Test
    fun `defaults round-trip through json unchanged`() {
        val a = MatrixConfig.DEFAULT
        val b = MatrixConfig.fromJson(a.toJson().toString())
        assertEquals(a, b)
    }

    @Test
    fun `a partial override only changes the fields it names`() {
        val base = MatrixConfig(width = 96, height = 48, startChannel = 4097, brightness = 60)
        val merged = MatrixConfig.fromJson("""{"brightness":35}""", base)
        assertEquals(96, merged.width)
        assertEquals(48, merged.height)
        assertEquals(4097, merged.startChannel)
        assertEquals(35, merged.brightness)
    }

    @Test
    fun `malformed json leaves the display alone`() {
        // A sideloaded typo must not brick a panel that is mid-show.
        val base = MatrixConfig(width = 64, height = 32)
        assertEquals(base, MatrixConfig.fromJson("{not json", base))
        assertEquals(base, MatrixConfig.fromJson("", base))
    }

    @Test
    fun `an unknown enum value falls back instead of throwing`() {
        val c = MatrixConfig.fromJson("""{"colorOrder":"XYZ","scaleMode":"nonsense"}""")
        assertEquals(MatrixConfig.ColorOrder.RGB, c.colorOrder)
        assertEquals(MatrixConfig.ScaleMode.FIT, c.scaleMode)
    }

    @Test
    fun `enum names are case-insensitive`() {
        val c = MatrixConfig.fromJson("""{"colorOrder":"grb","idleMode":"test_pattern"}""")
        assertEquals(MatrixConfig.ColorOrder.GRB, c.colorOrder)
        assertEquals(MatrixConfig.IdleMode.TEST_PATTERN, c.idleMode)
    }

    @Test
    fun `out-of-range values are clamped, not accepted`() {
        val c = MatrixConfig.fromJson(
            """{"width":100000,"height":0,"brightness":500,"gamma":99,"startChannel":-4}"""
        )
        assertEquals(MatrixConfig.MAX_DIMENSION, c.width)
        assertEquals(1, c.height)
        assertEquals(100, c.brightness)
        assertEquals(4.0f, c.gamma, 0.001f)
        assertEquals(1, c.startChannel)
    }

    @Test
    fun `channel arithmetic matches FPP's one-based UI`() {
        val c = MatrixConfig(width = 64, height = 32, startChannel = 1)
        assertEquals(0, c.startChannelZeroBased)
        assertEquals(64 * 32 * 3, c.channelCount)
        assertEquals(6144, c.channelCount)

        val d = c.copy(startChannel = 6145)
        assertEquals(6144, d.startChannelZeroBased)
    }

    @Test
    fun `the advertised range is the block this matrix actually consumes`() {
        // FPP formats a remote's ranges as "start-count" with a zero-based start.
        val c = MatrixConfig(width = 32, height = 16, startChannel = 1025)
        assertEquals("1024-1536", c.rangesString())
    }

    @Test
    fun `the ranges string fits the ping packet's field`() {
        val c = MatrixConfig(width = 1024, height = 1024, startChannel = MatrixConfig.MAX_CHANNEL)
        assertTrue("ranges must fit 120 chars", c.rangesString().length <= 120)
    }
}
