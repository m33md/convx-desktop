/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Settings
import com.convx.desktop.integrations.youtube.YouTubeAccount
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeLocale
import java.util.Locale

object AppInfo {
    private val build = java.util.Properties().apply {
        runCatching { AppInfo::class.java.getResourceAsStream("/convx-build.properties")?.use { load(it) } }
    }

    /** 1.0 and the CI build's number, so each installer upgrades the one before; 1.0.0 for a build made elsewhere. */
    val VERSION: String = build.getProperty("version") ?: "1.0.0"

    /** The commit this copy was built from, for the updater; "dev" for a build made elsewhere. */
    val COMMIT: String = build.getProperty("commit") ?: "dev"

    /** The computer this copy runs on, as people say it: "Convx for Mac". */
    val PLATFORM: String = System.getProperty("os.name").lowercase().let { os ->
        when {
            os.contains("mac") -> "Mac"
            os.contains("win") -> "Windows"
            else -> "Linux"
        }
    }
}

/**
 * The countries YouTube Music serves, as the phone lists them (its CountryCodeToName). Asked as any
 * other country, YouTube Music can answer with albums that have no playable songs and empty radios.
 */
private val YouTubeMusicCountries = setOf(
    "DZ", "AR", "AU", "AT", "AZ", "BH", "BD", "BY", "BE", "BO", "BA", "BR", "BG", "KH", "CA", "CL", "HK", "CO", "CR", "HR",
    "CY", "CZ", "DK", "DO", "EC", "EG", "SV", "EE", "FI", "FR", "GE", "DE", "GH", "GR", "GT", "HN", "HU", "IS", "IN", "ID",
    "IQ", "IE", "IL", "IT", "JM", "JP", "JO", "KZ", "KE", "KR", "KW", "LA", "LV", "LB", "LY", "LI", "LT", "LU", "MK", "MY",
    "MT", "MX", "ME", "MA", "NP", "NL", "NZ", "NI", "NG", "NO", "OM", "PK", "PA", "PG", "PY", "PE", "PH", "PL", "PT", "PR",
    "QA", "RO", "RU", "SA", "SN", "RS", "SG", "SK", "SI", "ZA", "ES", "LK", "SE", "CH", "TW", "TZ", "TH", "TN", "TR", "UG",
    "UA", "AE", "GB", "US", "UY", "VE", "VN", "YE", "ZW",
)

/**
 * The phone's App.initializeSettings locale rule: the setting, else this PC's country and language
 * when YouTube Music has them, else US and English.
 */
fun applyLocale(settings: Settings) {
    val system = Locale.getDefault()
    val languages = com.convx.desktop.lyrics.TranslationLanguages.keys
    YouTube.locale = YouTubeLocale(
        gl = settings.country.ifBlank { system.country.uppercase().takeIf { it in YouTubeMusicCountries } ?: "US" },
        hl = settings.language.ifBlank {
            system.language.takeIf { it in languages } ?: system.toLanguageTag().takeIf { it in languages } ?: "en"
        },
    )
    Log.i("YouTube", "Region ${YouTube.locale.gl}, language ${YouTube.locale.hl} (this PC: ${system.toLanguageTag()})")
}

/** Configures the shared YouTube client the way the phone does before its first request. */
suspend fun initYouTube() {
    val settings = AppSettings.value
    YouTube.log = { Log.i("YouTube", it) }
    applyLocale(settings)
    // A signed-in account brings its own cookie and visitor data; the guest's is only a fallback.
    YouTubeAccount.restore()
    if (YouTubeAccount.hasVisitorData) return
    val saved = settings.visitorData?.takeIf { it.isNotBlank() && it != "null" }
    if (saved != null) {
        YouTube.visitorData = saved
    } else {
        YouTube.visitorData().onSuccess { fresh ->
            YouTube.visitorData = fresh
            AppSettings.update { it.copy(visitorData = fresh) }
        }.onFailure { Log.w("Startup", "Couldn't get visitor data", it) }
    }
}
