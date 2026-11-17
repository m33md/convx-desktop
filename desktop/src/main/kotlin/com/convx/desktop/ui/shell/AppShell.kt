/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import com.convx.desktop.ui.components.LocalPageWidth
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
import com.convx.desktop.ui.theme.ConvxMotion
import com.convx.desktop.ui.theme.ConvxTheme
import com.convx.desktop.ui.theme.ConvxType
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.convx.desktop.ui.glass.GlassMotion
import kotlin.math.abs

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
    // The recording is what shows on screen too (the window is black under it), so the pages are
    // drawn once a frame rather than once for the screen and again for the glass.
    val appBackdrop = rememberLayerBackdrop(
        onDraw = remember {
            {
                drawRect(Color.Black)
                drawContent()
            }
        },
        showRecording = true,
    )
    LaunchedEffect(GlassMotion.fast) { if (GlassMotion.fast) GlassMotion.settle() }

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
            BoxWithConstraints(Modifier.fillMaxSize().then(if (IsMacShell) Modifier else Modifier.background(Color.Black))) {
                val windowWidth = maxWidth
                val collapsed = settings.sidebarCollapsed || maxWidth < AutoCollapseWidth
                // A spring turned round mid-way can carry past its target; an inset below zero would be negative padding.
                val startInsetRaw by animateDpAsState(
                    if (collapsed) SideBarCollapsedContentInset else SideBarContentInset,
                    ConvxMotion.appear(),
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
                val endInsetRaw by animateDpAsState(if (showPanel) RightPanelInset else 0.dp, ConvxMotion.appear())
                val endInset = endInsetRaw.coerceAtLeast(0.dp)
                val insets = PageInsets(
                    start = startInset,
                    top = TopBarHeight,
                    bottom = if (showPill) BottomChromeInset else 0.dp,
                    end = endInset,
                )

                // macOS: the window is see-through and the side panel is the system's material, so only
                // the pages get black behind them; the glass controls over them still refract the recorded pages.
                if (IsMacShell) {
                    val playerOpen = UiState.fullPlayer
                    SideEffect {
                        com.convx.desktop.integrations.MacGlass.setWindowHeight(maxHeight.value.toDouble())
                        com.convx.desktop.integrations.MacGlass.setHidden(playerOpen)
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            // Flush against the side panel and the window's edges, as a Mac app's content is.
                            .padding(start = (startInset - 8.dp).coerceAtLeast(0.dp))
                            .background(Color.Black),
                    )
                }

                // The pages, recorded as the backdrop every glass surface samples.
                Box(
                    Modifier
                        .fillMaxSize()
                        .layerBackdrop(appBackdrop)
                        // How fast the pages scroll, for the glass over them; the scroll itself goes on to the page.
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type != PointerEventType.Scroll) continue
                                    val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                                    GlassMotion.onScroll(abs(delta.x) + abs(delta.y))
                                }
                            }
                        },
                ) {
                    CompositionLocalProvider(LocalPageInsets provides insets, LocalPageWidth provides windowWidth - insets.start - insets.end) {
                        Pages(navigator, blackFrom = if (IsMacShell) (startInset - 8.dp).coerceAtLeast(0.dp) else 0.dp)
                    }
                }

                // The chrome samples the recording, so it sits outside it.
                CompositionLocalProvider(LocalAppBackdrop provides appBackdrop) {
                    SideBar(
                        collapsed = collapsed,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(
                                start = SideBarMargin,
                                top = if (com.convx.desktop.ui.glass.IsMac && !collapsed) SideBarMargin else TopBarHeight,
                                bottom = SideBarMargin,
                            ),
                    )

                    AnimatedVisibility(
                        visible = showPanel,
                        enter = slideInHorizontally(ConvxMotion.appear()) { it / 3 } + fadeIn(ConvxMotion.fade()),
                        exit = slideOutHorizontally(ConvxMotion.appear()) { it / 3 } + fadeOut(ConvxMotion.fade()),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = SideBarMargin, top = TopBarHeight, bottom = SideBarMargin),
                    ) {
                        RightPanel(shownPanel)
                    }

                    TopBar(
                        Modifier
                            .align(Alignment.TopCenter)
                            // On a Mac the window buttons and the side panel take the left; the bar sits beside them.
                            .padding(start = if (com.convx.desktop.ui.glass.IsMac) maxOf(startInset, 84.dp) else 0.dp),
                    )

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

                // A queued song's cover on its way into the bar.
                com.convx.desktop.ui.player.QueueFlightLayer()

                AnimatedVisibility(
                    visible = UiState.fullPlayer,
                    // The phone's player sheet: up from the bottom edge and back down into the bar.
                    enter = slideInVertically(ConvxMotion.playerSheet()) { it },
                    exit = slideOutVertically(ConvxMotion.playerSheet()) { it },
                ) {
                    // Menus over the player would refract the pages hidden under it. Its top edge
                    // is the window's title bar while it covers the window.
                    CompositionLocalProvider(LocalMenuBackdrop provides null, LocalPagesBackdrop provides appBackdrop) {
                        Box(Modifier.fillMaxSize().windowDragArea()) { FullPlayer() }
                    }
                }

                // The bar's cover growing into the player's, and back.
                com.convx.desktop.ui.player.CoverMorphLayer()

                com.convx.desktop.ui.player.AmbientOverlay()

                ReplayOverlay()

                // The phone's DIY editor, over everything but toasts and dialogs.
                CompositionLocalProvider(LocalMenuBackdrop provides null) { com.convx.desktop.ui.player.DiyEditorOverlay() }

                // Disc Studio's editor, also over everything but toasts and dialogs.
                CompositionLocalProvider(LocalMenuBackdrop provides null) { com.convx.desktop.ui.studio.DiscEditorOverlay() }

                // Notices rise out from behind the bar. With the player or a full-window view over
                // the pages there's no bar to hide behind and no page to frost, so they rise in place on dark glass.
                val pagesCovered = UiState.fullPlayer || com.convx.desktop.ui.player.Ambient.on ||
                    com.convx.desktop.ui.screens.replay.Replay.open ||
                    com.convx.desktop.ui.player.DiyEditor.isOpen || com.convx.desktop.ui.studio.DiscEditor.isOpen
                OverlayHost(
                    noticeLine = 12.dp + NowPlayingPillHeight,
                    clipNotices = showPill && !pagesCovered,
                    noticeStart = if (pagesCovered) 0.dp else startInset,
                    noticeEnd = if (pagesCovered) 0.dp else endInset,
                    glassNotices = !pagesCovered,
                )
            }
        }
    }
}

