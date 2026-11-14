/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.convx.desktop.integrations.party.Party
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.theme.ConvxType
import kotlin.math.abs

/** The phone's Tempo and Pitch, from the player's menu. */
fun tempoAndPitch() {
    Overlays.showDialog { TempoPitchDialog() }
}

/** The phone's steps: 0.25x to 2x by 0.05, and a semitone at a time an octave either way. */
private val Tempos = (0..35).map { (25 + it * 5) / 100f }
private val Semitones = (-12..12).toList()

@Composable
private fun TempoPitchDialog() {
    val state by PlayerController.state.collectAsState()
    val party by Party.session.collectAsState()
    DialogCard("Tempo and pitch") {
        // In a listening party everyone hears the song at the same speed, so only the pitch is yours.
        if (party == null) {
            val index = remember(state.tempo) { Tempos.indices.minBy { abs(Tempos[it] - state.tempo) } }
            Adjuster(
                icon = "speed",
                label = "x${Tempos[index]}",
                canLower = index > 0,
                canRaise = index < Tempos.lastIndex,
                onLower = { PlayerController.setTempoPitch(Tempos[index - 1], state.transpose) },
                onRaise = { PlayerController.setTempoPitch(Tempos[index + 1], state.transpose) },
            )
            Spacer(Modifier.height(12.dp))
        }
        Adjuster(
            icon = "sliders",
            label = if (state.transpose > 0) "+${state.transpose}" else "${state.transpose}",
            canLower = state.transpose > Semitones.first(),
            canRaise = state.transpose < Semitones.last(),
            onLower = { PlayerController.setTempoPitch(state.tempo, state.transpose - 1) },
            onRaise = { PlayerController.setTempoPitch(state.tempo, state.transpose + 1) },
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            PillButton("Reset", { PlayerController.setTempoPitch(1f, 0) })
            PillButton("OK", { Overlays.dismissDialog() }, filled = true)
        }
    }
}

@Composable
private fun Adjuster(
    icon: String,
    label: String,
    canLower: Boolean,
    canRaise: Boolean,
    onLower: () -> Unit,
    onRaise: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ConvxIcon(icon, tint = Color.White, size = 28.dp)
        IconButton("remove", onLower, size = 40.dp, iconSize = 20.dp, enabled = canLower)
        Text(label, style = ConvxType.Title3, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.width(80.dp))
        IconButton("add", onRaise, size = 40.dp, iconSize = 20.dp, enabled = canRaise)
    }
}
