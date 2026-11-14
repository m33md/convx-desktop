/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations.youtube

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.core.WinProcess
import com.convx.desktop.data.AppSettings
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Kernel32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google sign-in for YouTube Music, done in a real browser window.
 *
 * The phone signs in inside Android's WebView and reads the cookies and `ytcfg` values from it.
 * A PC has no WebView, and Google turns away sign-ins from embedded browsers such as CEF, and
 * from a browser something is driving over its debugging port. So Convx opens a browser of yours
 * (Windows' default if it can, or the one picked on the Account page) with a fresh, empty profile
 * of its own and nothing else: an ordinary window, which Google accepts. You sign in, and close
 * the window once YouTube Music shows your account (or press Done in Convx, which closes it for
 * you). Then Convx reads music.youtube.com's cookies from that profile and deletes it:
 *
 * - Chrome, Edge, Brave, Opera, Vivaldi and the other Chromium browsers encrypt their cookies, so
 *   Convx opens the profile once more, out of sight, and asks the browser for them over its
 *   debugging port, with the page's visitor data and channel id (exactly what the phone keeps).
 * - Firefox and the browsers built on it keep cookies in a plain database, which Convx reads
 *   itself; the visitor data and channel id then come from YouTube Music's page.
 *
 * The window is followed by its profile rather than the process Convx started, because some
 * browsers (Opera's launcher, Firefox) hand the window to another process and exit.
 */
object BrowserSignIn {
    private const val Tag = "BrowserSignIn"
    private const val StartUrl = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"
    private const val MusicHost = "music.youtube.com"
    private const val TimeoutMs = 15 * 60_000L

    /** Cookies that only exist once a Google account is signed in; guest pages set the others. */
    private val AccountCookies = setOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
    )

    data class Session(val cookie: String, val visitorData: String?, val dataSyncId: String?)

    /** How a browser keeps its cookies, which decides how Convx reads them. */
    enum class Engine { Chromium, Firefox, Safari }

    /** A browser on this PC the sign-in can open. */
    data class Browser(val name: String, val path: String, val engine: Engine, val isDefault: Boolean = false)

    class NoBrowserException : Exception("Convx needs a browser such as Chrome, Edge, Firefox or Opera to sign in to Google")
    class ClosedException : Exception("The sign-in window was closed before you signed in")

    /** The sign-in window while it's open, for [finish]. */
    @Volatile private var window: Window? = null

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    /** Browsers a Mac sign-in can use: name, app folder, program inside it, and the bundle id the system names it by. */
    private data class MacApp(val name: String, val app: String, val program: String, val bundleId: String, val engine: Engine)

    private val MacApps = listOf(
        MacApp("Google Chrome", "Google Chrome.app", "Google Chrome", "com.google.chrome", Engine.Chromium),
        MacApp("Microsoft Edge", "Microsoft Edge.app", "Microsoft Edge", "com.microsoft.edgemac", Engine.Chromium),
        MacApp("Brave", "Brave Browser.app", "Brave Browser", "com.brave.browser", Engine.Chromium),
        MacApp("Opera", "Opera.app", "Opera", "com.operasoftware.opera", Engine.Chromium),
        MacApp("Vivaldi", "Vivaldi.app", "Vivaldi", "com.vivaldi.vivaldi", Engine.Chromium),
        MacApp("Chromium", "Chromium.app", "Chromium", "org.chromium.chromium", Engine.Chromium),
        MacApp("Arc", "Arc.app", "Arc", "company.thebrowser.browser", Engine.Chromium),
        MacApp("Firefox", "Firefox.app", "firefox", "org.mozilla.firefox", Engine.Firefox),
        MacApp("Zen", "Zen.app", "zen", "app.zen-browser.zen", Engine.Firefox),
        MacApp("LibreWolf", "LibreWolf.app", "librewolf", "org.mozilla.librewolf", Engine.Firefox),
        MacApp("Floorp", "Floorp.app", "floorp", "one.ablaze.floorp", Engine.Firefox),
        MacApp("Waterfox", "Waterfox.app", "waterfox", "net.waterfox.waterfox", Engine.Firefox),
    )

    /** The https handler macOS has chosen, as a bundle id (lower case), from LaunchServices' own record. */
    private fun macDefaultBundleId(): String? = runCatching {
        val plist = File(System.getProperty("user.home"), "Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist")
        if (!plist.isFile) return null
        val process = ProcessBuilder("plutil", "-convert", "json", "-o", "-", plist.path).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val handlers = Json.parseToJsonElement(text).jsonObject["LSHandlers"]?.jsonArray ?: return null
        handlers.map { it.jsonObject }
            .firstOrNull { it["LSHandlerURLScheme"]?.jsonPrimitive?.contentOrNull == "https" }
            ?.get("LSHandlerRoleAll")?.jsonPrimitive?.contentOrNull?.lowercase()
    }.getOrNull()

    private fun macBrowsers(): List<Browser> {
        val roots = listOf(File("/Applications"), File(System.getProperty("user.home"), "Applications"))
        val default = macDefaultBundleId()
        val safari = listOf(File("/Applications/Safari.app"), File("/System/Applications/Safari.app")).firstOrNull { it.isDirectory }
            ?.let { Browser("Safari", it.absolutePath, Engine.Safari, isDefault = default == "com.apple.safari") }
        val others = MacApps.flatMap { app ->
            roots.map { File(it, "${app.app}/Contents/MacOS/${app.program}") }
                .filter { it.isFile }
                .map { Browser(app.name, it.absolutePath, app.engine, isDefault = app.bundleId == default) }
        }
        return (listOfNotNull(safari) + others).sortedByDescending { it.isDefault }
    }

    /** The browsers on this PC Convx can sign in with, the system's default first. */
    fun browsers(): List<Browser> {
        if (isMac) return macBrowsers()
        if (!isWindows) return emptyList()
        val found = LinkedHashMap<String, Browser>()
        for ((name, exe) in registered() + knownPaths()) {
            if (!exe.isFile) continue
            val engine = engineOf(exe) ?: continue
            found.getOrPut(exe.absolutePath.lowercase()) { Browser(name, exe.absolutePath, engine) }
        }
        val default = defaultBrowser()?.absolutePath?.lowercase()
        return found.values.map { it.copy(isDefault = it.path.lowercase() == default) }.sortedByDescending { it.isDefault }
    }

    /** The browser a sign-in opens: the one picked on the Account page, else Windows' default, else any. */
    fun chosen(browsers: List<Browser> = browsers()): Browser? {
        val picked = AppSettings.value.signInBrowser?.lowercase()
        return browsers.firstOrNull { it.path.lowercase() == picked } ?: browsers.firstOrNull { it.isDefault } ?: browsers.firstOrNull()
    }

    /** Browsers registered with Windows (Settings, Default apps), by the name they give themselves. */
    private fun registered(): List<Pair<String, File>> = buildList {
        val places = listOf(
            WinReg.HKEY_CURRENT_USER to "SOFTWARE\\Clients\\StartMenuInternet",
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\Clients\\StartMenuInternet",
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\WOW6432Node\\Clients\\StartMenuInternet",
        )
        for ((root, path) in places) {
            val keys = runCatching { Advapi32Util.registryGetKeys(root, path) }.getOrNull() ?: continue
            for (key in keys) {
                val exe = registryText(root, "$path\\$key\\shell\\open\\command")?.let(::exeOf) ?: continue
                val name = registryText(root, "$path\\$key")?.takeIf { it.isNotBlank() } ?: key
                add(name to exe)
            }
        }
    }

    /** Where the usual browsers install, for any that didn't register. */
    private fun knownPaths(): List<Pair<String, File>> {
        val roots = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA"))
        return listOf(
            "Google Chrome" to "Google/Chrome/Application/chrome.exe",
            "Microsoft Edge" to "Microsoft/Edge/Application/msedge.exe",
            "Brave" to "BraveSoftware/Brave-Browser/Application/brave.exe",
            "Opera GX" to "Programs/Opera GX/launcher.exe",
            "Opera GX" to "Opera GX/launcher.exe",
            "Opera" to "Programs/Opera/launcher.exe",
            "Opera" to "Opera/launcher.exe",
            "Vivaldi" to "Vivaldi/Application/vivaldi.exe",
            "Chromium" to "Chromium/Application/chrome.exe",
            "Firefox" to "Mozilla Firefox/firefox.exe",
            "LibreWolf" to "LibreWolf/librewolf.exe",
            "Waterfox" to "Waterfox/waterfox.exe",
            "Zen" to "Zen Browser/zen.exe",
            "Floorp" to "Ablaze Floorp/floorp.exe",
        ).flatMap { (name, path) -> roots.map { name to File(it, path) } }
    }

    /** The program Windows opens https links with. */
    private fun defaultBrowser(): File? {
        for (key in listOf("UserChoiceLatest", "UserChoice")) {
            val progId = registryText(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\$key", "ProgId")
                ?: continue
            registryText(WinReg.HKEY_CLASSES_ROOT, "$progId\\shell\\open\\command")?.let(::exeOf)?.let { return it }
        }
        return null
    }

    private fun registryText(root: WinReg.HKEY, key: String, value: String = ""): String? =
        runCatching { Advapi32Util.registryGetValue(root, key, value) as? String }.getOrNull()

    /** The program in a registered command line: `"C:\...\x.exe" --flags "%1"`. */
    private fun exeOf(command: String): File? {
        val text = runCatching { Kernel32Util.expandEnvironmentStrings(command.trim()) }.getOrDefault(command.trim())
        val path = if (text.startsWith("\"")) text.drop(1).substringBefore('"') else Regex("""^(.*?\.exe)""", RegexOption.IGNORE_CASE).find(text)?.value
        return path?.let(::File)?.takeIf { it.isFile }
    }

    private val FirefoxPrograms = setOf("firefox.exe", "librewolf.exe", "waterfox.exe", "zen.exe", "floorp.exe", "mullvadbrowser.exe")
    private val ChromiumPrograms = setOf("chrome.exe", "msedge.exe", "brave.exe", "vivaldi.exe", "opera.exe", "chromium.exe", "thorium.exe")

    /** Firefox ships omni.ja beside its program; Chromium browsers ship .pak files, there or in a version folder. */
    private fun engineOf(exe: File): Engine? {
        val name = exe.name.lowercase()
        val dir = exe.parentFile ?: return null
        val here = dir.list()?.map { it.lowercase() }.orEmpty()
        if (name in FirefoxPrograms || "omni.ja" in here) return Engine.Firefox
        if (name in ChromiumPrograms) return Engine.Chromium
        val below = dir.listFiles { f -> f.isDirectory }.orEmpty().flatMap { sub -> sub.list()?.map { it.lowercase() }.orEmpty() }
        return if ((here + below).any { it.endsWith(".pak") }) Engine.Chromium else null
    }

    /**
     * Opens the window, waits for you to sign in and close it (up to fifteen minutes), then reads
     * the account from the profile it leaves.
     */
    suspend fun run(): Session {
        val browser = chosen() ?: throw NoBrowserException()
        return signIn(browser, StartUrl, requireAccount = true)
    }

    /**
     * The whole sign-in with [browser], opening [startUrl]. The browser check passes a guest page
     * and [closeAfterMs], to run every step but the Google account itself.
     */
    internal suspend fun signIn(browser: Browser, startUrl: String, requireAccount: Boolean, closeAfterMs: Long? = null): Session = withContext(Dispatchers.IO) {
        if (browser.engine == Engine.Safari) return@withContext signInSafari(startUrl, requireAccount, closeAfterMs)
        val profile = File(AppDirs.cache, "sign-in-browser")
        stopAll(profile)
        profile.deleteRecursively()
        profile.mkdirs()
        try {
            Log.i(Tag, "Opening ${browser.name} (${File(browser.path).name}, ${browser.engine}) for Google sign-in")
            val shown = Window(browser, profile, open(browser, profile, visible = true, startUrl))
            window = shown
            try {
                if (closeAfterMs != null) {
                    delay(closeAfterMs)
                    finish()
                }
                awaitClosed(shown)
            } finally {
                window = null
                shown.stop()
            }
            when (browser.engine) {
                Engine.Chromium -> try {
                    readOverDevTools(browser, profile, requireAccount)
                } catch (e: Exception) {
                    if (e is ClosedException) throw e
                    currentCoroutineContext().ensureActive()
                    Log.w(Tag, "${browser.name} didn't answer on its debugging port (${e.message}); reading its cookie file instead")
                    stopAll(profile)
                    readChromiumDatabase(profile, requireAccount)
                }
                Engine.Firefox -> readFirefoxProfile(profile, requireAccount)
                Engine.Safari -> error("Safari signs in on its own path")
            }
        } finally {
            withContext(NonCancellable) {
                stopAll(profile)
                // Browsers' helpers can hold files for a moment after the browser exits.
                repeat(10) {
                    if (profile.deleteRecursively()) return@withContext
                    delay(500)
                }
                Log.w(Tag, "Couldn't delete the sign-in profile at ${profile.path}")
            }
        }
    }

    /**
     * Closes the sign-in window the way its close button does, so the browser saves its cookies
     * on the way out; [run] then reads them.
     */
    fun finish() {
        window?.close()
        safariDone?.complete(Unit)
    }

    /** Waiting for "Done" while the user signs in in Safari, which Convx can't watch the way it watches its own window. */
    @Volatile private var safariDone: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /**
     * Safari has no debugging port and no profile of ours, so it's the user's own Safari: the page opens
     * there, they sign in, press Done, and Convx reads the Google cookies Safari saved.
     */
    private suspend fun signInSafari(startUrl: String, requireAccount: Boolean, closeAfterMs: Long?): Session {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        safariDone = done
        try {
            Log.i(Tag, "Opening Safari for Google sign-in")
            ProcessBuilder("open", "-a", "Safari", startUrl).start().waitFor()
            val finished = withTimeoutOrNull(TimeoutMs) {
                if (closeAfterMs != null) delay(closeAfterMs) else done.await()
                true
            }
            if (finished == null) throw IOException("Sign-in timed out after fifteen minutes. Try again.")
        } finally {
            safariDone = null
        }
        val cookies = readSafariCookies()
        val cookie = cookies.joinToString("; ") { (name, value) -> "$name=$value" }
        return session(cookies, if (cookies.isEmpty()) null else readConfigOverHttp(cookie), requireAccount)
    }

    /** Safari's cookie file (Cookies.binarycookies): big-endian page list, little-endian pages, strings ending in a zero byte. */
    private fun readSafariCookies(): List<Pair<String, String>> {
        val home = File(System.getProperty("user.home"))
        val file = listOf(
            "Library/Containers/com.apple.Safari/Data/Library/Cookies/Cookies.binarycookies",
            "Library/Cookies/Cookies.binarycookies",
        ).map { File(home, it) }.firstOrNull { it.exists() } ?: throw ClosedException()
        val data = try {
            file.readBytes()
        } catch (e: IOException) {
            throw IOException("Convx isn't allowed to read Safari's cookies. In System Settings, Privacy & Security, Full Disk Access, turn Convx on, then log in again.")
        }
        if (data.size < 8 || String(data, 0, 4, Charsets.US_ASCII) != "cook") throw IOException("Safari's cookie file isn't in a form Convx can read")
        val head = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN)
        val pages = head.getInt(4)
        var offset = 8 + 4 * pages
        val out = ArrayList<Pair<String, String>>()
        for (index in 0 until pages) {
            val size = head.getInt(8 + 4 * index)
            val page = java.nio.ByteBuffer.wrap(data.copyOfRange(offset, offset + size)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            offset += size
            fun text(at: Int): String {
                var end = at
                while (end < page.limit() && page.get(end) != 0.toByte()) end++
                return String(page.array(), at, end - at, Charsets.UTF_8)
            }
            val count = page.getInt(4)
            for (i in 0 until count) {
                val start = page.getInt(8 + 4 * i)
                val domain = text(start + page.getInt(start + 16))
                val name = text(start + page.getInt(start + 20))
                val path = text(start + page.getInt(start + 24))
                val value = text(start + page.getInt(start + 28))
                if (sentToMusic(domain, path)) out.add(name to value)
            }
        }
        return out
    }

    /** The sign-in browser: the process Convx started and whatever runs on the same profile. */
    private class Window(val browser: Browser, val profile: File, val started: Process) {
        /** Every process on the profile: the one started, if it still runs, and those naming the profile. */
        fun processes(): List<ProcessHandle> {
            val named = WinProcess.withCommandLine(profile.absolutePath)
            return (listOfNotNull(started.toHandle().takeIf { it.isAlive }) + named).distinctBy { it.pid() }
        }

        val isOpen: Boolean get() = started.isAlive || processes().isNotEmpty()

        fun close() = processes().forEach { closeWindows(it.pid()) }

        /** Closes it if it's still open, then ends what's left. */
        fun stop() {
            if (!isOpen) return
            close()
            val deadline = System.currentTimeMillis() + 8_000
            while (isOpen && System.currentTimeMillis() < deadline) Thread.sleep(250)
            if (isOpen) kill(processes())
        }
    }

    /** Ends anything still running on [profile] (a sign-in that was cancelled, or Convx closing mid-way). */
    private fun stopAll(profile: File) {
        val left = WinProcess.withCommandLine(profile.absolutePath)
        if (left.isEmpty()) return
        left.forEach { closeWindows(it.pid()) }
        val deadline = System.currentTimeMillis() + 5_000
        while (left.any { it.isAlive } && System.currentTimeMillis() < deadline) Thread.sleep(250)
        kill(left)
    }

    private fun kill(processes: List<ProcessHandle>) {
        processes.forEach { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    /** Asks every window of process [pid] to close, as its close button does. */
    private fun closeWindows(pid: Long) {
        if (!isWindows) {
            // A polite quit: the browser saves its cookies on the way out, as it does when its window is closed.
            ProcessHandle.of(pid).ifPresent { it.destroy() }
            return
        }
        runCatching {
            val user32 = com.sun.jna.platform.win32.User32.INSTANCE
            user32.EnumWindows({ hwnd, _ ->
                val owner = com.sun.jna.ptr.IntByReference()
                user32.GetWindowThreadProcessId(hwnd, owner)
                if (owner.value.toLong() == pid && user32.IsWindowVisible(hwnd)) {
                    user32.PostMessage(hwnd, com.sun.jna.platform.win32.WinUser.WM_CLOSE, null, null)
                }
                true
            }, null)
        }.onFailure { Log.w(Tag, "Couldn't close the sign-in window: ${it.message}") }
    }

    /**
     * Starts [browser] on [profile]: a small ordinary window, or with [visible] false a hidden one
     * that answers on a debugging port.
     */
    private fun open(browser: Browser, profile: File, visible: Boolean, vararg urls: String, headless: Boolean = true): Process {
        val args = when (browser.engine) {
            Engine.Chromium -> listOf(
                "--user-data-dir=${profile.path}",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-sync",
            ) + if (visible) {
                listOf("--window-size=520,780", "--new-window")
            } else if (headless) {
                listOf("--headless=new", "--remote-debugging-port=0", "--mute-audio")
            } else {
                // For a browser without a headless mode: a real window, parked off the screen.
                listOf("--remote-debugging-port=0", "--mute-audio", "--window-position=-32000,-32000", "--window-size=400,300")
            }
            Engine.Safari -> emptyList()
            Engine.Firefox -> {
                writeFirefoxPrefs(profile)
                listOf("-no-remote", "-new-instance", "-profile", profile.path, "-width", "520", "-height", "780")
            }
        }
        return ProcessBuilder(listOf(browser.path) + args + urls)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    /** A new Firefox profile goes straight to the page: no welcome tour, no default-browser question. */
    private fun writeFirefoxPrefs(profile: File) {
        val prefs = mapOf(
            "browser.shell.checkDefaultBrowser" to "false",
            "browser.aboutwelcome.enabled" to "false",
            "browser.startup.homepage_override.mstone" to "\"ignore\"",
            "startup.homepage_welcome_url" to "\"\"",
            "startup.homepage_welcome_url.additional" to "\"\"",
            "datareporting.policy.dataSubmissionEnabled" to "false",
            "toolkit.telemetry.reportingpolicy.firstRun" to "false",
            "browser.sessionstore.resume_from_crash" to "false",
            "privacy.sanitize.sanitizeOnShutdown" to "false",
            "privacy.clearOnShutdown.cookies" to "false",
            "privacy.clearOnShutdown_v2.cookiesAndStorage" to "false",
        )
        File(profile, "user.js").writeText(prefs.entries.joinToString("") { (key, value) -> "user_pref(\"$key\", $value);\n" })
    }

    /** Waits for the window to close; nothing looks inside it meanwhile. */
    private suspend fun awaitClosed(window: Window) {
        val deadline = System.currentTimeMillis() + TimeoutMs
        // A launcher hands over to the browser and exits; give the browser a moment to show up.
        delay(2_000)
        while (window.isOpen) {
            if (System.currentTimeMillis() > deadline) throw IOException("Sign-in timed out after fifteen minutes. Try again.")
            delay(1_000)
        }
    }

    /**
     * The profile again, out of sight, now that sign-in is over: YouTube Music's page for its
     * `ytcfg` values, and the cookies the browser kept for it. A browser without a headless mode
     * gets a window off the screen instead.
     */
    private suspend fun readOverDevTools(browser: Browser, profile: File, requireAccount: Boolean): Session =
        try {
            readOverDevTools(browser, profile, requireAccount, headless = true)
        } catch (e: DevToolsUnavailable) {
            Log.w(Tag, "${browser.name} didn't open headless (${e.message}); trying a hidden window")
            stopAll(profile)
            readOverDevTools(browser, profile, requireAccount, headless = false)
        }

    private class DevToolsUnavailable(message: String) : IOException(message)

    private suspend fun readOverDevTools(browser: Browser, profile: File, requireAccount: Boolean, headless: Boolean): Session {
        File(profile, "DevToolsActivePort").delete()
        val process = open(browser, profile, visible = false, "about:blank", headless = headless)
        val window = Window(browser, profile, process)
        var devTools: DevTools? = null
        try {
            val cdp = DevTools(awaitEndpoint(profile, window))
            devTools = cdp
            // Headless says so in its user agent; YouTube Music gets the browser's ordinary one.
            val agent = cdp.send("Browser.getVersion")["userAgent"]?.jsonPrimitive?.contentOrNull?.replace("HeadlessChrome", "Chrome")
            val targetId = cdp.send("Target.createTarget", buildJsonObject { put("url", "about:blank") })["targetId"]
                ?.jsonPrimitive?.contentOrNull ?: throw IOException("The browser didn't open YouTube Music")
            val session = cdp.send("Target.attachToTarget", buildJsonObject { put("targetId", targetId); put("flatten", true) })["sessionId"]
                ?.jsonPrimitive?.contentOrNull ?: throw IOException("The browser didn't open YouTube Music")
            if (agent != null) cdp.send("Emulation.setUserAgentOverride", buildJsonObject { put("userAgent", agent) }, session)
            cdp.send("Page.navigate", buildJsonObject { put("url", "https://$MusicHost/") }, session)
            val config = readConfig(cdp, session)
            val cookies = cdp.send("Storage.getCookies")["cookies"].asObjects()
                .filter { sentToMusic(it.text("domain"), it.text("path")) }
                .map { it.text("name").orEmpty() to it.text("value").orEmpty() }
            return session(cookies, config, requireAccount)
        } finally {
            withContext(NonCancellable) {
                devTools?.let { cdp ->
                    withTimeoutOrNull(3_000) { runCatching { cdp.send("Browser.close") } }
                    cdp.close()
                }
                window.stop()
            }
        }
    }

    /** Firefox's cookie database, copied first so a lingering lock or journal can't get in the way. */
    private fun readFirefoxProfile(profile: File, requireAccount: Boolean): Session {
        val db = File(profile, "cookies.sqlite")
        if (!db.isFile) throw if (requireAccount) ClosedException() else IOException("Firefox left no cookies")
        val copy = Files.createTempDirectory("convx-cookies").toFile()
        try {
            for (suffix in listOf("", "-wal", "-shm")) {
                File(profile, "cookies.sqlite$suffix").takeIf { it.isFile }?.copyTo(File(copy, "cookies.sqlite$suffix"))
            }
            Class.forName("org.sqlite.JDBC")
            val cookies = DriverManager.getConnection("jdbc:sqlite:${File(copy, "cookies.sqlite").absolutePath}").use { connection ->
                // A cookie partitioned under another site (originAttributes set) isn't sent to YouTube Music itself.
                val query = runCatching { connection.prepareStatement("SELECT name, value, host, path FROM moz_cookies WHERE originAttributes = ''").executeQuery() }
                    .getOrElse { connection.prepareStatement("SELECT name, value, host, path FROM moz_cookies").executeQuery() }
                buildList {
                    query.use { rows ->
                        while (rows.next()) {
                            if (sentToMusic(rows.getString("host"), rows.getString("path"))) add(rows.getString("name") to rows.getString("value"))
                        }
                    }
                }
            }
            val cookie = cookies.joinToString("; ") { (name, value) -> "$name=$value" }
            return session(cookies, if (cookies.isEmpty()) null else readConfigOverHttp(cookie), requireAccount)
        } finally {
            copy.deleteRecursively()
        }
    }

    /**
     * The last resort for a Chromium browser that won't answer on a debugging port: its cookie
     * file, decrypted with the key Windows keeps for this account. A browser that ties its cookies
     * to itself (Chrome's app-bound "v20" cookies) can't be read this way, only over the port.
     */
    private fun readChromiumDatabase(profile: File, requireAccount: Boolean): Session {
        val state = File(profile, "Local State").takeIf { it.isFile }?.readText() ?: throw IOException("The browser left no profile to read")
        val wrapped = Json.parseToJsonElement(state).jsonObject["os_crypt"]?.jsonObject?.get("encrypted_key")?.jsonPrimitive?.contentOrNull
            ?: throw IOException("The browser's cookies have no key Convx can read")
        val raw = Base64.getDecoder().decode(wrapped)
        if (raw.size <= 5 || String(raw, 0, 5, Charsets.US_ASCII) != "DPAPI") throw IOException("The browser's cookies have no key Convx can read")
        val key = SecretKeySpec(Crypt32Util.cryptUnprotectData(raw.copyOfRange(5, raw.size)), "AES")
        val db = listOf("Default/Network/Cookies", "Network/Cookies", "Default/Cookies", "Cookies").map { File(profile, it) }.firstOrNull { it.isFile }
            ?: throw if (requireAccount) ClosedException() else IOException("The browser left no cookies")
        val copy = Files.createTempDirectory("convx-cookies").toFile()
        try {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                File(db.parentFile, db.name + suffix).takeIf { it.isFile }?.copyTo(File(copy, "Cookies$suffix"))
            }
            Class.forName("org.sqlite.JDBC")
            val cookies = DriverManager.getConnection("jdbc:sqlite:${File(copy, "Cookies").absolutePath}").use { connection ->
                // From version 24 the decrypted value starts with a hash of the cookie's domain.
                val version = runCatching {
                    connection.prepareStatement("SELECT value FROM meta WHERE key = 'version'").executeQuery().use { if (it.next()) it.getString(1).toInt() else 0 }
                }.getOrDefault(0)
                buildList {
                    connection.prepareStatement("SELECT host_key, name, value, encrypted_value, path FROM cookies").executeQuery().use { rows ->
                        while (rows.next()) {
                            if (!sentToMusic(rows.getString("host_key"), rows.getString("path"))) continue
                            val plain = rows.getString("value").orEmpty()
                            val value = plain.ifEmpty { null } ?: decryptCookie(rows.getBytes("encrypted_value"), key, version)
                            if (value != null) add(rows.getString("name") to value)
                        }
                    }
                }
            }
            val cookie = cookies.joinToString("; ") { (name, value) -> "$name=$value" }
            return session(cookies, if (cookies.isEmpty()) null else readConfigOverHttp(cookie), requireAccount)
        } finally {
            copy.deleteRecursively()
        }
    }

    /** A "v10"/"v11" cookie: AES-256-GCM, a 12-byte nonce after the prefix. Anything else is null. */
    private fun decryptCookie(data: ByteArray?, key: SecretKeySpec, version: Int): String? {
        if (data == null || data.size < 3 + 12 + 16) return null
        val prefix = String(data, 0, 3, Charsets.US_ASCII)
        if (prefix != "v10" && prefix != "v11") return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 3, 12))
            val plain = cipher.doFinal(data, 15, data.size - 15)
            String(if (version >= 24 && plain.size >= 32) plain.copyOfRange(32, plain.size) else plain, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun session(cookies: List<Pair<String, String>>, config: Pair<String, String?>?, requireAccount: Boolean): Session {
        if (requireAccount && cookies.none { it.first in AccountCookies }) throw ClosedException()
        val cookie = cookies.joinToString("; ") { (name, value) -> "$name=$value" }
        Log.i(Tag, "Read ${cookies.size} cookies, visitor data ${if (config != null) "found" else "missing"}, data sync id ${if (config?.second != null) "found" else "missing"}")
        return Session(cookie, config?.first, config?.second)
    }

    /** VISITOR_DATA and DATASYNC_ID from YouTube Music's own page, fetched with the cookies just read. */
    private fun readConfigOverHttp(cookie: String): Pair<String, String?>? = runCatching {
        val request = Request.Builder()
            .url("https://$MusicHost/")
            .header("Cookie", cookie)
            .header("User-Agent", Http.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        Http.client.newCall(request).execute().use { response ->
            val page = response.body.string()
            fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(page)?.groupValues?.get(1)
                ?.let { runCatching { Json.parseToJsonElement("\"$it\"").jsonPrimitive.content }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            val visitor = field("VISITOR_DATA") ?: return@use null
            percentDecode(visitor) to field("DATASYNC_ID")?.let(::percentDecode)
        }
    }.onFailure { Log.w(Tag, "Couldn't read YouTube Music's page after sign-in: ${it.message}") }.getOrNull()

    /** The browser writes its debugging port and path into the profile once it's listening. */
    private suspend fun awaitEndpoint(profile: File, window: Window): String {
        val file = File(profile, "DevToolsActivePort")
        val started = System.currentTimeMillis()
        var checked = started
        while (System.currentTimeMillis() < started + 20_000) {
            val lines = runCatching { file.readLines() }.getOrNull()
            if (lines != null && lines.size >= 2 && lines[0].toIntOrNull() != null) {
                return "ws://127.0.0.1:${lines[0].trim()}${lines[1].trim()}"
            }
            // Past a launcher's hand-over, a browser that's gone isn't coming back.
            if (System.currentTimeMillis() - checked > 1_000 && System.currentTimeMillis() - started > 3_000) {
                checked = System.currentTimeMillis()
                if (!window.isOpen) throw DevToolsUnavailable("it closed before Convx could read your sign-in")
            }
            delay(200)
        }
        throw DevToolsUnavailable("it didn't start for Convx to read your sign-in")
    }

    /** The page's `ytcfg` VISITOR_DATA and DATASYNC_ID, as the phone's JavaScript bridge reads them. */
    private suspend fun readConfig(cdp: DevTools, session: String): Pair<String, String?>? {
        val expression = "JSON.stringify((window.yt && window.yt.config_) ? " +
            "[window.yt.config_.VISITOR_DATA || null, window.yt.config_.DATASYNC_ID || null] : [null, null])"
        repeat(40) {
            val result = runCatching {
                cdp.send(
                    "Runtime.evaluate",
                    buildJsonObject { put("expression", expression); put("returnByValue", true) },
                    session,
                )
            }.getOrNull()
            val value = result?.get("result")?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
            val pair = value?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }
            val visitor = pair?.getOrNull(0)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            if (visitor != null) {
                val dataSync = pair.getOrNull(1)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
                // Keep DATASYNC_ID's "||" suffix: it tells channels on one account apart.
                return percentDecode(visitor) to dataSync?.let { percentDecode(it) }
            }
            delay(500)
        }
        return null
    }

    /** Whether a cookie for [domain] and [path] goes with a request to https://music.youtube.com/. */
    private fun sentToMusic(domain: String?, path: String?): Boolean {
        domain ?: return false
        val domainMatches = if (domain.startsWith(".")) MusicHost == domain.drop(1) || MusicHost.endsWith(domain) else MusicHost == domain
        return domainMatches && (path.isNullOrEmpty() || path == "/")
    }

    /** Android's Uri.decode: %XX only, a "+" stays a "+". */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 <= text.lastIndex) {
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement?.asObjects(): List<JsonObject> =
        (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** A minimal Chrome DevTools Protocol client: numbered commands over one WebSocket. */
    private class DevTools(url: String) : AutoCloseable {
        private val client = Http.client.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        private val ids = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val opened = CompletableDeferred<Unit>()

        private val socket: WebSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    val id = message["id"]?.jsonPrimitive?.intOrNull ?: return
                    pending.remove(id)?.complete(message)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t)

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(IOException("The browser closed the connection"))
            },
        )

        private fun fail(error: Throwable) {
            opened.completeExceptionally(error)
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }

        suspend fun send(method: String, params: JsonObject = JsonObject(emptyMap()), sessionId: String? = null): JsonObject {
            withTimeout(10_000) { opened.await() }
            val id = ids.getAndIncrement()
            val reply = CompletableDeferred<JsonObject>()
            pending[id] = reply
            val message = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
                if (sessionId != null) put("sessionId", sessionId)
            }
            if (!socket.send(message.toString())) {
                pending.remove(id)
                throw IOException("The browser closed the connection")
            }
            val answer = try {
                withTimeout(15_000) { reply.await() }
            } finally {
                pending.remove(id)
            }
            answer["error"]?.jsonObject?.let { error ->
                throw IOException(error["message"]?.jsonPrimitive?.contentOrNull ?: "DevTools refused $method")
            }
            return answer["result"]?.jsonObject ?: JsonObject(emptyMap())
        }

        override fun close() {
            socket.close(1000, null)
        }
    }
}
