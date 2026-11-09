/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import org.jetbrains.skia.Data
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Path
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.svg.SVGDOM
import java.util.zip.GZIPInputStream

/**
 * Disc Studio's sticker library, shipped with Convx so it works offline: Fluent Emoji (Microsoft,
 * MIT), drawn in their own colours, and Material Design Icons (Pictogrammers, Apache 2.0), drawn
 * in the sticker's colour. Built by desktop/packaging/make_stickers.py, and read the first time a
 * sticker from it is wanted. Each sticker draws in a box from -1 to 1, like Convx's own.
 */
object StickerLibrary {
    enum class Source(val id: String, val label: String, internal val file: String, internal val box: Float, val tinted: Boolean) {
        EMOJI("emoji", "Emoji", "/stickers/emoji.tsv.gz", 32f, tinted = false),
        ICONS("mdi", "Icons", "/stickers/icons.tsv.gz", 24f, tinted = true),
    }

    class Sticker internal constructor(val source: Source, val name: String, val categories: List<String>, internal val art: String) {
        val id: String = LibraryStickerPrefix + source.id + ":" + name
        val label: String = name.replace('-', ' ').replaceFirstChar { it.uppercase() }
        internal val words: String = name.replace('-', ' ') + " " + categories.joinToString(" ").lowercase()
    }

    private val loaded = HashMap<Source, List<Sticker>>()
    private val byId = HashMap<String, Sticker>()

    /** Every sticker of [source], in its categories' order. */
    fun stickers(source: Source): List<Sticker> = synchronized(loaded) {
        loaded.getOrPut(source) {
            val list = runCatching { read(source) }.getOrElse { emptyList() }
            for (sticker in list) byId[sticker.id] = sticker
            list
        }
    }

    /** [source]'s categories, in the order its stickers are grouped. */
    fun categories(source: Source): List<String> {
        val all = stickers(source)
        val first = all.map { it.categories.first() }.distinct()
        return first + (all.flatMap { it.categories }.distinct() - first.toSet())
    }

    val count: Int get() = Source.entries.sumOf { stickers(it).size }

    fun of(id: String): Sticker? {
        if (!id.startsWith(LibraryStickerPrefix)) return null
        val rest = id.removePrefix(LibraryStickerPrefix)
        val source = Source.entries.firstOrNull { rest.startsWith(it.id + ":") } ?: return null
        stickers(source)
        return synchronized(loaded) { byId[id] }
    }

    /** Stickers whose name has every word of [query]; with no words, [source]'s stickers in [category] (all of them for null). */
    fun search(query: String, source: Source, category: String?): List<Sticker> {
        val words = query.lowercase().split(' ', '-', ',').filter { it.isNotBlank() }
        if (words.isEmpty()) return stickers(source).filter { category == null || category in it.categories }
        // A search looks through both sets, best matches (a whole word) first.
        val found = Source.entries.flatMap { stickers(it) }.filter { s -> words.all { it in s.words } }
        return found.sortedByDescending { s -> words.count { w -> s.name.split('-').contains(w) } }
    }

    private fun read(source: Source): List<Sticker> {
        val stream = StickerLibrary::class.java.getResourceAsStream(source.file) ?: return emptyList()
        return GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) null else Sticker(source, parts[0], parts[1].split(','), parts[2])
            }.toList()
        }
    }

    // ---- Drawing -----------------------------------------------------------------------------

    private const val Kept = 600

    private val pictures = object : LinkedHashMap<String, Picture>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Picture>?) = size > Kept
    }
    private val paths = object : LinkedHashMap<String, Path>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Path>?) = size > Kept
    }

    /** Whether [sticker] is ready to draw without reading its art first. */
    fun isReady(sticker: Sticker): Boolean =
        if (sticker.source.tinted) synchronized(paths) { sticker.id in paths } else synchronized(pictures) { sticker.id in pictures }

    /** A one-colour sticker's outline, filling the box from -1 to 1. */
    fun path(sticker: Sticker): Path? {
        synchronized(paths) { paths[sticker.id] }?.let { return it }
        val path = runCatching {
            val combined = Path()
            for (d in sticker.art.split('|')) combined.addPath(Path.makeFromSVGString(d))
            val half = sticker.source.box / 2f
            combined.transform(Matrix33(1f / half, 0f, -1f, 0f, 1f / half, -1f, 0f, 0f, 1f), true)
        }.getOrNull() ?: return null
        synchronized(paths) { paths[sticker.id] = path }
        return path
    }

    /** A sticker in its own colours, recorded once, filling the box from -1 to 1. */
    fun picture(sticker: Sticker): Picture? {
        synchronized(pictures) { pictures[sticker.id] }?.let { return it }
        val box = sticker.source.box
        val picture = runCatching {
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="${box.toInt()}" height="${box.toInt()}" viewBox="0 0 ${box.toInt()} ${box.toInt()}">${sticker.art}</svg>"""
            val dom = SVGDOM(Data.makeFromBytes(svg.toByteArray()))
            try {
                dom.setContainerSize(box, box)
                val recorder = PictureRecorder()
                val canvas = recorder.beginRecording(Rect.makeLTRB(-1f, -1f, 1f, 1f))
                canvas.translate(-1f, -1f)
                canvas.scale(2f / box, 2f / box)
                dom.render(canvas)
                recorder.finishRecordingAsPicture()
            } finally {
                dom.close()
            }
        }.getOrNull() ?: return null
        synchronized(pictures) { pictures[sticker.id] = picture }
        return picture
    }
}
