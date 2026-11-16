/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.shell.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.Toolkit
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * Keeps what Convx holds close to what it needs.
 *
 * Java grows its heap for a burst of work (a page loading, a song being measured for AutoMix) and
 * hands it back only after a quiet minute without a single collection, which never comes while a
 * song plays and something on screen moves. So the heap is checked here instead: once the burst is
 * over and far more is held than used, a collection is asked for, and that is when Java shrinks it.
 * The same collection lets go of the pixels of covers the cover cache has dropped, which live
 * outside the heap and are only freed once Java notices nobody holds them.
 *
 * A minimised Convx also drops the covers it kept for scrolling back and lets Windows take its
 * memory for other apps; the pages it needs to keep playing come straight back as they are touched.
 */
object Memory {
    private const val Tag = "Memory"
    private const val Mb = 1024L * 1024

    /** How often the heap is looked at. */
    private const val CheckEveryMs = 10_000L

    /** A burst is over once nobody has scrolled, clicked or typed for this long. */
    private const val QuietMs = 6_000L

    /** The least Java holds on to, and the share of its heap it keeps free after a collection (the build's flags). */
    private const val HeapFloor = 64 * Mb
    private const val FreeShareKept = 0.30

    /** Held memory a collection would hand back that is worth asking for one. */
    private const val SpareWorthTrimming = 48 * Mb

    /** The wait between two trims, doubled each time one gives nothing back. */
    private const val TrimGapMs = 20_000L
    private const val LongestTrimGapMs = 10 * 60_000L

    /** How long the window stays minimised before Convx gives up what it only needs on screen. */
    private const val MinimisedForMs = 20_000L
    private const val CoversKeptMinimised = 4 * Mb

    /** The PC's memory, which sizes the cover cache; 8 GB where Java can't tell. */
    val physicalBytes: Long by lazy {
        runCatching { (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize }
            .getOrNull()?.takeIf { it > 0 } ?: (8L * 1024 * Mb)
    }

    @Volatile private var lastInputNanos = System.nanoTime()

    /** Someone scrolled, moved, clicked or typed: Convx is in use, so nothing is trimmed under them. */
    private fun onInput() {
        lastInputNanos = System.nanoTime()
    }

    private val collections = Channel<Unit>(Channel.CONFLATED)

    /** Asks for a collection shortly; many asks close together make one. */
    fun collectSoon() {
        collections.trySend(Unit)
    }

    fun start() {
        runCatching {
            val events = AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.KEY_EVENT_MASK
            Toolkit.getDefaultToolkit().addAWTEventListener({ onInput() }, events)
        }
        AppScope.launch(Dispatchers.IO) {
            for (ask in collections) {
                delay(400)
                System.gc()
                // Covers dropped while this one ran wait for the next, a little later.
                delay(3_000)
            }
        }
        AppScope.launch(Dispatchers.IO) { watch() }
    }

    private suspend fun watch() {
        val memory = ManagementFactory.getMemoryMXBean()
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP && !it.name.contains("Eden") }
        var gap = TrimGapMs
        var lastTrim = 0L
        var minimisedSince = 0L
        var gaveBack = false
        while (true) {
            delay(CheckEveryMs)
            val now = System.currentTimeMillis()

            if (UiState.windowShown) {
                minimisedSince = 0L
                gaveBack = false
            } else {
                if (minimisedSince == 0L) minimisedSince = now
                if (!gaveBack && now - minimisedSince >= MinimisedForMs) {
                    gaveBack = true
                    giveBack()
                    lastTrim = System.currentTimeMillis()
                    continue
                }
            }

            val quiet = (System.nanoTime() - lastInputNanos) / 1_000_000 >= QuietMs
            if (!quiet || now - lastTrim < gap) continue
            val heap = memory.heapMemoryUsage
            // What the heap would come down to: what was still alive at the last collection, plus the share Java keeps free.
            val alive = pools.sumOf { it.collectionUsage?.used ?: 0L }.coerceAtMost(heap.used)
            val wouldHold = maxOf(HeapFloor, (alive / (1 - FreeShareKept)).toLong())
            if (heap.committed - wouldHold < SpareWorthTrimming) continue
            System.gc()
            lastTrim = System.currentTimeMillis()
            val after = memory.heapMemoryUsage.committed
            // The heap can take a second collection to shrink; one that keeps giving nothing is left alone for longer.
            gap = if (heap.committed - after >= 16 * Mb) TrimGapMs else (gap * 2).coerceAtMost(LongestTrimGapMs)
            Log.i(Tag, "Java heap trimmed from ${heap.committed / Mb} to ${after / Mb} MB (${memory.heapMemoryUsage.used / Mb} MB in use)")
        }
    }

    /** Minimised for a while: what only the screen needs goes, and Windows may have the rest for other apps. */
    private suspend fun giveBack() {
        val before = WinProcess.memory()?.first
        ImageCache.trimMemory(CoversKeptMinimised)
        runCatching { org.jetbrains.skia.Graphics.purgeResourceCache() }
        System.gc()
        // The collection's own clean-up frees the covers' pixels a moment after it ends.
        delay(1_500)
        WinProcess.releaseWorkingSet()
        val after = WinProcess.memory()?.first
        if (before != null && after != null) Log.i(Tag, "Minimised: memory in use went from ${before / Mb} to ${after / Mb} MB")
    }
}
