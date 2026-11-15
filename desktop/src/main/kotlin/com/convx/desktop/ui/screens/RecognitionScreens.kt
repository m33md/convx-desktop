/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.Library
import com.convx.desktop.data.Recognition
import com.convx.desktop.recognition.Recognizer
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Gutter
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.SectionHeader
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.LocalNavigator
import com.convx.desktop.ui.shell.Screen
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.convx.desktop.ui.theme.LocalAccent
import com.music.shazamkit.models.RecognitionResult
import com.music.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The phone's Recognize Music: listen, then what Shazam found, with a way to play it here. */
@Composable
fun RecognitionScreen() {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val status by Recognizer.status.collectAsState()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { Recognizer.recognize() }
    }
    fun cancel() {
        job?.cancel()
        Recognizer.reset()
    }
    LaunchedEffect(Unit) { Recognizer.reset() }
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            Recognizer.reset()
        }
    }

    PageList(state = rememberLazyListState(), tint = Color.Black) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recognize Music", style = ConvxType.LargeTitle, modifier = Modifier.weight(1f))
                PillButton("History", { navigator.navigate(Screen.RecognitionHistory) }, icon = "history")
            }
        }
        item(key = "body") {
            Box(Modifier.fillParentMaxHeight(0.78f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = status,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)).togetherWith(fadeOut() + scaleOut(targetScale = 0.92f)) },
                    contentKey = { it::class },
                ) { state ->
                    when (state) {
                        RecognitionStatus.Ready -> ReadyState(::start)
                        RecognitionStatus.Listening -> ListeningState(::cancel)
                        RecognitionStatus.Processing -> ProcessingState()
                        is RecognitionStatus.Success -> SuccessState(
                            state.result,
                            onPlay = { navigator.navigate(Screen.SearchResults("${state.result.title} ${state.result.artist}")) },
                            onTryAgain = ::start,
                            onClose = ::cancel,
                        )
                        is RecognitionStatus.NoMatch -> Problem("close", "No match found", state.message, ::start)
                        is RecognitionStatus.Error -> Problem("warning", "Recognition error", state.message, ::start)
                    }
                }
            }
        }
    }
}

/** The big round button, red as the phone's primary. */
@Composable
private fun MicDisc(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Box(
        modifier.size(160.dp).clip(CircleShape).background(accent).pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        ConvxIcon("mic", tint = Color.White, size = 64.dp)
    }
}

@Composable
private fun ReadyState(onStart: () -> Unit) {
    val accent = LocalAccent.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(
            Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.3f), accent.copy(alpha = 0.1f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            MicDisc(onStart)
        }
        Text("Click to recognize", style = ConvxType.Headline, color = Color.White)
        InputPicker()
    }
}

/** A PC has more than one input; this picks the one to listen with (Stereo Mix, where Windows offers it, hears the PC itself). */
@Composable
private fun InputPicker() {
    val settings by AppSettings.state.collectAsState()
    val menu = rememberMenuAnchor()
    val inputs = remember(menu.expanded) { if (menu.expanded) Recognizer.inputs() else emptyList() }
    Box {
        PillButton(settings.recognitionInput ?: "Default input", { menu.expanded = true }, icon = "mic")
        AppMenu(menu) {
            MenuItem("Default input", if (settings.recognitionInput == null) "check" else null) {
                AppSettings.update { it.copy(recognitionInput = null) }
                menu.expanded = false
            }
            inputs.forEach { name ->
                MenuItem(name, if (settings.recognitionInput == name) "check" else null) {
                    AppSettings.update { it.copy(recognitionInput = name) }
                    menu.expanded = false
                }
            }
        }
    }
}

@Composable
private fun ListeningState(onCancel: () -> Unit) {
    val accent = LocalAccent.current
    val pulse = rememberInfiniteTransition()
    val scale by pulse.animateFloat(1f, 1.2f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse))
    // The phone's pulse, swollen a little more by how loud the input is, so you can see it hears you.
    val level by Recognizer.level.collectAsState()
    val loud by animateFloatAsState(level, tween(120))
    val grow = scale + loud * 0.12f
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(200.dp).graphicsLayer { scaleX = grow; scaleY = grow }.clip(CircleShape).background(accent.copy(alpha = 0.2f)),
            )
            Box(
                Modifier.size(180.dp).graphicsLayer { scaleX = grow * 0.9f; scaleY = grow * 0.9f }.clip(CircleShape).background(accent.copy(alpha = 0.3f)),
            )
            MicDisc(onCancel)
        }
        Text("Listening…", style = ConvxType.Headline, color = accent)
        PillButton("Cancel", onCancel)
    }
}

@Composable
private fun ProcessingState() {
    val accent = LocalAccent.current
    val spin = rememberInfiniteTransition()
    val rotation by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(2000, easing = LinearEasing)))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(160.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .border(
                        4.dp,
                        Brush.sweepGradient(listOf(accent, accent.copy(alpha = 0.5f), Color.Transparent, accent.copy(alpha = 0.5f), accent)),
                        CircleShape,
                    ),
            )
            ConvxIcon("music_note", tint = accent, size = 48.dp)
        }
        Text("Processing…", style = ConvxType.Headline, color = Color.White)
    }
}

