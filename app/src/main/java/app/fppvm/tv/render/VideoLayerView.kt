package app.fppvm.tv.render

import android.content.Context
import android.media.MediaPlayer
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File

/**
 * Full-screen video, played underneath the matrix.
 *
 * Deliberately featureless. Blacking the picture during an effect, picture-in-picture, framing —
 * all of that is authored into the media file in a video editor, where the tools are good. This
 * plays the file, full screen, on the show's clock, and offers nothing else.
 *
 * It sits *below* the matrix surface, which is translucent wherever no emitter is lit, so the video
 * shows through the gaps between the LEDs rather than being covered by a grid of dark dots.
 *
 * Timing follows the same principle as [app.fppvm.tv.sync.SyncClock]: **correct the rate, not the
 * position.** Seeking is what makes video sync miserable — an exact seek decodes from the previous
 * keyframe forward, so with a long GOP every correction costs most of a second. Nudging the
 * playback speed a percent or two absorbs drift continuously and costs nothing, and a real seek is
 * reserved for a jump too large to walk off.
 */
class VideoLayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "FppVideo"

        /** Widest rate correction. Beyond a couple of percent the pitch shift becomes audible. */
        const val MAX_RATE = 1.02f
        const val MIN_RATE = 0.98f

        /** Past this, walking the error off would take longer than a seek. */
        const val SEEK_THRESHOLD_MS = 1_500L

        /** Gain from seconds of error to a rate adjustment. */
        private const val CORRECTION_GAIN = 0.04f
    }

    private var player: MediaPlayer? = null
    private var pendingFile: File? = null

    @Volatile
    var surfaceReady = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        pendingFile?.let { play(it) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stop()
    }

    /** Starts [file], looping. Held until the surface exists if it does not yet. */
    fun play(file: File): Boolean {
        pendingFile = file
        if (!surfaceReady) return false
        stop()
        return try {
            player = MediaPlayer().apply {
                setDisplay(this@VideoLayerView.holder)
                setDataSource(file.absolutePath)
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    lastError = "media error $what/$extra"
                    Log.w(TAG, "playback error on ${file.name}: $what/$extra")
                    true
                }
                prepare()
                start()
            }
            lastError = ""
            Log.i(TAG, "playing ${file.name}")
            true
        } catch (t: Throwable) {
            lastError = t.message ?: "could not open"
            Log.w(TAG, "could not play ${file.name}", t)
            player = null
            false
        }
    }

    fun stop() {
        val p = player ?: return
        player = null
        try {
            p.stop()
        } catch (_: Throwable) {
        }
        try {
            p.release()
        } catch (_: Throwable) {
        }
    }

    fun isPlaying(): Boolean = try {
        player?.isPlaying == true
    } catch (t: Throwable) {
        false
    }

    fun positionMs(): Int = try {
        player?.currentPosition ?: -1
    } catch (t: Throwable) {
        -1
    }

    fun durationMs(): Int = try {
        player?.duration ?: -1
    } catch (t: Throwable) {
        -1
    }

    /**
     * Pulls playback towards [targetMs] without seeking unless it has to.
     *
     * Returns the rate now in force, or 0 when nothing is playing. A positive error means the show
     * is ahead of the video, so the video is asked to run slightly fast to catch up.
     */
    fun follow(targetMs: Long): Float {
        val p = player ?: return 0f
        val pos = positionMs()
        if (pos < 0) return 0f
        val errorMs = targetMs - pos

        if (kotlin.math.abs(errorMs) > SEEK_THRESHOLD_MS) {
            // Too far out to walk off. This is the expensive path, which is exactly why the media
            // wants a short GOP: the decoder has to run from the previous keyframe to here.
            try {
                p.seekTo(targetMs.toInt())
                setRate(p, 1f)
            } catch (t: Throwable) {
                Log.w(TAG, "seek to $targetMs failed: ${t.message}")
            }
            return 1f
        }

        val rate = (1f + (errorMs / 1000f) * CORRECTION_GAIN).coerceIn(MIN_RATE, MAX_RATE)
        setRate(p, rate)
        return rate
    }

    private fun setRate(p: MediaPlayer, rate: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
        try {
            val params = p.playbackParams
            if (kotlin.math.abs(params.speed - rate) < 0.001f) return
            p.playbackParams = params.setSpeed(rate)
        } catch (t: Throwable) {
            Log.d(TAG, "rate $rate rejected: ${t.message}")
        }
    }
}
