/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import com.sun.jna.Library
import com.sun.jna.Native
import java.awt.EventQueue
import java.awt.Frame
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * One Convx at a time, as Spotify does. Two would each keep their own copy of your library, and
 * the one closed last would write its copy over what you changed in the other, so a playlist you
 * deleted there would be back. Opening Convx again brings up the window that's already open.
 */
object SingleInstance {
    private const val Tag = "SingleInstance"
    private const val Show = "show"
    private const val Shown = "ok"

    private val lockFile get() = File(AppDirs.data, "instance.lock")
    private val portFile get() = File(AppDirs.data, "instance.port")

    // Held until Convx exits; Windows lets go of the lock with the process.
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** The window to bring up when Convx is opened again. */
    @Volatile
    var window: Frame? = null

    /**
     * True when this is the only Convx. False once the Convx already open has been asked to show
     * itself, and this one should quit. A Convx that's still closing (as during an update) is
     * waited for.
     */
    fun claim(): Boolean {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            when (tryLock()) {
                true -> {
                    listen()
                    return true
                }
                null -> return true
                false -> Unit
            }
            if (signal()) return false
            if (System.currentTimeMillis() > deadline) {
                Log.w(Tag, "Another Convx holds the lock and doesn't answer; opening anyway")
                return true
            }
            Thread.sleep(300)
        }
    }

    /** True with the lock, false while another Convx has it, null when it can't be taken at all. */
    private fun tryLock(): Boolean? = try {
        val opened = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val taken = runCatching { opened.tryLock() }.getOrNull()
        if (taken == null) {
            opened.close()
            false
        } else {
            channel = opened
            lock = taken
            true
        }
    } catch (error: Exception) {
        // A folder that can't be locked mustn't keep Convx from opening.
        Log.w(Tag, "Couldn't take the instance lock", error)
        null
    }

    /** Waits, on a background thread, for a second launch asking this one to show itself. */
    private fun listen() {
        runCatching {
            val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            portFile.writeText(server.localPort.toString())
            Thread({
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { client ->
                            client.soTimeout = 2_000
                            if (client.getInputStream().bufferedReader().readLine() == Show) {
                                bringToFront()
                                client.getOutputStream().apply { write("$Shown\n".toByteArray()); flush() }
                            }
                        }
                    }
                }
            }, "convx-instance").apply { isDaemon = true }.start()
        }.onFailure { Log.w(Tag, "Couldn't listen for Convx being opened again", it) }
    }

    /** Asks the Convx that's open to show itself; true when it says it did. */
    private fun signal(): Boolean = runCatching {
        val port = portFile.readText().trim().toInt()
        // This launch has the foreground; let the open Convx take it.
        user32?.AllowSetForegroundWindow(AsfwAny)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().apply { write("$Show\n".toByteArray()); flush() }
            socket.getInputStream().bufferedReader().readLine() == Shown
        }
    }.getOrDefault(false)

    private fun bringToFront() = EventQueue.invokeLater {
        val frame = window ?: return@invokeLater
        if (frame.extendedState and Frame.ICONIFIED != 0) frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
    }

    private const val AsfwAny = -1

    private interface User32 : Library {
        fun AllowSetForegroundWindow(processId: Int): Boolean
    }

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").lowercase().contains("win")) null
        else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }
}
