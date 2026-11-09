/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package android.util

/**
 * Android's Log for the shared module engine, written to Convx's own log. Debug lines (a
 * module's console.log among them) are kept only when Convx runs with -Dconvx.debug.
 */
object Log {
    @JvmStatic
    fun d(tag: String, message: String): Int {
        if (timber.log.Timber.verbose) com.convx.desktop.core.Log.i(tag, message)
        return 0
    }

    @JvmStatic
    fun i(tag: String, message: String): Int {
        com.convx.desktop.core.Log.i(tag, message)
        return 0
    }

    @JvmStatic
    fun w(tag: String, message: String): Int {
        com.convx.desktop.core.Log.w(tag, message)
        return 0
    }

    @JvmStatic
    fun w(tag: String, message: String, error: Throwable?): Int {
        com.convx.desktop.core.Log.w(tag, message, error)
        return 0
    }

    @JvmStatic
    fun e(tag: String, message: String): Int {
        com.convx.desktop.core.Log.e(tag, message)
        return 0
    }

    @JvmStatic
    fun e(tag: String, message: String, error: Throwable?): Int {
        com.convx.desktop.core.Log.e(tag, message, error)
        return 0
    }
}
