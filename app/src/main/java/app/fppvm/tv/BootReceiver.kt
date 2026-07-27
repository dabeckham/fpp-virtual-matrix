package app.fppvm.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.fppvm.tv.config.ConfigStore

/** Brings the matrix back after a power cycle, so a show display needs no human to restart it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ConfigStore(context).autoStartOnBoot) return
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            context.startActivity(launch)
        } catch (_: Throwable) {
            // Some TV builds block activity starts from BOOT_COMPLETED; the HOME intent-filter is
            // the fallback path for those.
        }
    }
}
