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

    /** Merges [json] over the stored config, persists the result and returns it. */
    fun applyOverride(json: String): MatrixConfig {
        val merged = MatrixConfig.fromJson(json, load())
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
