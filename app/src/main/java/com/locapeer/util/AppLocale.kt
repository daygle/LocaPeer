package com.locapeer.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * The language the app displays itself in, for code that runs outside an Activity.
 *
 * The language picker applies the choice through AppCompat's per-app locales
 * ([com.locapeer.settings.AppLanguage]). On API 33+ the framework also switches the process
 * default, so [Locale.getDefault] and every context already follow it. On Android 8-12 the
 * choice only reaches AppCompatActivity contexts: notifications, foreground services,
 * broadcast receivers, workers and the synchronous date/number formatters would otherwise
 * stay in the *system* language while the UI is in the app language.
 *
 * This holder keeps the selected tag - written each time the picker applies a language, and
 * adopted from AppCompat's own store for installs that picked one before it existed - and
 * derives both a [Locale] and a locale-aware [Context] from it, so those call sites can opt
 * in. A process that starts after the choice is made picks it up from disk.
 *
 * An empty tag means "follow the system language", which is also the fallback whenever
 * nothing has been stored yet (e.g. JVM unit tests).
 */
object AppLocale {
    private const val PREFS_NAME = "app_locale"
    private const val KEY_TAG = "tag"

    // Application-scoped by construction: this is the Application or its base context, which
    // outlives every caller, never an Activity.
    @SuppressLint("StaticFieldLeak")
    @Volatile private var appContext: Context? = null

    // Cached alongside the locale it was built for; holds the same application-scoped
    // context as [appContext], never an Activity.
    @SuppressLint("StaticFieldLeak")
    @Volatile private var snapshot: Snapshot? = null

    private class Snapshot(val tag: String, val locale: Locale, val context: Context?)

    /**
     * Registers the context every lookup resolves against. Called from
     * [com.locapeer.LocaPeerApplication] as soon as its base context exists, before any
     * service, receiver or worker can need a localized string.
     */
    fun attach(context: Context) {
        appContext = context
        snapshot = null
    }

    /** The stored BCP-47 tag; "" means "follow the system language". */
    fun selectedTag(): String = current().tag

    /** The locale user-visible strings and date/number formats should use. */
    fun locale(): Locale = current().locale

    /** A context whose resources resolve in the app language, or null before [attach]. */
    fun context(): Context? = current().context

    /**
     * Wraps [base] so its resources resolve in the app language. Use it for contexts the
     * framework hands out (services) instead of trusting their default locale.
     */
    fun wrap(base: Context): Context = base.createConfigurationContext(configuration(base, locale()))

    /**
     * Records the language just applied in the picker; "" means "follow the system".
     * A no-op before [attach], which only happens outside a running app (unit tests).
     */
    fun remember(tag: String) {
        val app = appContext ?: return
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAG, tag)
            .apply()
        snapshot = build(app, tag)
    }

    /**
     * Adopts the locale AppCompat persisted for an install that chose a language before this
     * holder existed, so notifications and formatters do not fall back to the system language
     * for a choice AppCompat does hold. Safe to call from an Activity, where AppCompat has
     * already restored its stored value.
     */
    fun adoptStoredAppLocale(context: Context) {
        if (appContext == null) attach(context)
        val locales = AppCompatDelegate.getApplicationLocales()
        // Below API 33 an empty list can also mean AppCompat has not restored its stored
        // locale yet, so only a restored value is adopted; this runs again on the next
        // Activity creation. On API 33+ an empty list is authoritative ("follow the system").
        if (locales.isEmpty && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val tag = if (locales.isEmpty) "" else locales.toLanguageTags()
        if (tag != selectedTag()) remember(tag)
    }

    private fun current(): Snapshot {
        snapshot?.let { return it }
        val app = appContext ?: return Snapshot("", Locale.getDefault(), null)
        val stored = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TAG, null)
            .orEmpty()
        return build(app, stored).also { snapshot = it }
    }

    private fun build(context: Context, tag: String): Snapshot {
        val locale = if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)
        return Snapshot(tag, locale, context.createConfigurationContext(configuration(context, locale)))
    }

    private fun configuration(base: Context, locale: Locale): Configuration =
        Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        }
}
