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

    /**
     * Covers, the song cache, logs and the like. On Windows the installer puts Convx itself in
     * %LOCALAPPDATA%\Convx and empties that folder on every update, so the cache sits beside it,
     * in %LOCALAPPDATA%\Convx Cache, where an update leaves it alone.
     */
    val cache: File by lazy {
        if (isWindows) {
            val local = System.getenv("LOCALAPPDATA")?.let(::File) ?: File(System.getProperty("user.home"), "AppData/Local")
            File(local, "Convx Cache").apply {
                mkdirs()
                moveOldCache(File(local, "Convx"), this)
            }
        } else {
            val base = if (isMac) {
                File(System.getProperty("user.home"), "Library/Caches")
            } else {
                System.getenv("XDG_CACHE_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
            }
            File(base, "Convx").apply { mkdirs() }
        }
    }

    /** What Convx kept in its install folder, which a copy of Convx that wasn't updated by the installer may still have. */
    private val CacheParts = listOf("images", "lyrics", "logs", "audio", "local-covers", "module-scripts", "apple_catalog")

    private fun moveOldCache(old: File, into: File) {
        for (name in CacheParts) {
            val from = File(old, name)
            val to = File(into, name)
            if (!from.isDirectory || to.exists()) continue
            runCatching { java.nio.file.Files.move(from.toPath(), to.toPath()) }
        }
    }

    val images: File by lazy { File(cache, "images").apply { mkdirs() } }
    val lyrics: File by lazy { File(cache, "lyrics").apply { mkdirs() } }
    val logs: File by lazy { File(cache, "logs").apply { mkdirs() } }
}
