/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.AppScope
import com.convx.desktop.core.Http
import com.convx.desktop.core.Memory
import com.convx.desktop.data.AppSettings
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.HeroColors
import com.convx.desktop.ui.theme.MeshColors
import com.convx.desktop.ui.theme.Palette
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.MipmapMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val YtThumbnailName = Regex("""[a-z0-9_]+default\.(jpg|webp)""")
private val YtJpegName = Regex("""[a-z0-9_]+default\.jpg""")
private val GoogleSizeMark = Regex("""=s\d+""")
private val GoogleSizeTail = Regex("""=s\d+.*$""")

/** Resizes YouTube/Google artwork URLs to the size actually drawn (the phone's String.resize). */
fun artworkUrl(url: String?, size: Int): String? {
    if (url.isNullOrBlank()) return null
    if (url.contains("i.ytimg.com")) {
        val quality = when {
            size >= 1000 -> "maxresdefault.jpg"
            size >= 400 -> "sddefault.jpg"
            size >= 250 -> "hqdefault.jpg"
            else -> "mqdefault.jpg"
        }
        return url.replace(YtThumbnailName, quality).substringBefore("?")
    }
    if ((url.contains("googleusercontent.com") || url.contains("ggpht.com")) && url.contains("=w")) {
        return "${url.substringBefore("=w")}=w$size-h$size-p-l90-rj"
    }
    if ((url.contains("googleusercontent.com") || url.contains("ggpht.com")) && GoogleSizeMark.containsMatchIn(url)) {
        return url.replace(GoogleSizeTail, "=s$size")
    }
    return url
}

/**
 * Memory + disk image cache. Thumbnails are small and YouTube serves them fast, but
 * scrolling a long shelf asks for dozens at once, so network fetches are throttled and
 * duplicate requests share one download.
 */
object ImageCache {
    /**
     * Decoded covers kept for reuse, least recently drawn first out. Held by size, not count, and
     * sized to the PC: about 50 MB on 8 GB of memory. The ones still on screen stay drawn whether
     * or not they're kept here.
     */
    private val MemoryBudget = (Memory.physicalBytes / 160).coerceIn(32L * 1024 * 1024, 96L * 1024 * 1024)

    /** Dropped covers' pixels are only freed by a collection, so one is asked for after this much is dropped. */
    private const val CollectAfterDropping = 16L * 1024 * 1024

    /** A kept cover: its pixels, and the side it was made for ([Whole] when it's the picture as it came). */
    private class Kept(val image: ImageBitmap, val side: Int) {
        val bytes = image.width.toLong() * image.height * 4
    }

    private const val Whole = Int.MAX_VALUE
    private val memory = LinkedHashMap<String, Kept>(256, 0.75f, true)
    private var memoryBytes = 0L
    private var droppedBytes = 0L
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val palettes = ConcurrentHashMap<String, HeroColors>()
    private val meshes = ConcurrentHashMap<String, List<Color>>()
    private val network = Semaphore(8)
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** The cover as it's kept now, at whatever size that is. */
    fun peek(url: String): ImageBitmap? = synchronized(memory) { memory[url]?.image }

    /** What the kept covers' pixels take, in bytes. */
    fun memoryBytes(): Long = synchronized(memory) { memoryBytes }

    private fun kept(url: String, side: Int): ImageBitmap? = synchronized(memory) {
        memory[url]?.takeIf { it.side >= (if (side > 0) side else Whole) }?.image
    }

    private fun keep(url: String, image: ImageBitmap, side: Int) {
        synchronized(memory) {
            val entry = Kept(image, side)
            memory.put(url, entry)?.let { dropped(it) }
            memoryBytes += entry.bytes
            trimTo(MemoryBudget, url)
        }
    }

    private fun dropped(entry: Kept) {
        memoryBytes -= entry.bytes
        droppedBytes += entry.bytes
        if (droppedBytes >= CollectAfterDropping) {
            droppedBytes = 0
            Memory.collectSoon()
        }
    }

    private fun trimTo(bytes: Long, keeping: String? = null) {
        val entries = memory.entries.iterator()
        while (memoryBytes > bytes && entries.hasNext()) {
            val eldest = entries.next()
            if (eldest.key == keeping) continue
            entries.remove()
            dropped(eldest.value)
        }
    }

    /** Keeps only the [keepBytes] of covers drawn last, for a Convx nobody is looking at. */
    fun trimMemory(keepBytes: Long) = synchronized(memory) { trimTo(keepBytes) }

