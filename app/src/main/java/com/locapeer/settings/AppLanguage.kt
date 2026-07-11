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
    ENGLISH("en", "English"),
    ENGLISH_UK("en-GB", "English (UK)"),
    ENGLISH_AUSTRALIA("en-AU", "English (Australia)"),
    SPANISH("es", "Español"),
    SPANISH_MEXICO("es-MX", "Español (México)"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt", "Português"),
    PORTUGUESE_BRAZIL("pt-BR", "Português (Brasil)"),
    JAPANESE("ja", "日本語"),
    CHINESE_SIMPLIFIED("zh-rCN", "简体中文"),
    HINDI("hi", "हिन्दी"),
    RUSSIAN("ru", "Русский"),
    KOREAN("ko", "한국어"),
    ARABIC("ar", "العربية"),
    CHINESE_TRADITIONAL("zh-rTW", "繁體中文"),
    DUTCH("nl", "Nederlands"),
    POLISH("pl", "Polski"),
    TURKISH("tr", "Türkçe"),
    VIETNAMESE("vi", "Tiếng Việt"),
    INDONESIAN("in", "Bahasa Indonesia"),
    UKRAINIAN("uk", "Українська"),
    PERSIAN("fa", "فارسی"),
    THAI("th", "ไทย"),
    BENGALI("bn", "বাংলা"),
    HEBREW("iw", "עברית"),
    SWEDISH("sv", "Svenska"),
    CZECH("cs", "Čeština"),
    MALAY("ms", "Bahasa Melayu"),
    FILIPINO("tl", "Filipino"),
    URDU("ur", "اردو"),
    ROMANIAN("ro", "Română"),
    HUNGARIAN("hu", "Magyar"),
    TAMIL("ta", "தமிழ்"),
    TELUGU("te", "తెలుగు"),
    MARATHI("mr", "मराठी"),
    SWAHILI("sw", "Kiswahili"),
    GUJARATI("gu", "ગુજરાતી"),
    KANNADA("kn", "ಕನ್ನಡ"),
    PUNJABI("pa", "ਪੰਜਾਬੀ"),
    MALAYALAM("ml", "മലയാളം"),
    HAUSA("ha", "Hausa"),
    AMHARIC("am", "አማርኛ"),
    BURMESE("my", "မြန်မာဘာသာ"),
    GREEK("el", "Ελληνικά"),
    KURDISH("ku", "Kurdî"),
    PASHTO("ps", "پښتو"),
    KAZAKH("kk", "Қазақ тілі"),
    UZBEK("uz", "O'zbek"),
    YORUBA("yo", "Yorùbá"),
    IGBO("ig", "Asụsụ Igbo"),
    OROMO("om", "Afaan Oromoo"),
    ODIA("or", "ଓଡ଼ିଆ");

    companion object {
        /** The currently applied language, resolved from the persisted app locale. */
        fun current(): AppLanguage {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return SYSTEM
            val locale = locales[0] ?: return SYSTEM
            // Match the full BCP-47 tag first (e.g. "pt-BR", "zh-Hant") so region/script
            // variants resolve, then fall back to the primary language subtag (e.g. "pt").
            val fullTag = locale.toLanguageTag()
            return entries.firstOrNull { it.tag.isNotEmpty() && it.tag.equals(fullTag, ignoreCase = true) }
                ?: entries.firstOrNull { it.tag.isNotEmpty() && it.tag.equals(locale.language, ignoreCase = true) }
                ?: SYSTEM
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