private val IsMacShell = com.convx.desktop.integrations.MacWindow.isMac

/** The pages, each on black from [blackFrom] rightwards so one can slide or fade in over another. */
@Composable
private fun Pages(navigator: Navigator, blackFrom: Dp) {
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
        transitionSpec = { pageTransition(initialState, targetState, navigator.backStack.size) },
        contentKey = { it.key },
        modifier = Modifier.fillMaxSize(),
    ) { entry ->
        Box(
            Modifier.fillMaxSize().drawBehind {
                val left = blackFrom.toPx()
                drawRect(Color.Black, Offset(left, 0f), Size(size.width - left, size.height))
            },
        ) {
            holder.SaveableStateProvider(entry.key) {
                // Each opening of a page paces its own cascade of covers.
                CompositionLocalProvider(com.convx.desktop.ui.components.LocalPageReveal provides remember { com.convx.desktop.ui.components.PageReveal() }) {
                    ScreenHost(entry.screen)
                }
            }
        }
    }
}

/**
 * The phone's page motion (its NavigationBuilder): an album or a playlist slides in from the right
 * over the page it was opened from, which holds still, and slides back out on the way back; an
 * artist slides in while it fades; every other page fades in over the one before, which dims.
 * Going back plays each in reverse, with the page being left on top.
 */
private fun pageTransition(from: Entry, to: Entry, depth: Int): ContentTransform {
    val back = to.key < from.key
    // The page whose kind sets the motion: the one opened, or on the way back the one being left.
    val moving = if (back) from.screen else to.screen
    val enterFade = tween<Float>(ConvxMotion.PageEnterMillis)
    val exitFade = tween<Float>(ConvxMotion.PageExitMillis)
    val transform = when {
        moving.isCollection() ->
            if (back) fadeIn(enterFade, initialAlpha = 0.999f) togetherWith slideOutHorizontally(ConvxMotion.collectionPush()) { it }
            else slideInHorizontally(ConvxMotion.collectionPush()) { it } togetherWith fadeOut(exitFade, targetAlpha = 0.999f)
        moving is Screen.Artist -> {
            val slideIn = tween<IntOffset>(ConvxMotion.PageEnterMillis, easing = FastOutSlowInEasing)
            val slideOut = tween<IntOffset>(ConvxMotion.PageExitMillis, easing = FastOutSlowInEasing)
            if (back) fadeIn(enterFade, initialAlpha = 0.7f) togetherWith slideOutHorizontally(slideOut) { it / 4 } + fadeOut(tween(ConvxMotion.PageExitMillis, easing = FastOutSlowInEasing))
            else slideInHorizontally(slideIn) { it } + fadeIn(tween(ConvxMotion.PageEnterMillis, easing = FastOutSlowInEasing)) togetherWith fadeOut(exitFade, targetAlpha = 0.7f)
        }
        back -> fadeIn(enterFade, initialAlpha = 0.7f) togetherWith fadeOut(ConvxMotion.appearExit())
        else -> fadeIn(tween(ConvxMotion.PushMillis)) togetherWith fadeOut(exitFade, targetAlpha = 0.7f)
    }
    // Deeper pages draw over shallower ones, so the page opened is on top going in and the page left is on top going back.
    transform.targetContentZIndex = depth.toFloat()
    return transform
}

/** An album or a playlist of any kind: the pages the phone slides in whole. */
private fun Screen.isCollection(): Boolean = when (this) {
    is Screen.Album, is Screen.Playlist, is Screen.LocalPlaylist, is Screen.SpotifyPlaylist,
    Screen.Liked, Screen.Downloads, Screen.Cached, Screen.Uploaded, Screen.MyTop, Screen.SpotifyLiked,
    -> true
    else -> false
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
