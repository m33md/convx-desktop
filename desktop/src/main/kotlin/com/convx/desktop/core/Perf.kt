/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.core

import androidx.compose.ui.awt.ComposeWindow
import com.convx.desktop.ui.components.ImageCache
import com.convx.desktop.ui.shell.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Container
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * What Convx costs while it runs, written to the log now and then: CPU, memory and how many frames
 * the window drew, so "Convx is heavy" can be answered with numbers from the PC it happened on.
 */
object Perf {
    private const val Tag = "Perf"
    private val frames = AtomicLong()
    @Volatile private var renderApi = "unknown"

    /** Counts the frames [window] draws and starts the log lines. */
    fun watch(window: ComposeWindow) {
        renderApi = runCatching { window.renderApi.name }.getOrDefault("unknown")
        val layers = layersIn(window)
        if (layers.isEmpty()) Log.w(Tag, "Couldn't find the window's drawing surface; frames won't be counted")
        layers.forEach { layer ->
            val inner = layer.renderDelegate ?: return@forEach
            layer.renderDelegate = object : SkikoRenderDelegate {
                override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                    frames.incrementAndGet()
                    inner.onRender(canvas, width, height, nanoTime)
                }
            }
        }
        AppScope.launch(Dispatchers.IO) {
            // A minute in, five, a quarter of an hour, then every half hour; CONVX_PERF_EVERY (seconds) sets a fixed beat.
            val fixed = System.getenv("CONVX_PERF_EVERY")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)
            val waits = if (fixed != null) generateSequence { fixed } else sequenceOf(60_000L, 240_000L, 600_000L) + generateSequence { 30 * 60_000L }
            var last = sample()
            for (wait in waits) {
                delay(wait)
                val now = sample()
                Log.i(Tag, describe(last, now))
                last = now
            }
        }
    }

    private class Sample(val at: Long, val cpuNanos: Long, val frames: Long)

    private fun sample() = Sample(
        System.nanoTime(),
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.processCpuTime ?: -1L,
        frames.get(),
    )

    private fun describe(from: Sample, to: Sample): String {
        val seconds = ((to.at - from.at) / 1e9).coerceAtLeast(0.001)
        val cores = Runtime.getRuntime().availableProcessors()
        val cpu = if (from.cpuNanos >= 0 && to.cpuNanos >= 0) (to.cpuNanos - from.cpuNanos) / 1e9 / seconds / cores * 100 else -1.0
        val fps = (to.frames - from.frames) / seconds
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val nonHeap = ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage
        val process = WinProcess.memory()
        val mb = { bytes: Long -> bytes / 1_048_576 }
        val state = when {
            !UiState.windowShown -> "minimised"
            !UiState.windowFocused -> "in the background"
            else -> "in front"
        }
        return buildString {
            append("Last ${seconds.toLong()} s: CPU ${"%.1f".format(cpu)}% of $cores threads, ${"%.1f".format(fps)} frames/s ($renderApi), window $state. ")
            append("Memory: ")
            if (process != null) append("${mb(process.first)} MB in use, ${mb(process.second)} MB private; ")
            append("Java heap ${mb(heap.used)} of ${mb(heap.committed)} MB, other Java ${mb(nonHeap.committed)} MB, ")
            append("covers ${mb(ImageCache.memoryBytes())} MB")
        }
    }

    private fun layersIn(container: Container): List<SkiaLayer> = container.components.flatMap { child ->
        when (child) {
            is SkiaLayer -> listOf(child)
            is Container -> layersIn(child)
            else -> emptyList()
        }
    }
}
