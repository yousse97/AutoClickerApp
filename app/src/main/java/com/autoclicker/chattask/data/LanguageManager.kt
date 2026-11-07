package com.autoclicker.chattask.data

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.autoclicker.chattask.R
import java.util.Locale

/** The two languages of the app, and their rows in the language selector. */
enum class LanguageChoice(val tag: String, @IdRes val itemId: Int) {
    ARABIC("ar", R.id.languageArabic),
    ENGLISH("en", R.id.languageEnglish),
    ;

    companion object {
        fun fromItem(@IdRes itemId: Int): LanguageChoice? = entries.firstOrNull { it.itemId == itemId }

        fun fromLanguage(language: String): LanguageChoice = if (language == ARABIC.tag) ARABIC else ENGLISH
    }
}

/**
 * Switches the app between English and Arabic.
 *
 * AppCompat handles activities. Below Android 13 it does not reach services, so the overlay gets
 * its text through [localize], which reads the choice saved here.
 */
object LanguageManager {
    private const val PREFS = "language"
    private const val KEY_TAG = "tag"

    private var localized: Pair<String, Context>? = null

    fun select(context: Context, choice: LanguageChoice) {
        prefs(context).edit().putString(KEY_TAG, choice.tag).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(choice.tag))
    }

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

    fun releaseCache() {
        localized = null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
