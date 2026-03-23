package org.telegram.messenger.tgws

import android.content.Context

class ConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

    fun load(): ProxyConfig {
        val raw = prefs.getString("config", null)
        return ProxyConfig.fromJson(raw) ?: ProxyConfig()
    }

    fun save(config: ProxyConfig) {
        prefs.edit().putString("config", config.toJson()).apply()
    }

    fun setShouldRun(value: Boolean) {
        prefs.edit().putBoolean("should_run", value).apply()
    }

    fun shouldRun(): Boolean {
        return prefs.getBoolean("should_run", false)
    }

    fun setDarkTheme(value: Boolean) {
        prefs.edit().putBoolean("dark_theme", value).apply()
    }

    fun getDarkTheme(): Boolean {
        return prefs.getBoolean("dark_theme", false)
    }
}

