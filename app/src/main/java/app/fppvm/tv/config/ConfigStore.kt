package app.fppvm.tv.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists [MatrixConfig].
 *
 * Also accepts a whole config as an intent extra so a matrix can be reconfigured from a laptop
 * without touching the remote control:
 *
 * ```
 * adb shell "am start -n app.fppvm.tv/.MainActivity --es config '{\"width\":96,\"height\":48}'"
 * ```
 *
 * An override is merged over the stored config and then persisted, so a partial JSON only changes
 * the fields it names.
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("fppvm", Context.MODE_PRIVATE)

    fun load(): MatrixConfig {
        val json = prefs.getString(KEY_CONFIG, null) ?: return MatrixConfig.DEFAULT
        return MatrixConfig.fromJson(json, MatrixConfig.DEFAULT)
    }

    fun save(config: MatrixConfig) {
        prefs.edit().putString(KEY_CONFIG, config.validated().toJson().toString()).apply()
    }

    /**
     * Merges [json] over the stored config, persists the result and returns it.
     *
     * `{"profileId":"p10","applyProfile":true}` loads that profile's appearance first, so the web
     * page can switch product without having to know every field a profile carries. Anything else
     * in the same object is then applied on top, which is what makes "load P10, but round" work.
     */
    fun applyOverride(json: String): MatrixConfig {
        var base = load()
        try {
            val o = org.json.JSONObject(json)
            if (o.optBoolean("applyProfile", false)) {
                val id = o.optString("profileId", "")
                app.fppvm.tv.panel.LedProfiles.byId(id)?.let { base = base.applyProfile(it) }
            }
        } catch (t: Throwable) {
            // A malformed body just means no profile switch; the merge below still runs safely.
        }
        val merged = MatrixConfig.fromJson(json, base)
        save(merged)
        return merged
    }

    fun reset(): MatrixConfig {
        prefs.edit().remove(KEY_CONFIG).apply()
        return MatrixConfig.DEFAULT
    }

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    companion object {
        private const val KEY_CONFIG = "config"
        private const val KEY_AUTOSTART = "autostart"
        const val EXTRA_CONFIG = "config"
    }
}
