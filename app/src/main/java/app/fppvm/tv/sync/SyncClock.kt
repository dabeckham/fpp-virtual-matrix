package app.fppvm.tv.sync

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tracks the master's playback position and converts it to a frame index at any local instant.
 *
 * FPP's own remotes cannot seek: `fppd` plays frames sequentially out of a channel-output thread,
 * so staying in sync means slewing that thread's inter-frame delay and, when that is not enough,
 * skipping or repeating frames (`CalculateNewChannelOutputDelayForFrame`). A frame here is a
 * random access into the FSEQ, so none of that is needed — we simply ask "what frame should be on
 * screen right now?" every vsync. That removes the skip/hold artefacts by construction.
 *
 * Between sync packets (FPP sends one every 10 frames once past frame 32, so roughly twice a
 * second) the position free-runs off the local monotonic clock. Each packet applies a
 * proportional correction rather than a jump, so ordinary network jitter never shows on screen.
 * A full re-anchor happens only when the error is large enough that it must be a real seek or a
 * dropped stretch of packets.
 *
 * No integral term: the only thing an integrator would buy is cancelling steady-state error from
 * crystal drift between the two boxes, and at ~100 ppm over the half second between packets that
 * is under a ten-thousandth of a frame. Proportional-only cannot ring, which matters more.
 *
 * Not thread-safe by itself; [MatrixPlayer] confines mutation to its own lock.
 */
class SyncClock {

    companion object {
        /**
         * Fraction of the measured error corrected per sync packet. 0.35 settles a small error in
         * about three packets (~1.5 s) without overshoot.
         */
        const val CORRECTION_ALPHA = 0.35f

        /**
         * Error beyond which we stop nudging and re-anchor outright. Half a second matches the
         * threshold FPP uses before it gives up on slewing and jumps
         * (`diff <= -(RefreshRate / 2)`).
         */
        const val HARD_RESYNC_SECONDS = 0.5f
    }

    var stepTimeMs: Int = 50
        private set

    var numFrames: Int = 0
        private set

    /** FPP's `remoteOffset`: positive renders later than the master reports. */
    var remoteOffsetMs: Int = 0

    private var running = false
    private var anchorNanos = 0L
    private var anchorFrame = 0.0

    // Diagnostics
    var lastErrorFrames: Float = 0f
        private set
    var maxErrorFrames: Float = 0f
        private set
    var resyncJumps: Int = 0
        private set
    var syncPackets: Int = 0
        private set
    var lastSyncNanos: Long = 0L
        private set
    private var haveSync = false

    val isRunning: Boolean get() = running

    private val stepNanos: Double get() = stepTimeMs.toDouble() * 1_000_000.0

    /** Called when a sequence is opened; [stepTimeMs] and [numFrames] come from its FSEQ header. */
    fun configure(stepTimeMs: Int, numFrames: Int) {
        this.stepTimeMs = if (stepTimeMs <= 0) 50 else stepTimeMs
        this.numFrames = numFrames
    }

    fun start(frame: Int, nowNanos: Long) {
        running = true
        anchorFrame = frame.toDouble()
        anchorNanos = nowNanos
        lastErrorFrames = 0f
        maxErrorFrames = 0f
        resyncJumps = 0
        syncPackets = 0
        lastSyncNanos = nowNanos
        haveSync = true
    }

    fun stop() {
        running = false
    }

    /**
     * Applies a sync packet.
     *
     * [frameNumber] and [secondsElapsed] are taken straight off the wire. FPP prefers the seconds
     * field whenever it is non-zero and recomputes the frame from it
     * (`MultiSync::SyncSyncedSequence`), because seconds is what the master's media clock actually
     * produced; we do the same so a sequence with a different step time still lands correctly.
     */
    fun onSync(frameNumber: Int, secondsElapsed: Float, nowNanos: Long) {
        val masterFrame = masterFrameFor(frameNumber, secondsElapsed)
        syncPackets++
        lastSyncNanos = nowNanos
        haveSync = true

        if (!running) {
            start(masterFrame.roundToInt(), nowNanos)
            return
        }

        val estimate = positionAt(nowNanos)
        val error = masterFrame - estimate
        lastErrorFrames = error.toFloat()
        if (abs(error) > maxErrorFrames) maxErrorFrames = abs(error).toFloat()

        val hardThreshold = HARD_RESYNC_SECONDS * 1000.0 / stepTimeMs
        if (abs(error) >= hardThreshold) {
            anchorFrame = masterFrame
            anchorNanos = nowNanos
            resyncJumps++
            return
        }
        // Re-anchor at "now" so the correction is applied from this instant forward rather than
        // being smeared back over however long ago the previous anchor was.
        anchorFrame = estimate + error * CORRECTION_ALPHA
        anchorNanos = nowNanos
    }

    /** Converts a wire (frameNumber, secondsElapsed) pair to a master frame position. */
    fun masterFrameFor(frameNumber: Int, secondsElapsed: Float): Double {
        val offsetSeconds = remoteOffsetMs / 1000.0
        return if (secondsElapsed > 0.0001f) {
            (secondsElapsed + offsetSeconds) * 1000.0 / stepTimeMs
        } else {
            frameNumber.toDouble() + offsetSeconds * 1000.0 / stepTimeMs
        }
    }

    /** Fractional frame position at [nowNanos], unclamped. */
    fun positionAt(nowNanos: Long): Double {
        if (!running) return anchorFrame
        return anchorFrame + (nowNanos - anchorNanos) / stepNanos
    }

    /**
     * The frame that should be on screen at [nowNanos], clamped into the sequence. Returns -1 once
     * the position runs past the end so the caller can hold or blank rather than wrap.
     */
    fun frameAt(nowNanos: Long): Int {
        if (!running) return -1
        val pos = positionAt(nowNanos)
        if (pos < 0) return 0
        val f = pos.toInt()
        if (numFrames in 1..f) return -1
        return f
    }

    /** Milliseconds since the last sync packet; large values mean the master went away. */
    fun millisSinceSync(nowNanos: Long): Long =
        if (!haveSync) Long.MAX_VALUE else (nowNanos - lastSyncNanos) / 1_000_000
}
