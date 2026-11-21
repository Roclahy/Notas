package com.omgodse.notally.preferences

import android.content.Context
import android.content.res.Configuration
import android.preference.PreferenceManager
import java.util.Locale

/**
 * Per-app language without additional dependencies.
 * Supports Android 7+ through createConfigurationContext.
 */
object RoseLanguage {
    const val KEY = "uiLanguage"
    const val SYSTEM = "system"
    const val SPANISH = "es"
    const val ENGLISH = "en"

    fun selected(context: Context): String {
        val saved = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY, SYSTEM)
        return saved?.takeIf { it in setOf(SYSTEM, SPANISH, ENGLISH) } ?: SYSTEM
    }

    fun set(context: Context, language: String): Boolean {
        require(language in setOf(SYSTEM, SPANISH, ENGLISH))
        return PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(KEY, language).commit()
    }

    fun wrap(context: Context): Context {
        val language = selected(context)
        val locale = if (language == SYSTEM) {
            context.resources.configuration.locales[0]
        } else Locale.forLanguageTag(language)
        // Keeps DateFormat and PrettyTime consistent with the selected UI language.
        Locale.setDefault(locale)
        if (language == SYSTEM) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
