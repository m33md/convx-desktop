/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.shapes.ContinuousCapsule
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent

/** iOS's segmented control: a grey track with a lighter thumb that slides to the choice. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(ContinuousCapsule)
            .background(Color(0x3D767680))
            .padding(2.dp),
    ) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(segment * selected, spring(dampingRatio = 0.78f, stiffness = 520f))
        Box(
            Modifier
                .offset(x = x)
                .width(segment)
                .fillMaxHeight()
                .shadow(3.dp, ContinuousCapsule)
                .clip(ContinuousCapsule)
                .background(Color(0xFF636366)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(ContinuousCapsule)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = if (i == selected) ConvxType.SubheadStrong else ConvxType.Subhead, color = ConvxColors.Label)
                }
            }
        }
    }
}

/**
 * The phone's segmented buttons, which its equalizer lays presets out in: an outlined capsule
 * split into equal parts, the chosen ones filled and ticked.
 */
@Composable
fun SegmentedButtons(
    labels: List<String>,
    isSelected: (Int) -> Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (Int) -> Unit,
) {
    val accent = LocalAccent.current
    val outline = Color.White.copy(alpha = 0.22f)
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(ContinuousCapsule)
            .border(1.dp, outline, ContinuousCapsule)
            .then(if (enabled) Modifier else Modifier.alpha(0.4f)),
    ) {
        labels.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(outline))
            val selected = isSelected(i)
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        when {
                            selected -> accent.copy(alpha = 0.28f)
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(interaction, enabled)
                    .then(
                        if (enabled) {
                            Modifier.pointerHoverIcon(PointerIcon.Hand)
                                .clickable(interactionSource = interaction, indication = null) { onClick(i) }
                        } else Modifier,
                    )
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    ConvxIcon("check", tint = ConvxColors.Label, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    label,
                    style = ConvxType.CaptionStrong,
                    color = ConvxColors.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A row that's one of a list's choices, ticked in the accent when it's the one. */
@Composable
fun CheckRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    icon: String? = null,
    tint: Color = SystemColors.Gray,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsRow(title, subtitle, icon, tint, enabled = enabled, onClick = onClick) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            if (checked) ConvxIcon("check", tint = LocalAccent.current, size = 18.dp)
        }
    }
}
