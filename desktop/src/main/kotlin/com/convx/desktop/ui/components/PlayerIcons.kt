/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.PlayerIconOverride
import com.convx.desktop.ui.theme.ConvxMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A player control whose glyph can be your own image, the phone's custom player icons. Only
 * controls the PC draws get a slot: one that nothing drew would be a setting that does nothing.
 *
 * [fallback] is the glyph drawn without an image. Apple Music v2 has its own set, as on the phone,
 * drawn with its own glyphs.
 */
enum class PlayerIconSlot(val fallback: String, val label: String, val isV2: Boolean = false) {
    PLAY("play_applemusic", "Play"),
    PAUSE("pause_applemusic", "Pause"),
    PREVIOUS("apple_skip_previous", "Previous"),
    NEXT("apple_skip_next", "Next"),
    REPLAY("replay", "Replay"),
    LIKE("favorite_border", "Like"),
    LIKED("favorite", "Liked"),
    MORE("more_horiz", "More"),
    SEEK_THUMB("play_applemusic", "Seek bar handle"),

    V2_PLAY("flamingo_play", "V2 Play", isV2 = true),
    V2_PAUSE("flamingo_pause", "V2 Pause", isV2 = true),
    V2_PREVIOUS("flamingo_rewind", "V2 Previous", isV2 = true),
    V2_NEXT("flamingo_fforward", "V2 Next", isV2 = true),
    V2_LIKE("flamingo_favorite", "V2 Like", isV2 = true),
    V2_LIKED("flamingo_favorited", "V2 Liked", isV2 = true),
    V2_MORE("flamingo_more", "V2 More", isV2 = true),
    V2_LYRICS("lyrics", "V2 Lyrics", isV2 = true),
    V2_QUEUE("apple_queue", "V2 Queue", isV2 = true),
    V2_VOLUME_DOWN("volume_down", "V2 Volume", isV2 = true),
    ;

    /** The seek bar's handle is only an image: without one, the bars draw their own. */
    val hiddenWhenUnset: Boolean get() = this == SEEK_THUMB
}

/** A slot's image, loaded, and whether it takes the control's colour. */
class CustomIcon(val painter: Painter, val tint: Boolean) {
    /** Null draws the image in its own colours. */
    fun colorFilter(color: Color): ColorFilter? = if (tint) ColorFilter.tint(color) else null
}

/**
 * Where the images live and how they come and go: one file per slot, named for it, in Convx's
 * folder. A file no slot points at any more is deleted, so ten tries at a play button don't
 * leave ten pictures behind.
 */
object PlayerIcons {
    val dir: File by lazy { File(AppDirs.data, "player_icons").apply { mkdirs() } }

    /** What the picker shows. The bytes decide what's kept: a mislabelled file still works. */
    val Extensions = listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg")

    /** No control is drawn bigger than this, so bigger pictures are scaled down to it. */
    private const val MaxSide = 512
    private const val MaxBytes = 20 * 1024 * 1024

    val overrides: StateFlow<Map<String, PlayerIconOverride>> = AppSettings.state
        .map { it.playerIcons }
        .distinctUntilChanged()
        .stateIn(AppScope, SharingStarted.Eagerly, AppSettings.value.playerIcons)

    private val painters = ConcurrentHashMap<String, Painter>()

    private fun key(icon: PlayerIconOverride) = "${icon.file}#${icon.stamp}"

    fun file(icon: PlayerIconOverride): File = File(dir, icon.file)

    fun peek(icon: PlayerIconOverride?): Painter? = icon?.let { painters[key(it)] }

    /**
     * The image, or null when its file has gone (a restore, a cleaner): the stock glyph is
     * drawn then, as on the phone, rather than no button at all.
     */
    suspend fun painter(icon: PlayerIconOverride): Painter? {
        painters[key(icon)]?.let { return it }
        val loaded = withContext(Dispatchers.IO) { runCatching { decode(file(icon)) }.getOrNull() } ?: return null
        painters[key(icon)] = loaded
        return loaded
    }

    // The replacement lives in the Compose resources library, which Convx doesn't otherwise need.
    @Suppress("DEPRECATION")
    private fun decode(file: File): Painter? {
        if (!file.isFile) return null
        return if (file.extension.equals("svg", ignoreCase = true)) {
            file.inputStream().use { loadSvgPainter(it, Density(1f)) }
        } else {
            BitmapPainter(SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap())
        }
    }

