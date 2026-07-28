package app.fppvm.tv.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this pins: **correct the rate, not the position.**
 *
 * A player that seeks to match every sync packet cannot keep up, because an exact seek decodes from
 * the previous keyframe forward — with a two second GOP that is most of a second of work per
 * correction. Nudging the playback rate absorbs drift continuously and costs nothing, which is what
 * the sequence clock already does for channel data.
 */
class VideoFollowTest {

    @Test
    fun `in step means no correction`() {
        assertEquals(1.0f, VideoLayerView.rateFor(0), 0.0001f)
        assertFalse(VideoLayerView.needsSeek(0))
    }

    @Test
    fun `a video running behind is asked to speed up, and vice versa`() {
        // Positive error = the show is ahead of the video, so the video must run fast to catch up.
        assertTrue("behind the show should speed up", VideoLayerView.rateFor(500) > 1f)
        assertTrue("ahead of the show should slow down", VideoLayerView.rateFor(-500) < 1f)
    }

    @Test
    fun `the correction stays inside two percent`() {
        // Wider than this and the speed change becomes noticeable; it must be invisible.
        for (err in longArrayOf(-100_000, -5_000, -1_400, 0, 1_400, 5_000, 100_000)) {
            val r = VideoLayerView.rateFor(err)
            assertTrue("rate $r out of range for error $err",
                r >= VideoLayerView.MIN_RATE - 1e-6 && r <= VideoLayerView.MAX_RATE + 1e-6)
        }
    }

    @Test
    fun `a small error is walked off rather than seeked`() {
        // Half a second out is well within what rate correction absorbs, and seeking for it would
        // cost more than the error itself.
        assertFalse(VideoLayerView.needsSeek(500))
        assertFalse(VideoLayerView.needsSeek(-500))
        assertFalse(VideoLayerView.needsSeek(VideoLayerView.SEEK_THRESHOLD_MS))
    }

    @Test
    fun `a jump too large to walk off does seek`() {
        // Rate correction is capped at 2%, so closing a 30 s gap would take 25 minutes. Seek.
        assertTrue(VideoLayerView.needsSeek(30_000))
        assertTrue(VideoLayerView.needsSeek(-30_000))
        assertTrue(VideoLayerView.needsSeek(VideoLayerView.SEEK_THRESHOLD_MS + 1))
    }

    @Test
    fun `the gain is proportional, so a bigger error pulls harder`() {
        val small = VideoLayerView.rateFor(200)
        val large = VideoLayerView.rateFor(1_000)
        assertTrue("a larger error must correct at least as hard", large >= small)
        assertTrue(small > 1f)
    }
}
