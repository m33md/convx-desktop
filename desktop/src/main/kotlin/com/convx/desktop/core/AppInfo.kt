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

/** The phone's App.initializeSettings locale rule: setting, else the system's, else US/English. */
fun applyLocale(settings: Settings) {
    val system = Locale.getDefault()
    YouTube.locale = YouTubeLocale(
        gl = settings.country.ifBlank { system.country.takeIf { it.length == 2 } ?: "US" },
        hl = settings.language.ifBlank { system.language.takeIf { it.isNotBlank() } ?: "en" },
    )
}

/** Configures the shared YouTube client the way the phone does before its first request. */
suspend fun initYouTube() {
    val settings = AppSettings.value
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
