package com.itzdfplayer.opengimbal.ui

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.itzdfplayer.opengimbal.R

/**
 * The languages the app ships, in the order the picker offers them.
 *
 * The labels are the language names written in that language - "Deutsch", not "German" -
 * which is what every other picker on the phone does, because someone looking for their own
 * language is looking for the word they would write themselves.
 */
enum class AppLanguage(
    /** The BCP-47 tag, as the system wants it. Empty means "follow the phone". */
    val tag: String,
    @param:StringRes val labelRes: Int,
) {
    SYSTEM("", R.string.language_system),

    // Sorted by the name each one shows, not by language code, because that is the order the
    // user scans while looking for their own language. "System default" stays on top because
    // it is a different kind of answer.
    GERMAN("de", R.string.language_de),
    ENGLISH("en", R.string.language_en),
    SPANISH("es", R.string.language_es),
    FRENCH("fr", R.string.language_fr),
    ITALIAN("it", R.string.language_it),
    HUNGARIAN("hu", R.string.language_hu),
    POLISH("pl", R.string.language_pl),
    PORTUGUESE("pt", R.string.language_pt),
    RUSSIAN("ru", R.string.language_ru),
    UKRAINIAN("uk", R.string.language_uk),
    JAPANESE("ja", R.string.language_ja),
    CHINESE_SIMPLIFIED("zh-CN", R.string.language_zh_cn),
    ;

    companion object {
        /** The language the app is currently set to, or [SYSTEM] if it follows the phone. */
        fun current(context: Context): AppLanguage {
            val tag = applicationLocalesTag(context) ?: return SYSTEM

            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it }

            // Fall back to the primary language, because the language can also be set from the
            // system's own per-app language screen, and that is free to store a qualified tag
            // ("de-DE") where the picker offers the bare one ("de"). To the user those are the
            // same choice, and showing "System default" after they picked German would be
            // wrong about something they explicitly asked for.
            val language = tag.substringBefore('-')
            return entries.firstOrNull {
                it.tag.isNotEmpty() && it.tag.substringBefore('-').equals(language, ignoreCase = true)
            } ?: SYSTEM
        }

        /** True on the versions where a per-app language can actually be chosen. */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
}

/**
 * The app's own language setting as the system holds it, or `null` when it follows the phone.
 *
 * Zero locales is not the same as "no setting": the platform uses an empty list to mean the
 * app should follow the system, which is a choice the user can make and one the picker has to
 * show back to them.
 *
 * The whole tag is compared, not just the language: `locale.language` drops the region, so a
 * choice of `zh-CN` would come back as `zh`, match nothing in the list, and leave the picker
 * showing "System default" for a language the user had explicitly chosen.
 */
private fun applicationLocalesTag(context: Context): String? {
    if (!AppLanguage.isSupported) return null
    val manager = context.getSystemService(LocaleManager::class.java) ?: return null
    val locales = manager.applicationLocales
    if (locales.isEmpty) return null
    return locales.get(0)?.toLanguageTag()
}

/**
 * Sets the app's language.
 *
 * On Android 13 and newer this is the platform's own per-app language, which persists across
 * restarts and is also offered in the system settings - so the app and the phone agree about
 * what is selected. Below that there is no platform support, and the honest options were to
 * wrap the whole app in AppCompat purely to get its backport or to leave the setting out; the
 * picker says so instead of pretending.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun setAppLanguage(context: Context, language: AppLanguage) {
    val manager = context.getSystemService(LocaleManager::class.java) ?: return
    val locales = if (language == AppLanguage.SYSTEM) {
        LocaleList.getEmptyLocaleList()
    } else {
        LocaleList.forLanguageTags(language.tag)
    }
    manager.applicationLocales = locales

    // The platform recreates the app for its own language change, but doing it here as well
    // makes the switch immediate and independent of the order the system gets round to it.
    context.findActivity()?.recreate()
}

/** The Activity behind a Compose context, which may be wrapped one or more layers deep. */
private fun Context.findActivity(): android.app.Activity? {
    var candidate: Context? = this
    while (candidate is ContextWrapper) {
        if (candidate is android.app.Activity) return candidate
        candidate = candidate.baseContext
    }
    return null
}
