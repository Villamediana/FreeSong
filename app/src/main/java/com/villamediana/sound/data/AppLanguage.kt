package com.villamediana.sound.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language, apart from the phone's: Português, Español, English, or the phone's
 * ([SYSTEM]). Android 13+ keeps it itself (and shows it in the system's app settings too); before
 * that it is kept here and every context of the app is made in it ([wrap]).
 *
 * YouTube Music follows it ([contentLocale]): what it answers comes in the language on screen.
 */
object AppLanguage {
    const val SYSTEM = ""

    /** The choices, in the settings' order. */
    val CHOICES = listOf(SYSTEM, "pt", "es", "en")

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    /** The language chosen ([SYSTEM] = the phone's). */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return locales?.takeIf { !it.isEmpty }?.get(0)?.language?.takeIf { it in CHOICES } ?: SYSTEM
        }
        return stored(context)
    }

    /** Switches to [tag]: the screen comes back in it. */
    fun set(activity: Activity, tag: String) {
        if (current(activity) == tag) return
        if (Build.VERSION.SDK_INT >= 33) {
            // Android redraws the app in it by itself.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList(localeOf(tag))
            return
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        activity.recreate()
    }

    /** Android 8–12: [base] in the language chosen (the activity and the application wrap theirs with it). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base)
        if (tag == SYSTEM) return base
        val locale = localeOf(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** [tag] with the phone's country (numbers and dates keep the phone's region). */
    private fun localeOf(tag: String): Locale {
        val country = Resources.getSystem().configuration.locales[0]?.country.orEmpty()
        return runCatching { Locale.Builder().setLanguage(tag).setRegion(country).build() }.getOrElse { Locale(tag) }
    }

    /**
     * The language YouTube Music is asked for: the one the app shows (the strings Android picked, not
     * a phone language the app has no words for), with the phone's country.
     */
    fun contentLocale(context: Context): Locale {
        val language = context.getString(com.villamediana.sound.R.string.content_language).ifBlank { "pt" }
        val country = Resources.getSystem().configuration.locales[0]?.country?.takeIf { it.length == 2 } ?: "BR"
        return Locale(language, country)
    }

    private fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)?.takeIf { it in CHOICES } ?: SYSTEM
}