@Composable
private fun SuccessState(result: RecognitionResult, onPlay: () -> Unit, onTryAgain: () -> Unit, onClose: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 16.dp),
    ) {
        Artwork(result.coverArtHqUrl ?: result.coverArtUrl, Modifier.size(180.dp), size = 544, shape = RoundedCornerShape(12.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            result.title,
            style = ConvxType.Title2.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(result.artist, style = ConvxType.Headline, color = ConvxColors.SecondaryLabel, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        result.album?.let {
            Text(it, style = ConvxType.Body, color = ConvxColors.SecondaryLabel.copy(alpha = 0.7f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(320.dp)) {
            WideButton("Play on Convx", "play_applemusic", onPlay, accent = true)
            WideButton("Re-listen", "mic", onTryAgain)
            WideButton("Close", "close", onClose, outlined = true)
        }
    }
}

/** The phone's full-width Button, FilledTonalButton and OutlinedButton. */
@Composable
private fun WideButton(text: String, icon: String, onClick: () -> Unit, accent: Boolean = false, outlined: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val red = LocalAccent.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(
                when {
                    accent -> if (hovered) red.copy(alpha = 0.88f) else red
                    outlined -> if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent
                    else -> Color.White.copy(alpha = if (hovered) 0.16f else 0.1f)
                },
            )
            .then(if (outlined) Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), shape) else Modifier)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon(icon, tint = Color.White, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = ConvxType.SubheadStrong, color = Color.White)
    }
}

/** No match and errors: the phone's red disc, what happened, and Try again. */
@Composable
private fun Problem(icon: String, title: String, message: String, onTryAgain: () -> Unit) {
    val accent = LocalAccent.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            ConvxIcon(icon, tint = accent, size = 48.dp)
        }
        Text(title, style = ConvxType.Title2.copy(fontWeight = FontWeight.Bold))
        Text(
            message,
            style = ConvxType.Body,
            color = ConvxColors.SecondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp).padding(horizontal = 32.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Try again", onTryAgain, icon = "refresh", filled = true)
            // Windows mutes desktop apps' microphones from its privacy page; open it.
            if (message == Recognizer.SilentInput) {
                PillButton("Microphone settings", { com.convx.desktop.ui.screens.settings.openUrl("ms-settings:privacy-microphone") }, icon = "settings")
            }
        }
        if (message == Recognizer.SilentInput) InputPicker()
    }
}

/** The phone's recognition history: what it found, by day, searchable, each one a search away from playing. */
@Composable
fun RecognitionHistoryScreen() {
    val navigator = LocalNavigator.current
    val library by Library.state.collectAsState()
    val items = library.recognitions
    var query by remember { mutableStateOf("") }
    val filtered = remember(items, query) {
        if (query.isEmpty()) items
        else items.filter { it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true) }
    }
    val grouped = remember(filtered) { filtered.groupBy { dayLabel(it.at) } }
    fun open(item: Recognition) = navigator.navigate(Screen.SearchResults("${item.title} ${item.artist}"))
    fun delete(item: Recognition) = Overlays.confirm("Delete", "Remove \"${item.title}\" from your recognition history?", "Delete") {
        Library.removeRecognition(item.at)
    }

    PageList(state = rememberLazyListState(), tint = Color.Black) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recognition History", style = ConvxType.LargeTitle, modifier = Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    PillButton("Clear", {
                        Overlays.confirm("Clear recognition history", "Are you sure you want to clear all recognition history?", "Clear") {
                            Library.clearRecognitions()
                        }
                    }, icon = "delete")
                }
            }
        }
        item(key = "search") { HistorySearch(query) { query = it } }
        when {
            items.isEmpty() -> item(key = "empty") { MessageState("No recognition history", icon = "history") }
            filtered.isEmpty() -> item(key = "none") { MessageState("No results for \"$query\"", icon = "search") }
            // Searching shows one flat list, as on the phone; otherwise by day.
            query.isNotEmpty() -> items(filtered, key = { "r:${it.at}" }) { HistoryRow(it, { open(it) }, { delete(it) }) }
            else -> grouped.forEach { (label, group) ->
                item(key = "h:$label") {
                    SectionHeader(label, Modifier.padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 4.dp))
                }
                items(group, key = { "r:${it.at}" }) { HistoryRow(it, { open(it) }, { delete(it) }) }
            }
        }
    }
}

@Composable
private fun HistorySearch(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = Gutter, vertical = 8.dp)
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConvxIcon("search", tint = ConvxColors.SecondaryLabel, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            val style = TextStyle(fontFamily = ConvxType.family, fontSize = 16.sp, color = Color.White)
            if (query.isEmpty()) Text("Search", style = style, color = ConvxColors.SecondaryLabel)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().onFocusChanged { UiState.typingFocusChanged("recognition-search", it.isFocused) },
            )
        }
        if (query.isNotEmpty()) IconButton("close", { onQuery("") }, size = 28.dp, iconSize = 15.dp, tint = ConvxColors.SecondaryLabel)
    }
}

private val RecognizedAt = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")
private val MonthYear = DateTimeFormatter.ofPattern("MMMM yyyy")

/** The phone's groups: Today, Yesterday, This Week, then by month. */
private fun dayLabel(at: Long): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val date = time.toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date >= today.minusDays(7) -> "This Week"
        else -> time.format(MonthYear)
    }
}

@Composable
private fun HistoryRow(item: Recognition, onClick: () -> Unit, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .padding(horizontal = Gutter, vertical = 6.dp)
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (hovered) ConvxColors.CardSecondary else ConvxColors.Card)
            .hoverable(interaction)
            .pressable(onClick, interaction = interaction, pressScale = 0.99f)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.coverArt, Modifier.size(60.dp), size = 200, shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = ConvxType.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist, style = ConvxType.Body, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Instant.ofEpochMilli(item.at).atZone(ZoneId.systemDefault()).format(RecognizedAt),
                style = ConvxType.Subhead.copy(fontSize = 12.sp),
                color = ConvxColors.SecondaryLabel.copy(alpha = 0.7f),
            )
        }
        IconButton("delete", onDelete, size = 36.dp, iconSize = 19.dp, tint = ConvxColors.SecondaryLabel)
    }
}
