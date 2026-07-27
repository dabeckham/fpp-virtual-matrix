package app.fppvm.tv.focus

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Keeps the show on screen when something else tries to take the panel.
 *
 * A display running a show all night should not be interrupted by a launcher, a screensaver or a
 * stray remote press. But an app that is both the home screen *and* fights for focus can lock a
 * person out of their own television, so every part of this is built around being escapable:
 *
 *  - it does nothing unless a show is actually playing;
 *  - holding BACK releases it for [RELEASE_MINUTES], long enough to do whatever you needed;
 *  - the web UI can switch it off outright, from a phone, without touching the TV.
 *
 * This is the polite tier. True kiosk lock needs device-owner provisioning
 * (`dpm set-device-owner`), which requires a device with no accounts on it — worth doing on a
 * dedicated show TV, not on one that is already signed in.
 *
 * Note the Android version limit: re-launching ourselves from the background works on API 28.
 * Android 10 and later block background activity starts, where this would need a foreground
 * service to remain effective.
 */
class FocusGuard(
    private val activity: Activity,
    /** Whether a show is playing right now — the guard is inert otherwise. */
    private val showPlaying: () -> Boolean,
    /** User setting; false disables the guard entirely. */
    private val enabled: () -> Boolean
) {
    companion object {
        private const val TAG = "FppFocus"

        /** Long enough that a bounce-back is not annoying, short enough to look deliberate. */
        const val REGRAB_DELAY_MS = 2_000L

        /** How long a BACK hold buys you before the guard resumes. */
        const val RELEASE_MINUTES = 5L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var releasedUntilMs = 0L

    private val regrab = Runnable {
        if (!shouldHold()) return@Runnable
        try {
            activity.startActivity(
                Intent(activity, activity.javaClass).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            Log.i(TAG, "took focus back mid-show")
        } catch (t: Throwable) {
            // Android 10+ refuses background starts. Nothing to do but leave the show alone.
            Log.w(TAG, "could not take focus back: ${t.message}")
        }
    }

    private fun shouldHold(): Boolean =
        enabled() && showPlaying() && System.currentTimeMillis() >= releasedUntilMs

    /** Call when the window loses focus or the activity stops. */
    fun onFocusLost() {
        handler.removeCallbacks(regrab)
        if (!shouldHold()) return
        handler.postDelayed(regrab, REGRAB_DELAY_MS)
    }

    fun onFocusGained() {
        handler.removeCallbacks(regrab)
    }

    /** Stand down for [RELEASE_MINUTES]; the escape hatch behind the BACK hold. */
    fun release(): Long {
        releasedUntilMs = System.currentTimeMillis() + RELEASE_MINUTES * 60_000L
        handler.removeCallbacks(regrab)
        Log.i(TAG, "focus hold released for $RELEASE_MINUTES minutes")
        return releasedUntilMs
    }

    fun isReleased(): Boolean = System.currentTimeMillis() < releasedUntilMs

    /** Minutes left on a release, 0 when the guard is active. */
    fun releaseMinutesRemaining(): Long {
        val left = releasedUntilMs - System.currentTimeMillis()
        return if (left <= 0) 0 else (left + 59_999) / 60_000
    }

    fun cancel() {
        handler.removeCallbacks(regrab)
    }
}
