package eu.elraro.openreader

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/** User settings: theme, language and document dark mode. */
object Prefs {
    const val REPO_URL = "https://github.com/elraro/openreader"
    const val DONATE_URL = "https://paypal.me/elraro"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    /** Language tags offered in the settings; "" follows the device language. */
    val LANGUAGES = listOf(
        "" to "", "en" to "English", "es" to "Español", "ca" to "Català", "fr" to "Français", "de" to "Deutsch",
        "it" to "Italiano", "pt" to "Português", "ru" to "Русский", "zh" to "中文", "ja" to "日本語",
    )

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun theme(c: Context) = sp(c).getInt("theme", THEME_SYSTEM)
    fun setTheme(c: Context, v: Int) = sp(c).edit().putInt("theme", v).apply()

    fun language(c: Context) = sp(c).getString("lang", "") ?: ""
    fun setLanguage(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()

    fun darkDocuments(c: Context) = sp(c).getBoolean("darkDocs", false)
    fun setDarkDocuments(c: Context, v: Boolean) = sp(c).edit().putBoolean("darkDocs", v).apply()

    fun isDark(c: Context): Boolean = when (theme(c)) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun themeRes(c: Context): Int =
        if (isDark(c)) android.R.style.Theme_Material else android.R.style.Theme_Material_Light_DarkActionBar

    /** Wraps a base context so resources use the language chosen in settings (device language by default). */
    fun wrap(base: Context): Context {
        val tag = language(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}
