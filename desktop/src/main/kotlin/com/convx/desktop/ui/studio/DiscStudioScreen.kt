/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import com.convx.desktop.ui.components.rememberLoopingPhase
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.DiscArt
import com.convx.desktop.data.DiscDesign
import com.convx.desktop.data.DiscDesigns
import com.convx.desktop.data.DiscFormat
import com.convx.desktop.data.DiscPresets
import com.convx.desktop.data.Library
import com.convx.desktop.data.LocalPlaylist
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.MenuDivider
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.screens.PageList
import com.convx.desktop.ui.screens.settings.pickFile
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(14.dp)
private val HeroShape = RoundedCornerShape(24.dp)
private val CardMinWidth = 168.dp
private val CardGap = 16.dp

/** How big the page's cards are drawn, in pixels: sharp up to about 240dp wide. */
private const val CardPixels = DiscThumbs.CardPixels

/** The Disc Studio section: your designs, the presets to start from, and new or shared ones. */
@Composable
fun DiscStudioScreen() {
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DiscDesigns.load() } }
    val designs by DiscDesigns.all.collectAsState()
    val library by Library.state.collectAsState()

    PageList(state = rememberLazyListState(), tint = Color.Black) {
        item(key = "hero") { StudioHero(designs.firstOrNull()) }
        item(key = "yours-header") {
            SectionHeader(
                "Your designs",
                Modifier.padding(start = Gutter, end = Gutter, top = 28.dp, bottom = 14.dp),
                trailing = if (designs.isEmpty()) null else ({
                    Text(
                        if (designs.size == 1) "1 design" else "${designs.size} designs",
                        style = ConvxType.Subhead,
                        color = ConvxColors.SecondaryLabel,
                    )
                }),
            )
        }
        item(key = "yours") {
            if (designs.isEmpty()) {
                EmptyDesigns()
            } else {
                CardGrid(designs) { design, width ->
                    DesignCard(design, library.localPlaylists.filter { it.design == design.id }, library.localPlaylists, width)
                }
            }
        }
        item(key = "presets-header") {
            SectionHeader(
                "Start from a preset",
                Modifier.padding(start = Gutter, end = Gutter, top = 32.dp, bottom = 14.dp),
            )
        }
        item(key = "presets") {
            CardGrid(DiscPresets.ordered) { preset, width -> PresetCard(preset, width) }
        }
        item(key = "tip") {
            Text(
                "Tip: right-click any of your playlists and pick Design disc to make one with its name on it.",
                style = ConvxType.Footnote,
                color = ConvxColors.TertiaryLabel,
                modifier = Modifier.padding(start = Gutter, end = Gutter, top = 28.dp, bottom = 32.dp),
            )
        }
    }
}

/** Picks a .convxdisc file and adds it to your designs. */
internal fun importDesignFile() {
    val file = pickFile("Open a disc design", listOf(DiscDesigns.Extension)) ?: return
    AppScope.launch(Dispatchers.Main) {
        withContext(Dispatchers.IO) { runCatching { DiscDesigns.import(file) } }
            .onSuccess { design ->
                Overlays.toast("Added \"${design.name}\" to your designs", action = "Open") { DiscEditor.open(design) }
            }
            .onFailure { Overlays.toast(it.message ?: "Couldn't open that file.", "error") }
    }
}

/** Puts [design] on a playlist from the studio page, without opening the editor. */
private fun applyDesign(design: DiscDesign, playlist: LocalPlaylist, open: (String) -> Unit) {
    AppScope.launch(Dispatchers.Main) {
        val done = withContext(Dispatchers.IO) { DiscDesigns.applyTo(design, playlist.id) }
        if (done) {
            Overlays.toast("\"${design.name}\" is now the cover of ${playlist.name}", action = "View") { open(playlist.id) }
        } else {
            Overlays.toast("Couldn't put it on ${playlist.name}", "error")
        }
    }
}

// ---- Hero ------------------------------------------------------------------------------------

