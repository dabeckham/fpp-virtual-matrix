package app.fppvm.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SyncClockTest {

    private val ms = 1_000_000L

    private fun clock(step: Int = 50, frames: Int = 2000) = SyncClock().apply { configure(step, frames) }

    @Test
    fun `position free-runs off the local clock between packets`() {
        val c = clock()
        c.start(0, 0)
        assertEquals(0, c.frameAt(0))
        assertEquals(10, c.frameAt(500 * ms))   // 500 ms at 50 ms/frame
        assertEquals(20, c.frameAt(1000 * ms))
    }

    @Test
    fun `seconds elapsed wins over the frame number, as it does in FPP`() {
        val c = clock(step = 25)
        // MultiSync::SyncSyncedSequence recomputes the frame from seconds whenever seconds is set,
        // because that is what the master's media clock actually produced.
        assertEquals(400.0, c.masterFrameFor(frameNumber = 1, secondsElapsed = 10.0f), 0.001)
        // With no seconds yet (frame 0 / start), the frame number is authoritative.
        assertEquals(37.0, c.masterFrameFor(frameNumber = 37, secondsElapsed = 0.0f), 0.001)
    }

    @Test
    fun `a small error is corrected gradually rather than jumped`() {
        val c = clock()
        c.start(100, 0)
        // Master says frame 104 when we think we are at 100: a 4-frame (200 ms) error.
        c.onSync(104, 0f, 0)
        val after = c.positionAt(0)
        assertTrue("should move toward the master", after > 100.0)
        assertTrue("should not snap all the way", after < 104.0)
        assertEquals(0, c.resyncJumps)
        assertEquals(4.0f, c.lastErrorFrames, 0.01f)
    }

    @Test
    fun `repeated packets converge on the master position`() {
        val c = clock()
        c.start(100, 0)
        var t = 0L
        // The master advances 10 frames per packet, as FPP sends them; we start 4 frames behind.
        var masterFrame = 104
        repeat(8) {
            c.onSync(masterFrame, 0f, t)
            t += 500 * ms
            masterFrame += 10
        }
        val error = masterFrame - 10 - c.positionAt(t - 500 * ms)
        assertTrue("converged to within a frame, was $error", abs(error) < 1.0)
        assertEquals(0, c.resyncJumps)
    }

    @Test
    fun `a large error re-anchors outright`() {
        val c = clock()
        c.start(0, 0)
        // A seek on the master: half a second at 50 ms/frame is 10 frames, so 400 is far past it.
        c.onSync(400, 0f, 0)
        assertEquals(400.0, c.positionAt(0), 0.001)
        assertEquals(1, c.resyncJumps)
    }

    @Test
    fun `the hard resync threshold tracks the step time`() {
        // Half a second, matching FPP's own give-up-and-jump point of RefreshRate / 2 frames.
        val fast = clock(step = 25)
        fast.start(0, 0)
        fast.onSync(19, 0f, 0) // 19 frames = 475 ms: still a slew
        assertEquals(0, fast.resyncJumps)

        val fast2 = clock(step = 25)
        fast2.start(0, 0)
        fast2.onSync(21, 0f, 0) // 21 frames = 525 ms: a jump
        assertEquals(1, fast2.resyncJumps)
    }

    @Test
    fun `running past the last frame reports end rather than wrapping`() {
        val c = clock(step = 50, frames = 20)
        c.start(0, 0)
        assertEquals(19, c.frameAt(950 * ms))
        assertEquals(-1, c.frameAt(1000 * ms))
        assertEquals(-1, c.frameAt(5000 * ms))
    }

    @Test
    fun `the remote offset shifts playback in whole milliseconds`() {
        val c = clock(step = 50)
        c.remoteOffsetMs = 100 // render 100 ms later than the master reports
        assertEquals(22.0, c.masterFrameFor(0, 1.0f), 0.001) // (1.0 + 0.1) * 1000 / 50
        c.remoteOffsetMs = -50
        assertEquals(19.0, c.masterFrameFor(0, 1.0f), 0.001)
    }

    @Test
    fun `a sync packet arriving before start begins playback`() {
        // A remote powered on mid-show never sees start; it has to join from a sync packet.
        val c = clock()
        c.onSync(250, 12.5f, 0)
        assertTrue(c.isRunning)
        assertEquals(250, c.frameAt(0))
    }

    @Test
    fun `silence from the master is measurable`() {
        val c = clock()
        c.start(0, 0)
        c.onSync(10, 0.5f, 1000 * ms)
        assertEquals(0L, c.millisSinceSync(1000 * ms))
        assertEquals(15_000L, c.millisSinceSync(16_000 * ms))
    }
}