    /**
     * The picture at [url]. With a [maxSide], it's made no bigger than a box of that many pixels
     * needs (its shorter side comes down to [maxSide]), which is what keeps a wall of covers
     * small; without one it's the picture as it came.
     */
    suspend fun load(url: String, maxSide: Int = 0): ImageBitmap? {
        kept(url, maxSide)?.let { return it }
        if (url in failed) return null
        val flight = "$maxSide $url"
        // The work is the app's, not the asker's: a cover that scrolls away mid-download still
        // lands for whoever else asked for it.
        val mine = AppScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val outcome = runCatching { fetch(url, maxSide) }
                val result = outcome.getOrNull()
                // Only a definite "not there" is remembered; network blips retry on the next ask.
                if (result == null && outcome.isSuccess) failed += url
                // A bigger copy made meanwhile stays.
                result?.let { (image, side) -> kept(url, side) ?: image.also { keep(url, it, side) } }
            } finally {
                inFlight.remove(flight)
            }
        }
        val existing = inFlight.putIfAbsent(flight, mine)
        if (existing != null) {
            mine.cancel()
            return existing.await()
        }
        return mine.await()
    }

    suspend fun palette(url: String): HeroColors? {
        palettes[url]?.let { return it }
        val bitmap = peek(url) ?: load(url, ColorsSide) ?: return null
        val colors = withContext(Dispatchers.Default) { Palette.extract(bitmap) }
        palettes[url] = colors
        return colors
    }

    suspend fun mesh(url: String): List<Color>? {
        meshes[url]?.let { return it }
        val bitmap = peek(url) ?: load(url, ColorsSide) ?: return null
        val colors = withContext(Dispatchers.Default) { MeshColors.extract(bitmap) }
        meshes[url] = colors
        return colors
    }

    /** Colours are read from a small copy; a bigger one is as good when it's already kept. */
    private const val ColorsSide = 160

    private suspend fun fetch(url: String, maxSide: Int): Pair<ImageBitmap, Int>? {
        // Covers of music files on this PC are read where they are.
        if (url.startsWith("file:")) {
            val bytes = File(java.net.URI(url)).takeIf { it.isFile }?.readBytes() ?: return null
            return decode(bytes, maxSide)
        }
        val file = File(AppDirs.images, sha1(url))
        val keep = AppSettings.value.maxImageCacheMb != 0
        val bytes = if (keep && file.isFile && file.length() > 0) {
            // Touched now and then, so trimming drops what hasn't been seen for longest.
            if (System.currentTimeMillis() - file.lastModified() > TouchAfterMs) file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        } else {
            network.withPermit {
                download(url)
                    // maxres/sd thumbnails don't exist for every video; hq always does.
                    ?: url.takeIf { it.contains("i.ytimg.com") && !it.contains("hqdefault") }
                        ?.let { download(it.replace(YtJpegName, "hqdefault.jpg")) }
            }?.also { data ->
                if (keep) {
                    runCatching { file.writeBytes(data) }
                    if (writes.incrementAndGet() % TrimEvery == 0) trimDisk()
                }
            } ?: return null
        }
        return decode(bytes, maxSide)
    }

    /**
     * The pixels and the side they're good for, copied out of Skia's encoded image, which is let
     * go of at once rather than at the next GC. A picture well over [maxSide] is scaled down here,
     * once, rather than on every frame it's drawn in.
     */
    private fun decode(bytes: ByteArray, maxSide: Int): Pair<ImageBitmap, Int> = SkiaImage.makeFromEncoded(bytes).use { image ->
        val shorter = minOf(image.width, image.height)
        if (maxSide <= 0 || shorter <= maxSide * 5 / 4) return@use image.toComposeImageBitmap() to Whole
        val scale = maxSide.toFloat() / shorter
        val bitmap = Bitmap()
        bitmap.allocN32Pixels((image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1))
        val scaled = bitmap.peekPixels()?.use { image.scalePixels(it, FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), false) } == true
        if (!scaled) {
            bitmap.close()
            return@use image.toComposeImageBitmap() to Whole
        }
        bitmap.setImmutable()
        bitmap.asComposeImageBitmap() to maxSide
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        return Http.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.bytes() else null
        }
    }

    fun clearDisk() {
        AppDirs.images.listFiles()?.forEach { it.delete() }
    }

    fun diskBytes(): Long = AppDirs.images.listFiles()?.sumOf { it.length() } ?: 0L

    private const val TrimEvery = 200
    private const val TouchAfterMs = 24L * 60 * 60 * 1000
    private val writes = java.util.concurrent.atomic.AtomicInteger()

    /** The phone's "Max image cache size": the covers seen longest ago go first. */
    fun trimDisk() {
        val cap = AppSettings.value.maxImageCacheMb.toLong() * 1024 * 1024
        val files = AppDirs.images.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= cap) break
            total -= file.length()
            file.delete()
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The picture at [url] as it came; lists of covers use [Artwork], which makes it the size it's drawn at. */
@Composable
fun rememberImage(url: String?): State<ImageBitmap?> =
    produceState(initialValue = url?.let(ImageCache::peek), url) {
        value = url?.let { ImageCache.load(it) }
    }

/** The pixel sizes covers are made at: the first one that covers the box they're drawn in. */
private val CoverSides = intArrayOf(40, 56, 72, 96, 128, 160, 200, 256, 320, 400, 512, 640, 800, 1000, 1280, 1600)

private fun coverSide(pixels: Int): Int = CoverSides.firstOrNull { it >= pixels } ?: 0

@Composable
fun rememberHeroColors(url: String?): State<HeroColors?> =
    produceState<HeroColors?>(initialValue = null, url) {
        value = url?.let { ImageCache.palette(it) }
    }

@Composable
fun rememberMeshColors(url: String?): State<List<Color>?> =
    produceState<List<Color>?>(initialValue = null, url) {
        value = url?.let { ImageCache.mesh(it) }
    }

/**
 * Artwork with a quiet placeholder while it loads (or when there is none). [size] picks which
 * copy the server sends; what's kept in memory is as big as the box it's drawn in.
 */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 544,
    shape: Shape = ConvxShapes.Artwork,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderIcon: String = "music_note",
    iconSize: Dp = 28.dp,
) {
    val resolved = remember(url, size) { artworkUrl(url, size) }
    // The box's longer side in pixels, known once it's laid out; the cover is then made for it.
    val side = remember { MutableStateFlow(-1) }
    val image by produceState(initialValue = resolved?.let(ImageCache::peek), resolved) {
        if (resolved == null) {
            value = null
            return@produceState
        }
        side.collectLatest { pixels -> if (pixels >= 0) value = ImageCache.load(resolved, pixels) }
    }
    Box(
        modifier.clip(shape).background(ConvxColors.CardSecondary).onSizeChanged { side.value = coverSide(maxOf(it.width, it.height)) },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            ConvxIcon(placeholderIcon, tint = Color.White.copy(alpha = 0.28f), size = iconSize)
        }
    }
}
