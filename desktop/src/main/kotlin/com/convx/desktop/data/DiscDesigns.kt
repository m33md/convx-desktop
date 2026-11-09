/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import com.convx.desktop.core.AppDirs
import com.convx.desktop.core.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Typeface
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The discs designed in Disc Studio: one JSON file each, the pictures they use in a folder beside
 * them (named by their content, so a picture used twice is kept once). A design shared as a
 * .convxdisc file is a zip of its JSON, its pictures and a preview, and opens on any PC.
 */
object DiscDesigns {
    private const val Tag = "DiscDesigns"
    const val Extension = "convxdisc"
    const val Folder = "disc_designs"
    const val AssetFolder = "disc_design_assets"

    /** The picture formats Skia reads. */
    val ImageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    private const val MaxImageBytes = 25L * 1024 * 1024
    private const val MaxShareBytes = 80L * 1024 * 1024
    private const val MaxSide = 1600

    val dir: File by lazy { File(AppDirs.data, Folder).apply { mkdirs() } }
    val assetDir: File by lazy { File(AppDirs.data, AssetFolder).apply { mkdirs() } }

    private val _all = MutableStateFlow<List<DiscDesign>>(emptyList())

    /** Every design, the last one changed first. */
    val all: StateFlow<List<DiscDesign>> = _all

    @Volatile
    private var loaded = false

    /** Reads the designs from disk the first time anything asks. */
    fun load() {
        if (!loaded) refresh()
    }

    fun refresh() {
        val designs = dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().mapNotNull { file ->
            runCatching { StoreJson.decodeFromString(DiscDesign.serializer(), file.readText()) }
                .onFailure { Log.w(Tag, "Couldn't read ${file.name}", it) }
                .getOrNull()
        }
        _all.value = designs.sortedByDescending { it.updatedAt }
        loaded = true
    }

    fun get(id: String?): DiscDesign? {
        if (id == null) return null
        load()
        return _all.value.firstOrNull { it.id == id }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)

    private fun file(id: String) = File(dir, safe(id) + ".json")

    /** Saves [design], and draws it again on every playlist that wears it. */
    fun save(design: DiscDesign): DiscDesign {
        load()
        val saved = design.copy(updatedAt = System.currentTimeMillis())
        val target = file(saved.id)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(StoreJson.encodeToString(DiscDesign.serializer(), saved))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        _all.update { list -> (list.filterNot { it.id == saved.id } + saved).sortedByDescending { it.updatedAt } }
        playlistsWearing(saved.id).forEach { applyTo(saved, it.id) }
        return saved
    }

    /** Deletes a design. Playlists wearing it keep the picture it made, no longer tied to it. */
    fun delete(id: String) {
        runCatching { file(id).delete() }
        _all.update { list -> list.filterNot { it.id == id } }
        Library.forgetDesign(id)
        pruneAssets()
    }

