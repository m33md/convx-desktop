/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.Log
import java.awt.Toolkit

/**
 * Linux desktops match a window to its menu entry by the window's class (WM_CLASS). Java names
 * it after the main class, so the dock would show a nameless window without Convx's icon. This
 * gives every window the name of the entry the .deb, .rpm and AppImage install.
 */
object LinuxDesktop {
    /** The menu entry's name without ".desktop": jpackage's "<package>-<launcher>". */
    const val WmClass = "convx-Convx"

    val isLinux: Boolean = System.getProperty("os.name").lowercase().contains("linux")

    /** Call before the first window opens. Needs `--add-opens java.desktop/sun.awt.X11`, which the Linux packages pass. */
    fun nameWindows() {
        if (!isLinux) return
        runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
            toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(null, WmClass)
        }.onFailure { Log.w("LinuxDesktop", "Couldn't name the window for the menu entry: ${it.message}") }
    }
}
