/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.glass.of
import com.convx.desktop.ui.glass.LocalGlassConfig
import com.convx.desktop.ui.glass.GlassConfig
import com.convx.desktop.data.AppSettings
import com.convx.desktop.lyrics.LyricsService
import com.convx.desktop.integrations.party.Party
import com.convx.desktop.player.PlayerController
import kotlinx.coroutines.launch
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.LocalCardWidth
import com.convx.desktop.ui.components.LocalPageInsets
import com.convx.desktop.ui.components.LocalPlayback
import com.convx.desktop.ui.components.OverlayHost
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.screens.replay.ReplayOverlay
import com.convx.desktop.ui.components.PageInsets
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.Playback
import com.convx.desktop.ui.components.cardWidth
import com.convx.desktop.ui.glass.LocalAppBackdrop
import com.convx.desktop.ui.glass.LocalPagesBackdrop
import com.convx.desktop.ui.glass.LocalMenuBackdrop
import com.convx.desktop.ui.player.FullPlayer
import com.convx.desktop.ui.player.NowPlayingPanelWidth
import com.convx.desktop.ui.player.NowPlayingPill
import com.convx.desktop.ui.player.RightPanel
import com.convx.desktop.ui.player.NowPlayingPillHeight
import com.convx.desktop.ui.player.NowPlayingPillWidthFraction
import com.convx.desktop.ui.screens.ScreenHost
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxTheme
import com.convx.desktop.ui.theme.ConvxType
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Below this window width the side bar folds to its rail of icons on its own. */
private val AutoCollapseWidth = 1100.dp

/** Space the pages leave under the bottom pill: the pill and its margins. */
private val BottomChromeInset = NowPlayingPillHeight + 24.dp

/** Space the pages leave beside the Now Playing panel: the panel, its margin and a gap. */
private val RightPanelInset = NowPlayingPanelWidth + SideBarMargin + 8.dp

/** The narrowest the pages get for the Now Playing panel; below it the panel stays away. */
private val MinPageWidthBesidePanel = 560.dp

/**
 * The window: the top bar across the top, the side bar on the left, the Now Playing view on the
 * right and the player at the bottom, built from the phone's pieces. The pages fill the whole
 * window on true black and the chrome floats over them as liquid glass that refracts them.
 */
