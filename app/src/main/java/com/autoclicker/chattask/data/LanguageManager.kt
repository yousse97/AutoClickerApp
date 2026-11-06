package com.autoclicker.chattask.data

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Switches the app between English and Arabic.
 *
 * AppCompat handles activities. Below Android 13 it does not reach services, so the overlay gets
 * its text through [localize], which reads the choice saved here.
 */
object LanguageManager {
    private const val PREFS = "language"
    private const val KEY_TAG = "tag"
    private const val ARABIC = "ar"
    private const val ENGLISH = "en"

    private var localized: Pair<String, Context>? = null

    fun toggle(context: Context) {
        val tag = if (isArabic(context)) ENGLISH else ARABIC
        prefs(context).edit().putString(KEY_TAG, tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun isArabic(context: Context): Boolean =
        context.resources.configuration.locales[0].language == ARABIC

    /** A context whose strings follow the chosen language. Cheap enough to call on each render. */
    fun localize(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val tag = prefs(context).getString(KEY_TAG, null) ?: return context
        val cached = localized
        if (cached?.first == tag) return cached.second
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return context.createConfigurationContext(config).also { localized = tag to it }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
