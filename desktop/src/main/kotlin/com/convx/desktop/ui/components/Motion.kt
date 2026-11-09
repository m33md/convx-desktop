/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.shell.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/*
 * Pacing for the views that move on their own (the playhead, lyrics, the playing bars, the
 * player's backgrounds). Waiting for a frame makes the whole window draw again, glass and all, at
 * the monitor's refresh rate, so these run only while something moves and someone can see it:
 * never while paused or minimised, and at [UnfocusedFps] while another app has the focus.
 */

/** Frames a second for moving views while another app has the focus. */
const val UnfocusedFps = 30

/** Frames a second for moving views at most, on a monitor that refreshes faster than this. */
const val FocusedFps = 60

/**
 * The next frame's time in ms, no more than [fps] a second, and no more than [UnfocusedFps]
 * while another app has the focus. Paced views wait for the same beat, so they share frames.
 */
suspend fun awaitPacedFrameMillis(fps: Int = FocusedFps): Long {
    val cap = if (UiState.windowFocused) fps else minOf(fps, UnfocusedFps)
    // A 60 Hz monitor gives 60 a second by itself; waiting on a timer there would only add jitter.
    val waited = (cap < FocusedFps || FramePacing.fasterDisplay) && FramePacing.awaitBeat(cap)
    val frame = withFrameMillis { it }
    FramePacing.saw(frame, waited)
    return frame
}

private object FramePacing {
    /** Learned from frames that came back to back: the monitor refreshes faster than [FocusedFps]. */
    @Volatile
    var fasterDisplay = false
        private set

    private var lastUnpaced = 0L
    private var shortGaps = 0

    /** Waits for the next beat of a shared [fps] grid; false if it didn't need to. */
    suspend fun awaitBeat(fps: Int): Boolean {
        val beat = 1_000_000_000L / fps
        val now = System.nanoTime()
        val waitMs = ((now / beat + 1) * beat - now) / 1_000_000
        if (waitMs <= 0) return false
        delay(waitMs)
        return true
    }

    fun saw(frameMs: Long, waited: Boolean) {
        if (waited) return
        val gap = frameMs - lastUnpaced
        lastUnpaced = frameMs
        // A frame drawn at once (a resize, say) can come early, so it takes a few in a row.
        if (gap == 0L) return
        shortGaps = if (gap <= FasterThanMs) shortGaps + 1 else 0
        if (shortGaps >= 3) fasterDisplay = true
    }

    /** A frame this soon after the last means a monitor above 80 Hz or so. */
    private const val FasterThanMs = 12L
}

/** True while a song plays (not paused, not loading) and the window isn't minimised. */
fun playheadMoving(): Flow<Boolean> = combine(
    PlayerController.state.map { it.isPlaying && !it.isLoading },
    snapshotFlow { UiState.windowShown },
) { playing, shown -> playing && shown }.distinctUntilChanged()

/**
 * A 0..1 phase that goes round once every [periodMs] while [running] and holds where it stopped
 * otherwise. An infinite transition asks for every frame forever; this asks for none while held.
 */
@Composable
fun rememberLoopingPhase(periodMs: Int, running: Boolean): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val go = running && UiState.windowShown
    LaunchedEffect(go, periodMs) {
        if (!go) return@LaunchedEffect
        val from = phase.floatValue
        val start = awaitPacedFrameMillis()
        while (true) {
            val now = awaitPacedFrameMillis()
            phase.floatValue = (from + (now - start).toFloat() / periodMs) % 1f
        }
    }
    return phase
}

/** Up and back down over one [phase]: what a linear tween does on RepeatMode.Reverse, over its two legs. */
fun pingPong(phase: Float): Float = 1f - abs(phase * 2f - 1f)
