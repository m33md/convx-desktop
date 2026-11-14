/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import java.io.File

/** Where the app keeps its library, settings and caches on this machine. */
object AppDirs {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    val data: File by lazy {
        val base = if (isWindows) {
            System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Roaming")
        } else if (isMac) {
            File(System.getProperty("user.home"), "Library/Application Support")
        } else {
            System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        }
        File(base, "Convx").apply { mkdirs() }
    }

    val cache: File by lazy {
        val base = if (isWindows) {
            System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
        } else if (isMac) {
            File(System.getProperty("user.home"), "Library/Caches")
        } else {
            System.getenv("XDG_CACHE_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
        }
        File(base, "Convx").apply { mkdirs() }
    }

    val images: File by lazy { File(cache, "images").apply { mkdirs() } }
    val lyrics: File by lazy { File(cache, "lyrics").apply { mkdirs() } }
    val logs: File by lazy { File(cache, "logs").apply { mkdirs() } }
}