    fun duplicate(design: DiscDesign): DiscDesign {
        val now = System.currentTimeMillis()
        return save(
            design.copy(
                id = UUID.randomUUID().toString(),
                name = "${design.name} copy",
                layers = design.layers.map { it.copy(id = newLayerId()) },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    fun playlistsWearing(id: String): List<LocalPlaylist> = Library.value.localPlaylists.filter { it.design == id }

    // ---- Pictures ----------------------------------------------------------------------------

    fun assetFile(name: String): File = File(assetDir, File(name).name)

    /** Copies a picture in for a design; throws with a message fit to show when it can't. */
    fun importImage(source: File): String {
        if (!source.isFile) error("That file isn't there any more.")
        if (source.length() > MaxImageBytes) error("That picture is over 25 MB.")
        val bytes = runCatching { source.readBytes() }.getOrElse { error("Couldn't read that file.") }
        return importImage(bytes, source.extension.lowercase())
    }

    /**
     * Keeps [bytes] (an encoded picture) as a design asset and returns its name. Big pictures are
     * scaled down to 1600 pixels a side; ones that may be see-through stay PNG.
     */
    fun importImage(bytes: ByteArray, extension: String): String {
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: error("That file isn't a picture Convx can read.")
        val clear = extension in setOf("png", "webp", "gif")
        val (data, ext) = if (image.width > MaxSide || image.height > MaxSide || extension !in ImageExtensions || extension == "bmp") {
            val scale = minOf(1f, MaxSide.toFloat() / maxOf(image.width, image.height))
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
            val scaled = surface.makeImageSnapshot()
            val encoded = if (clear) scaled.encodeToData(EncodedImageFormat.PNG)?.bytes else scaled.encodeToData(EncodedImageFormat.JPEG, 92)?.bytes
            (encoded ?: error("Couldn't shrink the picture.")) to if (clear) "png" else "jpg"
        } else {
            bytes to if (extension == "jpeg") "jpg" else extension
        }
        val hash = MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }.take(24)
        val name = "$hash.$ext"
        val target = File(assetDir, name)
        if (!target.isFile) target.writeBytes(data)
        return name
    }

    /** Deletes pictures no design uses; [keep] holds ones an open editor uses but hasn't saved yet. */
    fun pruneAssets(keep: Set<String> = emptySet()) {
        load()
        val used = _all.value.flatMapTo(HashSet()) { it.assets } + keep
        assetDir.listFiles()?.filter { it.isFile && it.name !in used }?.forEach { runCatching { it.delete() } }
    }

    // ---- Sharing -----------------------------------------------------------------------------

    fun fileName(design: DiscDesign): String =
        design.name.replace(Regex("""[\\/:*?"<>|]"""), "").trim().ifEmpty { "Disc" }.take(60) + ".$Extension"

    /** Writes [design] as a .convxdisc file: its JSON, its pictures and a preview anyone can look at. */
    fun export(design: DiscDesign, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            val manifest = """{"app":"Convx","kind":"disc","version":${design.version},"name":${kotlinx.serialization.json.JsonPrimitive(design.name)}}"""
            zip.write(manifest.toByteArray())
            zip.putNextEntry(ZipEntry("design.json"))
            zip.write(StoreJson.encodeToString(DiscDesign.serializer(), design).toByteArray())
            runCatching { DiscArt.png(DiscArt.renderCover(design, Resources, 600)) }.onSuccess {
                zip.putNextEntry(ZipEntry("preview.png"))
                zip.write(it)
            }
            design.assets.forEach { asset ->
                val file = assetFile(asset)
                if (file.isFile) {
                    zip.putNextEntry(ZipEntry("assets/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                }
            }
        }
    }

    /** Reads a .convxdisc file and saves it as a new design of your own; throws with a message fit to show. */
    fun import(source: File): DiscDesign {
        if (!source.isFile) error("That file isn't there any more.")
        if (source.length() > MaxShareBytes) error("That file is too big to be a disc design.")
        var design: DiscDesign? = null
        val pictures = HashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(source.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.isDirectory -> Unit
                        entry.name == "design.json" -> design = StoreJson.decodeFromString(DiscDesign.serializer(), zip.readBytes().decodeToString())
                        // Only a picture's own name is kept: an entry can't write anywhere else.
                        entry.name.startsWith("assets/") -> {
                            val name = File(entry.name).name
                            if (name.substringAfterLast('.', "").lowercase() in ImageExtensions) pictures[name] = zip.readBytes()
                        }
                    }
                }
            }
        }.onFailure { Log.w(Tag, "Couldn't read ${source.name}", it) }
        val read = design ?: error("That isn't a Convx disc design.")
        val renamed = HashMap<String, String>()
        pictures.forEach { (name, bytes) ->
            runCatching { importImage(bytes, name.substringAfterLast('.').lowercase()) }.onSuccess { renamed[name] = it }
        }
        val now = System.currentTimeMillis()
        return save(
            read.copy(
                id = UUID.randomUUID().toString(),
                layers = read.layers.map { layer -> layer.copy(id = newLayerId(), asset = layer.asset?.let { renamed[File(it).name] }) },
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    // ---- Playlists ---------------------------------------------------------------------------

    /** Puts [design] on the playlist as its cover: a CD in its case, or a record on its sleeve. */
    fun applyTo(design: DiscDesign, playlistId: String): Boolean {
        val bytes = runCatching { DiscArt.jpeg(DiscArt.renderCover(design, Resources, 900)) }
            .onFailure { Log.w(Tag, "Couldn't draw ${design.name}", it) }
            .getOrNull() ?: return false
        return when (val result = PlaylistCovers.save(playlistId, bytes, "jpg")) {
            is PlaylistCovers.Result.Done -> {
                Library.setPlaylistDesign(playlistId, result.cover, design.id)
                true
            }
            is PlaylistCovers.Result.Failed -> {
                Log.w(Tag, "Couldn't keep the cover for $playlistId: ${result.reason}")
                false
            }
        }
    }

    /** The pictures and fonts designs are drawn with: the designs' own picture files, Convx's fonts, Windows' families. */
    object Resources : DiscArt.Resources {
        private val images = object : LinkedHashMap<String, Image?>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image?>?): Boolean = size > 48
        }

        override fun image(asset: String): Image? = synchronized(images) {
            images.getOrPut(asset) {
                runCatching { assetFile(asset).takeIf { it.isFile }?.readBytes()?.let { Image.makeFromEncoded(it) } }.getOrNull()
            }
        }

        override fun typeface(font: String): Typeface? = DiscArt.bundledTypeface(font) ?: DiscArt.systemTypeface(font)
    }
}
