/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File

/**
 * The phone's System update, for the PC: every build CI makes is published as one rolling
 * release, and this compares that release's commit with the one this copy was built from, lists
 * what changed in between, and installs the new installer over this one, opening it again after.
 */
object Updater {
    private const val Tag = "Updater"
    private const val Repo = "m33md/convx-desktop"
    private const val ReleaseTag = "desktop-latest"

    class Release(val version: String, val commit: String, val installerUrl: String, val installerSize: Long, val changes: List<String>) {
        /** Convx's own setup .exe (newer builds) rather than the bare MSI. */
        val isSetup: Boolean get() = installerUrl.substringAfterLast('/').startsWith("Convx-Setup-") && installerUrl.endsWith(".exe", ignoreCase = true)
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by the window: closes the app the usual way, saving everything, so the installer can replace it. */
    @Volatile var quit: (() -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether this copy can update itself: an installed Convx.exe, not a run from the build. */
    val canInstall: Boolean get() = installedExe() != null

    /** On a Mac the update is a disk image to open, so [canInstall] is false there. */
    val isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac")
    private val appleSilicon: Boolean = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }

    suspend fun check(): State = withContext(Dispatchers.IO) {
        val now = _state.value
        if (now is State.Checking || now is State.Downloading) return@withContext now
        _state.value = State.Checking
        val result = runCatching { latest() }.getOrElse {
            Log.w(Tag, "Couldn't check for updates: ${it.message}")
            State.Failed("Couldn't reach GitHub. Check your connection and try again.")
        }
        _state.value = result
        result
    }

    private fun latest(): State {
        val release = get("https://api.github.com/repos/$Repo/releases/tags/$ReleaseTag").jsonObject
        val notes = release.string("body")
        val commit = Regex("""/tree/([0-9a-f]{40})""").find(notes)?.groupValues?.get(1) ?: error("the release names no build")
        val version = Regex("""Version (\d+(?:\.\d+)+)""").find(notes)?.groupValues?.get(1) ?: commit.take(7)
        if (commit == AppInfo.COMMIT) return State.UpToDate
        // What the newer build adds over this one; a build GitHub can't place (made elsewhere) shows none.
        val compare = runCatching { get("https://api.github.com/repos/$Repo/compare/${AppInfo.COMMIT}...$commit").jsonObject }.getOrNull()
        if (compare != null && compare.string("status") in setOf("behind", "identical")) return State.UpToDate
        val assets = release["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val installer = if (isMac) {
            // The disk image made for this kind of Mac.
            val wanted = if (appleSilicon) "-mac-apple-silicon.dmg" else "-mac-intel.dmg"
            assets.firstOrNull { it.string("name").endsWith(wanted, ignoreCase = true) }
                ?: return State.Failed("The newest version has no download for this Mac yet. Try again later.")
        } else {
            // Convx's setup .exe when the release has one (it shows its own progress window and keeps
            // Windows' Installed apps entry right), else the MSI older releases carried.
            assets.firstOrNull { it.string("name").let { n -> n.startsWith("Convx-Setup-") && n.endsWith(".exe", ignoreCase = true) } }
                ?: assets.firstOrNull { it.string("name").endsWith(".msi", ignoreCase = true) }
                ?: error("the release has no installer")
        }
        val changes = compare?.get("commits")?.jsonArray.orEmpty()
            .map { it.jsonObject["commit"]?.jsonObject?.string("message").orEmpty().lineSequence().first().trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Merge ") }
            .map { it.removePrefix("Desktop: ").replaceFirstChar(Char::uppercaseChar) }
            .reversed()
        Log.i(Tag, "Build $version (${commit.take(7)}) is available; this is ${AppInfo.VERSION} (${AppInfo.COMMIT.take(7)})")
        return State.Available(
            Release(version, commit, installer.string("browser_download_url"), installer["size"]?.jsonPrimitive?.longOrNull ?: 0L, changes),
        )
    }

    /** Downloads the new installer, then closes Convx and runs it; Convx opens again when it's done. */
    suspend fun downloadAndInstall() = withContext(Dispatchers.IO) {
        val release = (_state.value as? State.Available)?.release ?: return@withContext
        val dir = File(AppDirs.data, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, if (release.isSetup) "Convx-Setup-${release.version}.exe" else "Convx-${release.version}.msi")
        _state.value = State.Downloading(release, 0f)
        val ok = runCatching {
            val request = Request.Builder().url(release.installerUrl).header("User-Agent", "ConvxDesktop/${AppInfo.VERSION}").build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub answered ${response.code}")
                val body = response.body ?: error("no download")
                val total = body.contentLength().takeIf { it > 0 } ?: release.installerSize
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = 0f
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val progress = if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else 0f
                            if (progress - shown >= 0.01f) {
                                shown = progress
                                _state.value = State.Downloading(release, progress)
                            }
                        }
                    }
                }
            }
            if (release.installerSize > 0 && file.length() != release.installerSize) error("the download was cut short")
        }.onFailure {
            Log.w(Tag, "Couldn't download the update: ${it.message}")
            file.delete()
            _state.value = State.Failed("The download didn't finish. Try again.")
        }.isSuccess
        if (ok) install(file, release)
    }

    private suspend fun install(file: File, release: Release) {
        val exe = installedExe()
        val script = File(file.parentFile, "install.cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("title Updating Convx")
                appendLine("echo Updating Convx to ${release.version}. It opens again by itself when it's done.")
                // A moment for Convx to close, so the installer can replace its files.
                appendLine("ping -n 4 127.0.0.1 >nul")
                if (release.isSetup) {
                    // The setup window shows only its progress; InstallFolder keeps Convx where it is.
                    append("start \"\" /wait \"${file.absolutePath}\" /passive /norestart")
                    if (exe != null) append(" InstallFolder=\"${exe.parentFile.absolutePath}\"")
                } else {
                    append("start \"\" /wait msiexec /i \"${file.absolutePath}\" /passive /norestart")
                    if (exe != null) append(" INSTALLDIR=\"${exe.parentFile.absolutePath}\"")
                }
                appendLine()
                if (exe != null) appendLine("start \"\" \"${exe.absolutePath}\"")
            },
        )
        Log.i(Tag, "Installing ${release.version} from ${file.name}")
        runCatching { ProcessBuilder("cmd.exe", "/c", script.absolutePath).directory(file.parentFile).start() }
            .onFailure {
                Log.w(Tag, "Couldn't start the installer: ${it.message}")
                _state.value = State.Failed("Couldn't start the installer. It's saved in ${file.parentFile.absolutePath}.")
                return
            }
        withContext(Dispatchers.Main) { quit?.invoke() ?: kotlin.system.exitProcess(0) }
    }

    private fun installedExe(): File? =
        ProcessHandle.current().info().command().orElse(null)?.let(::File)
            ?.takeIf { it.isFile && it.name.equals("Convx.exe", ignoreCase = true) }

    private fun get(url: String): JsonElement {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ConvxDesktop/${AppInfo.VERSION}")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub answered ${response.code}")
            return json.parseToJsonElement(response.body?.string().orEmpty())
        }
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