@Composable
private fun StudioHero(latest: DiscDesign?) {
    val accent = LocalAccent.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp)) {
        val showDiscs = maxWidth > 640.dp
        Box(
            Modifier
                .fillMaxWidth()
                .clip(HeroShape)
                .background(
                    Brush.linearGradient(
                        0f to accent.copy(alpha = 0.34f),
                        0.55f to Color(0xFF1A1A1E),
                        1f to Color(0xFF0C0C0E),
                    ),
                )
                .border(0.5.dp, Color.White.copy(alpha = 0.10f), HeroShape),
        ) {
            if (showDiscs) {
                HeroDiscs(latest, Modifier.align(Alignment.CenterEnd).padding(end = 28.dp))
            }
            Column(
                Modifier
                    .padding(start = 32.dp, top = 30.dp, bottom = 30.dp, end = if (showDiscs) 300.dp else 32.dp)
                    .widthIn(max = 560.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConvxIcon("disc_studio", tint = accent, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("DISC STUDIO", style = ConvxType.CaptionStrong, color = Color.White.copy(alpha = 0.75f))
                }
                Spacer(Modifier.height(10.dp))
                Text("Make a disc for every playlist", style = ConvxType.LargeTitle.copy(fontSize = 30.sp, lineHeight = 36.sp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Design a CD or a record with your own pictures, words that curve around the disc, colours, " +
                        "patterns and stickers. Put it on a playlist as its cover, or share it as a file anyone with Convx can open.",
                    style = ConvxType.Body,
                    color = ConvxColors.SecondaryLabel,
                )
                Spacer(Modifier.height(20.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("New CD", { DiscEditor.openPreset(DiscPresets.of("blank-cd") ?: DiscPresets.all.first()) }, icon = "disc_studio", accent = accent)
                    PillButton("New vinyl", { DiscEditor.openPreset(DiscPresets.of("blank-vinyl") ?: DiscPresets.all.first()) }, icon = "disc_studio", filled = true)
                    PillButton("Open a design file", ::importDesignFile, icon = "download")
                }
            }
        }
    }
}

/** Your newest design (or a preset) spinning in front of a record. */
@Composable
private fun HeroDiscs(latest: DiscDesign?, modifier: Modifier) {
    val front = remember(latest?.id, latest?.updatedAt) {
        latest ?: DiscPresets.of("sticker-bomb")?.let { DiscPresets.start(it) }
    }
    // Behind it, the other kind of disc: a record behind a CD, a CD behind a record.
    val backKey = if (front?.format == DiscFormat.VINYL) "y2k" else "midnight-vinyl"
    val back = remember(backKey) { DiscPresets.of(backKey)?.let { DiscPresets.start(it, "Disc Studio") } }
    val frontImage = discImage(front, 440, "hero:${front?.id}:${front?.updatedAt}")
    val backImage = discImage(back, 400, "hero:$backKey")
    // They turn while the mouse is on them and rest otherwise, so the page costs nothing while you read it.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val phase by rememberLoopingPhase(14_000, hovered)
    val turn = phase * 360f
    Box(modifier.size(width = 260.dp, height = 200.dp).hoverable(interaction)) {
        if (backImage != null) {
            Image(
                backImage,
                null,
                Modifier.size(170.dp).align(Alignment.CenterEnd).graphicsLayer { rotationZ = turn * 0.8f },
                contentScale = ContentScale.Fit,
            )
        }
        if (frontImage != null) {
            Image(
                frontImage,
                null,
                Modifier.size(184.dp).align(Alignment.CenterStart).offset(x = 6.dp).graphicsLayer { rotationZ = turn },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** A bare disc drawn once off the main thread and kept under [key]. */
@Composable
private fun discImage(design: DiscDesign?, size: Int, key: String): ImageBitmap? =
    produceState(DiscThumbs.peek(key), key) {
        if (design == null || value != null) return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { DiscArt.renderDisc(design, DiscDesigns.Resources, size).toComposeImageBitmap() }.getOrNull()
        }?.also { DiscThumbs.keep(key, it) }
    }.value

// ---- Grids and cards -------------------------------------------------------------------------

/** As many columns as fit at [CardMinWidth], sharing the width, as Radio lays out its stations. */
@Composable
private fun <T> CardGrid(items: List<T>, card: @Composable (T, Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Gutter)) {
        val columns = ((maxWidth + CardGap) / (CardMinWidth + CardGap)).toInt().coerceAtLeast(1)
        val width = (maxWidth - CardGap * (columns - 1)) / columns - 0.5.dp
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CardGap), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items.forEach { card(it, width) }
        }
    }
}

/** A cover drawn off the main thread, sized for the page. */
@Composable
private fun CoverImage(key: String, design: () -> DiscDesign, modifier: Modifier) {
    val image = produceState(DiscThumbs.peek(key), key) { value = DiscThumbs.cover(key, design, CardPixels) }.value
    Box(modifier.background(Color(0xFF070708))) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun DesignCard(design: DiscDesign, wornBy: List<LocalPlaylist>, playlists: List<LocalPlaylist>, width: Dp) {
    val navigator = LocalNavigator.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    val useMenu = rememberMenuAnchor()
    val openMenu = menu.openAtProvider()
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .hoverable(interaction)
                .onSecondaryClick(openMenu)
                .pressable({ DiscEditor.open(design) }, interaction = interaction, pressScale = 0.98f)
                .clip(CardShape),
        ) {
            CoverImage("${DiscThumbs.designKey(design)}@$CardPixels", { design }, Modifier.fillMaxSize())
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                IconButton(
                    "more_horiz",
                    { menu.offset = androidx.compose.ui.unit.DpOffset(width - 40.dp, 44.dp); menu.expanded = true },
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    size = 32.dp,
                    iconSize = 18.dp,
                    filled = true,
                    tooltip = "More",
                )
                PillButton(
                    "Edit",
                    { DiscEditor.open(design) },
                    Modifier.align(Alignment.BottomStart).padding(10.dp),
                    icon = "edit",
                    filled = true,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            AppMenu(menu) {
                MenuItem("Edit", "edit") {
                    menu.expanded = false
                    DiscEditor.open(design)
                }
                MenuItem("Use on a playlist", "playlist_add") {
                    menu.expanded = false
                    useMenu.offset = menu.offset
                    useMenu.expanded = true
                }
                MenuItem("Duplicate", "content_copy") {
                    menu.expanded = false
                    AppScope.launch(Dispatchers.IO) { runCatching { DiscDesigns.duplicate(design) } }
                }
                MenuDivider()
                MenuItem("Share as a file", "ios_share") {
                    menu.expanded = false
                    DiscEditor.export(design)
                }
                MenuItem("Save as picture", "image") {
                    menu.expanded = false
                    DiscEditor.exportImage(design, cover = true)
                }
                MenuDivider()
                MenuItem("Delete", "delete", destructive = true) {
                    menu.expanded = false
                    val note = if (wornBy.isEmpty()) "It can't be brought back." else "Playlists wearing it keep the cover it made."
                    Overlays.confirm("Delete \"${design.name}\"?", note, "Delete") {
                        AppScope.launch(Dispatchers.IO) { runCatching { DiscDesigns.delete(design.id) } }
                    }
                }
            }
            AppMenu(useMenu) {
                if (playlists.isEmpty()) MenuItem("No playlists yet", enabled = false) {}
                playlists.take(40).forEach { playlist ->
                    MenuItem(playlist.name, if (playlist.design == design.id) "check" else "queue_music") {
                        useMenu.expanded = false
                        applyDesign(design, playlist) { navigator.navigate(Screen.LocalPlaylist(it)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            design.name,
            style = ConvxType.Headline.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val kind = if (design.format == DiscFormat.CD) "CD" else "Vinyl"
        val where = when (wornBy.size) {
            0 -> null
            1 -> "on ${wornBy.first().name}"
            else -> "on ${wornBy.size} playlists"
        }
        Text(
            listOfNotNull(kind, where).joinToString(" · "),
            style = ConvxType.Subhead.copy(fontSize = 12.sp),
            color = ConvxColors.SecondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PresetCard(preset: DiscPresets.Preset, width: Dp) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .hoverable(interaction)
                .pressable({ DiscEditor.openPreset(preset) }, interaction = interaction, pressScale = 0.98f)
                .clip(CardShape),
        ) {
            CoverImage("${DiscThumbs.presetKey(preset)}@$CardPixels", { DiscPresets.start(preset) }, Modifier.fillMaxSize())
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                PillButton(
                    "Use this",
                    { DiscEditor.openPreset(preset) },
                    Modifier.align(Alignment.BottomStart).padding(10.dp),
                    icon = "add",
                    filled = true,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            preset.title,
            style = ConvxType.Headline.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyDesigns() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter)
            .clip(CardShape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), CardShape)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon("disc_studio", tint = ConvxColors.SecondaryLabel, size = 30.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Nothing here yet", style = ConvxType.Headline)
            Text(
                "Start with a new CD or vinyl above, or pick a preset below. What you save shows up here.",
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
            )
        }
    }
}
