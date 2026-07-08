package com.locapeer.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The languages LocaPeer offers in its in-app language selector.
 *
 * [tag] is a BCP-47 language tag; an empty tag means "follow the system language".
 * [nativeName] is the language's own name for the picker (null for [SYSTEM], which is
 * labelled from a localized string resource instead).
 *
 * Keep this list in sync with res/xml/locales_config.xml. Adding a language is three steps:
 *   1. Translate res/values-<tag>/strings.xml
 *   2. Add a <locale> entry to res/xml/locales_config.xml
 *   3. Add an entry here
 *
 * Selecting a language with no translation resources simply falls back to the base
 * (English) strings, which is the standard Android resource-resolution behaviour.
 */
enum class AppLanguage(val tag: String, val nativeName: String?) {
    SYSTEM("", null),
    ENGLISH("en", "English");

    companion object {
        /** The currently applied language, resolved from the persisted app locale. */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            val language = locales[0]?.language ?: return SYSTEM
            return entries.firstOrNull { it.tag.isNotEmpty() && it.tag == language } ?: SYSTEM
        }

        /**
         * Applies [language] as the app locale. AppCompat persists the choice and recreates
         * the running activities so the new language takes effect immediately.
         */
        fun apply(language: AppLanguage) {
            val locales = if (language.tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
