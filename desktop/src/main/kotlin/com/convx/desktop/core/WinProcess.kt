/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/** What Windows tells about processes that Java's ProcessHandle doesn't: memory use and command lines. */
internal object WinProcess {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** PROCESS_MEMORY_COUNTERS_EX, field by field (64-bit sizes; Convx ships for x64 only). */
    @Structure.FieldOrder(
        "cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
        "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage", "PrivateUsage",
    )
    class MemoryCounters : Structure() {
        @JvmField var cb: Int = 0
        @JvmField var PageFaultCount: Int = 0
        @JvmField var PeakWorkingSetSize: Long = 0
        @JvmField var WorkingSetSize: Long = 0
        @JvmField var QuotaPeakPagedPoolUsage: Long = 0
        @JvmField var QuotaPagedPoolUsage: Long = 0
        @JvmField var QuotaPeakNonPagedPoolUsage: Long = 0
        @JvmField var QuotaNonPagedPoolUsage: Long = 0
        @JvmField var PagefileUsage: Long = 0
        @JvmField var PeakPagefileUsage: Long = 0
        @JvmField var PrivateUsage: Long = 0
    }

    private interface Psapi : StdCallLibrary {
        fun GetProcessMemoryInfo(process: WinNT.HANDLE, counters: MemoryCounters, size: Int): Boolean
    }

    private interface NtDll : StdCallLibrary {
        fun NtQueryInformationProcess(process: WinNT.HANDLE, infoClass: Int, info: Pointer, length: Int, returned: IntByReference): Int
    }

    private val psapi: Psapi? by lazy { if (isWindows) runCatching { Native.load("psapi", Psapi::class.java) }.getOrNull() else null }
    private val ntdll: NtDll? by lazy { if (isWindows) runCatching { Native.load("ntdll", NtDll::class.java) }.getOrNull() else null }

    /** This process's working set and private bytes, or null off Windows. */
    fun memory(): Pair<Long, Long>? {
        val api = psapi ?: return null
        return runCatching {
            val counters = MemoryCounters()
            counters.cb = counters.size()
            if (!api.GetProcessMemoryInfo(Kernel32.INSTANCE.GetCurrentProcess(), counters, counters.size())) return null
            counters.WorkingSetSize to counters.PrivateUsage
        }.getOrNull()
    }

    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private const val ProcessCommandLineInformation = 60
    private const val STATUS_INFO_LENGTH_MISMATCH = 0xC0000004.toInt()

    /**
     * The command line process [pid] was started with (Windows 8.1 and later), or null when Windows
     * won't say: the process is gone, protected, or another user's.
     */
    fun commandLine(pid: Long): String? {
        val api = ntdll ?: return null
        val handle = Kernel32.INSTANCE.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return null
        try {
            var size = 4096
            repeat(3) {
                val buffer = Memory(size.toLong())
                val needed = IntByReference()
                val status = api.NtQueryInformationProcess(handle, ProcessCommandLineInformation, buffer, size, needed)
                if (status == STATUS_INFO_LENGTH_MISMATCH && needed.value > size) {
                    size = needed.value
                    return@repeat
                }
                if (status != 0) return null
                // A UNICODE_STRING (length in bytes, then a pointer) followed by the text it points at.
                val length = buffer.getShort(0).toInt() and 0xFFFF
                val text = buffer.getPointer(8) ?: return null
                return String(text.getCharArray(0, length / 2))
            }
            return null
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    /** Running processes whose command line contains [needle], ignoring case. */
    fun withCommandLine(needle: String): List<ProcessHandle> {
        if (!isWindows) return emptyList()
        val wanted = needle.lowercase()
        return ProcessHandle.allProcesses().filter { process ->
            commandLine(process.pid())?.lowercase()?.contains(wanted) == true
        }.toList()
    }
}
