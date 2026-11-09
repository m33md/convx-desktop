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

/** The next frame's time in ms; while the window isn't focused, no more than [UnfocusedFps] a second. */
suspend fun awaitPacedFrameMillis(): Long {
    if (!UiState.windowFocused) delay(1000L / UnfocusedFps)
    return withFrameMillis { it }
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