@Composable
fun AppShell(navigator: Navigator) {
    val settings by AppSettings.state.collectAsState()
    val player by PlayerController.state.collectAsState()
    val playback = remember(player.current?.id, player.isPlaying, player.isLoading) {
        Playback(player.current?.id, player.isPlaying, player.isLoading)
    }

    // Playback problems surface as a toast, then clear.
    LaunchedEffect(player.error) {
        val error = player.error ?: return@LaunchedEffect
        Overlays.toast(error, "error")
        PlayerController.dismissError()
    }

    // Why a control did nothing (a party's host has the controls), and what a party wants said.
    LaunchedEffect(Unit) {
        launch { PlayerController.notices.collect { Overlays.toast(it, "lock") } }
        // The phone's queue conflict: ask before a play clears the queue, or offer Undo after.
        launch { PlayerController.conflicts.collect { queueConflictDialog(it) } }
        launch {
            PlayerController.replaced.collect { previous ->
                Overlays.toast("Queue replaced", "queue_music", action = "Undo") { PlayerController.restoreQueue(previous) }
            }
        }
        Party.notices.collect { Overlays.toast(it, "group") }
    }

    // Look the lyrics up as a song starts, and the next one's too, so they are there on open.
    LaunchedEffect(player.current?.id) {
        player.current?.let(LyricsService::prefetch)
        player.queue.getOrNull(player.index + 1)?.let(LyricsService::prefetch)
    }

    // Everything the glass refracts: the pages over the phone's base fill.
    val appBackdrop = rememberLayerBackdrop(
        onDraw = remember {
            {
                drawRect(Color.Black)
                drawContent()
            }
        },
    )

    // Convx red on black, always: the app keeps its identity whatever is playing.
    ConvxTheme(ConvxColors.AccentRed) {
        val glass = remember(settings.glass) { GlassConfig.of(settings.glass) }
        CompositionLocalProvider(
            LocalNavigator provides navigator,
            LocalPlayback provides playback,
            LocalMenuBackdrop provides appBackdrop,
            LocalGlassConfig provides glass,
            LocalCardWidth provides settings.cardWidth(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val collapsed = settings.sidebarCollapsed || maxWidth < AutoCollapseWidth
                // A spring overshoots past its target; an inset below zero would be negative padding.
                val startInsetRaw by animateDpAsState(
                    if (collapsed) SideBarCollapsedContentInset else SideBarContentInset,
                    spring(0.9f, 400f),
                )
                val startInset = startInsetRaw.coerceAtLeast(0.dp)
                val showPill = player.current != null
                // Spotify's Now Playing view: on the right while a song is loaded and the pages keep room.
                val panel = SidePanel.of(settings.rightPanel)
                val startTarget = if (collapsed) SideBarCollapsedContentInset else SideBarContentInset
                val panelFits = maxWidth - startTarget - RightPanelInset >= MinPageWidthBesidePanel
                LaunchedEffect(panelFits) { UiState.rightPanelFits = panelFits }
                val showPanel = panel != null && player.current != null && panelFits
                // It keeps showing what it showed while it slides away.
                var shownPanel by remember { mutableStateOf(panel ?: SidePanel.NowPlaying) }
                if (panel != null) shownPanel = panel
                val endInsetRaw by animateDpAsState(if (showPanel) RightPanelInset else 0.dp, spring(0.9f, 400f))
                val endInset = endInsetRaw.coerceAtLeast(0.dp)
                val insets = PageInsets(
                    start = startInset,
                    top = TopBarHeight,
                    bottom = if (showPill) BottomChromeInset else 0.dp,
                    end = endInset,
                )

                // The pages, recorded as the backdrop every glass surface samples.
                Box(Modifier.fillMaxSize().layerBackdrop(appBackdrop)) {
                    CompositionLocalProvider(LocalPageInsets provides insets) {
                        Pages(navigator)
                    }
                }

                // The chrome samples the recording, so it sits outside it.
                CompositionLocalProvider(LocalAppBackdrop provides appBackdrop) {
                    SideBar(
                        collapsed = collapsed,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = SideBarMargin, top = TopBarHeight, bottom = SideBarMargin),
                    )

                    AnimatedVisibility(
                        visible = showPanel,
                        enter = slideInHorizontally(spring(0.9f, 400f)) { it / 3 } + fadeIn(tween(220)),
                        exit = slideOutHorizontally(spring(1f, 500f)) { it / 3 } + fadeOut(tween(160)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = SideBarMargin, top = TopBarHeight, bottom = SideBarMargin),
                    ) {
                        RightPanel(shownPanel)
                    }

                    TopBar(Modifier.align(Alignment.TopCenter))

                    // Centred in the content between the side panels, not in the window.
                    BoxWithConstraints(Modifier.fillMaxSize().padding(start = startInset, end = endInset)) {
                        // Beside the Now Playing panel the content is narrow; the pill keeps room for the song.
                        val pillWidth = maxOf(maxWidth * NowPlayingPillWidthFraction, minOf(maxWidth - 24.dp, 640.dp)).coerceAtMost(1040.dp)
                        AnimatedVisibility(
                            visible = showPill,
                            // The phone's pill: in on a bouncy spring, out flat.
                            enter = slideInVertically(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) { it } + fadeIn(),
                            exit = slideOutVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)) { it / 2 } + fadeOut(),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        ) {
                            NowPlayingPill(Modifier.width(pillWidth))
                        }
                    }
                }

                AnimatedVisibility(
                    visible = UiState.fullPlayer,
                    enter = slideInVertically { it / 6 } + fadeIn(tween(220)) + scaleIn(initialScale = 0.98f),
                    exit = slideOutVertically { it / 6 } + fadeOut(tween(180)) + scaleOut(targetScale = 0.98f),
                ) {
                    // Menus over the player would refract the pages hidden under it. Its top edge
                    // is the window's title bar while it covers the window.
                    CompositionLocalProvider(LocalMenuBackdrop provides null, LocalPagesBackdrop provides appBackdrop) {
                        Box(Modifier.fillMaxSize().windowDragArea()) { FullPlayer() }
                    }
                }

                com.convx.desktop.ui.player.AmbientOverlay()

                ReplayOverlay()

                // The phone's DIY editor, over everything but toasts and dialogs.
                CompositionLocalProvider(LocalMenuBackdrop provides null) { com.convx.desktop.ui.player.DiyEditorOverlay() }

                // Disc Studio's editor, also over everything but toasts and dialogs.
                CompositionLocalProvider(LocalMenuBackdrop provides null) { com.convx.desktop.ui.studio.DiscEditorOverlay() }

                OverlayHost()
            }
        }
    }
}

@Composable
private fun Pages(navigator: Navigator) {
    val holder = rememberSaveableStateHolder()
    // Drop saved scroll positions for history entries that can't be reached any more.
    LaunchedEffect(navigator) {
        snapshotFlow { navigator.discarded.toList() }.collect { keys ->
            if (keys.isNotEmpty()) {
                keys.forEach { holder.removeState(it) }
                navigator.discarded.removeAll(keys)
            }
        }
    }
    AnimatedContent(
        targetState = navigator.current,
        transitionSpec = { fadeIn(tween(200, delayMillis = 40)) togetherWith fadeOut(tween(120)) },
        contentKey = { it.key },
        modifier = Modifier.fillMaxSize(),
    ) { entry ->
        holder.SaveableStateProvider(entry.key) {
            ScreenHost(entry.screen)
        }
    }
}

private fun queueConflictDialog(conflict: PlayerController.QueueConflict) {
    Overlays.showDialog {
        DialogCard("Something is already playing") {
            Text(
                "Keep the current queue and add this to the end, or clear it and play now?",
                style = ConvxType.Body,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Keep queue", {
                    Overlays.dismissDialog()
                    conflict.keep()
                })
                PillButton("Clear and play", {
                    Overlays.dismissDialog()
                    conflict.replace()
                }, filled = true)
            }
        }
    }
}
