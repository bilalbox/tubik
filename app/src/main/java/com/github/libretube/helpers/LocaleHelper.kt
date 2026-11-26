package com.github.libretube.helpers

import android.os.Build
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.obj.Country
import java.util.Locale

object LocaleHelper {
    @Deprecated("Only used for SDKs below 33 for compatibility")
    fun getAppLocale(): Locale {
        val languageName = PreferenceHelper.getString(PreferenceKeys.LANGUAGE, "sys")
        return when {
            languageName == "sys" -> Locale.getDefault()
            else -> getLocaleFromAndroidCode(languageName)
        }
    }

    fun isPerAppLocaleSettingSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun getLocaleFromAndroidCode(code: String): Locale {
        val normalizedCode = code.replace("-r", "-")
        return if (normalizedCode.contains("-")) {
            val parts = normalizedCode.split("-", limit = 2)
            Locale(parts[0], parts[1].uppercase())
        } else {
            Locale(normalizedCode)
        }
    }

    fun getAvailableLocales(): List<Country> {
        return Locale.getAvailableLocales()
            .distinctBy { it.language }
            .map { Country(it.displayLanguage, it.language) }
            .sortedBy { it.name }
    }
}