    /**
     * Takes [source] in as [slot]'s image. SVGs are kept as they are; a picture bigger than a
     * control is ever drawn is kept as a smaller PNG. Throws, with words to show, if it isn't an
     * image Convx can read.
     */
    fun import(slot: PlayerIconSlot, source: File) {
        val bytes = source.readBytes()
        require(bytes.size <= MaxBytes) { "That image is over 20 MB" }
        val (extension, kept) = if (source.extension.equals("svg", ignoreCase = true)) {
            requireNotNull(runCatching { SVGDOM(Data.makeFromBytes(bytes)).root }.getOrNull()) { "This SVG can't be read" }
            "svg" to bytes
        } else {
            val image = requireNotNull(runCatching { SkiaImage.makeFromEncoded(bytes) }.getOrNull()) {
                "This isn't an image Convx can read. Use PNG, JPEG, WebP, GIF, BMP or SVG"
            }
            val side = maxOf(image.width, image.height)
            if (side <= MaxSide) {
                source.extension.lowercase().ifEmpty { "png" } to bytes
            } else {
                val scale = MaxSide.toFloat() / side
                val width = (image.width * scale).toInt().coerceAtLeast(1)
                val height = (image.height * scale).toInt().coerceAtLeast(1)
                val surface = Surface.makeRasterN32Premul(width, height)
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true,
                )
                "png" to requireNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)?.bytes) { "Couldn't shrink this image" }
            }
        }
        val name = "${slot.name.lowercase()}.$extension"
        File(dir, name).writeBytes(kept)
        set(slot, name)
    }

    /** Gives [slot] a copy of an image another control already has, as the phone's picker offers. */
    fun reuse(slot: PlayerIconSlot, from: PlayerIconOverride) {
        val source = file(from)
        val name = "${slot.name.lowercase()}.${source.extension}"
        if (source.name != name) source.copyTo(File(dir, name), overwrite = true)
        set(slot, name)
    }

    private fun set(slot: PlayerIconSlot, name: String) {
        AppSettings.update {
            val tint = it.playerIcons[slot.name]?.tint ?: false
            it.copy(playerIcons = it.playerIcons + (slot.name to PlayerIconOverride(name, tint, System.currentTimeMillis())))
        }
        prune()
    }

    fun clear(slot: PlayerIconSlot) {
        AppSettings.update { it.copy(playerIcons = it.playerIcons - slot.name) }
        prune()
    }

    fun setTint(slot: PlayerIconSlot, tint: Boolean) {
        AppSettings.update { s ->
            val icon = s.playerIcons[slot.name] ?: return@update s
            s.copy(playerIcons = s.playerIcons + (slot.name to icon.copy(tint = tint)))
        }
    }

    internal fun prune() {
        val live = AppSettings.value.playerIcons.values
        val files = live.mapTo(HashSet()) { it.file }
        dir.listFiles()?.forEach { if (it.name !in files) it.delete() }
        val keys = live.mapTo(HashSet()) { key(it) }
        painters.keys.retainAll(keys)
    }
}

/** [slot]'s own image once it has loaded; null draws the stock glyph. */
@Composable
fun rememberPlayerIcon(slot: PlayerIconSlot?): CustomIcon? {
    val overrides by PlayerIcons.overrides.collectAsState()
    val icon = slot?.let { overrides[it.name] }
    val painter by produceState(PlayerIcons.peek(icon), icon) {
        value = icon?.let { PlayerIcons.painter(it) }
    }
    val loaded = painter
    return if (icon != null && loaded != null) CustomIcon(loaded, icon.tint) else null
}

/**
 * A player control's glyph: the image picked for [slot] if there is one, else [icon]. Controls
 * with a slot all draw through here, so a pick shows wherever that control is. An image that
 * keeps its own colours still fades with [tint], so a disabled button looks it.
 */
@Composable
fun PlayerGlyph(slot: PlayerIconSlot?, icon: String, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val custom = rememberPlayerIcon(slot)
    if (custom != null) {
        Image(
            custom.painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alpha = if (custom.tint) 1f else tint.alpha,
            colorFilter = custom.colorFilter(tint),
            modifier = modifier.size(size),
        )
    } else {
        ConvxIcon(icon, modifier, tint = tint, size = size)
    }
}

/**
 * Play, pause or replay, popping between them as the phone's does: the old glyph shrinks to 30%
 * as it fades and the new one grows from the same point, so it reads as the same button
 * changing its mind rather than another button appearing.
 */
@Composable
fun AnimatedPlayGlyph(slot: PlayerIconSlot, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = slot,
        transitionSpec = {
            (fadeIn(ConvxMotion.swapEnter()) + scaleIn(ConvxMotion.swapEnter(), initialScale = ConvxMotion.SwapInitialScale))
                .togetherWith(fadeOut(ConvxMotion.swapExit()) + scaleOut(ConvxMotion.swapExit(), targetScale = ConvxMotion.SwapInitialScale))
                .using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        label = "play_pause",
        modifier = modifier,
    ) { shown ->
        PlayerGlyph(shown, shown.fallback, tint, size)
    }
}
