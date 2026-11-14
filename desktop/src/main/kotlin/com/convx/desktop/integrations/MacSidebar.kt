/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.Log
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Pointer
import kotlinx.coroutines.launch

/**
 * The side panel as macOS draws it: an NSTableView in the system's source list style (its own
 * rows, icons, selection and scrolling) inside a Liquid Glass panel (NSGlassEffectView, macOS 26;
 * the sidebar material before that). Compose only says what the rows are and what a click does.
 *
 * The Objective-C delegate is a class made at run time with JNA, its methods Kotlin callbacks.
 */
object MacSidebar {
    private const val Tag = "MacSidebar"

    enum class Kind { Group, Row }

    /** A row: [symbol] is its SF Symbol, [image] the web address or file of a cover that replaces it once loaded. */
    class Item(
        val key: String,
        val kind: Kind,
        val title: String,
        val symbol: String,
        val selected: Boolean,
        val image: String? = null,
        val onClick: () -> Unit,
    )

    @Volatile private var items: List<Item> = emptyList()
    @Volatile private var footerItems: List<Item> = emptyList()
    private var signature = ""
    private var visible = true

    private var panel: Pointer? = null
    private var table: Pointer? = null
    private var footerTable: Pointer? = null
    private var scrollView: Pointer? = null
    private var suppress = false

    /** Compose's current rows: [rows] scroll, [footer] stays at the bottom. Reloads only when what is drawn changed. */
    fun update(rows: List<Item>, footer: List<Item>, visible: Boolean) {
        if (!MacWindow.isMac) return
        items = rows
        footerItems = footer
        val sig = (rows + footer).joinToString("|") { "${it.key}:${it.title}:${it.selected}:${it.image}" }
        val changed = sig != signature
        signature = sig
        val show = visible
        MacWindow.onMain {
            val view = panel ?: return@onMain
            if (show != this.visible) {
                this.visible = show
                MacWindow.send(view, "setHidden:", if (show) 0 else 1)
            }
            if (changed) reload()
        }
    }

    // ---- The delegate class --------------------------------------------------------------------

    private fun interface RowsCb : Callback { fun invoke(self: Pointer?, cmd: Pointer?, table: Pointer?): Long }
    private fun interface ViewCb : Callback { fun invoke(self: Pointer?, cmd: Pointer?, table: Pointer?, column: Pointer?, row: Long): Pointer? }
    private fun interface FlagCb : Callback { fun invoke(self: Pointer?, cmd: Pointer?, table: Pointer?, row: Long): Int }
    private fun interface HeightCb : Callback { fun invoke(self: Pointer?, cmd: Pointer?, table: Pointer?, row: Long): Double }
    private fun interface ChangedCb : Callback { fun invoke(self: Pointer?, cmd: Pointer?, note: Pointer?) }

    private val keep = ArrayList<Any>()

    private const val Width = 232.0
    private const val HeaderHeight = 76.0
    private const val FooterHeight = 82.0

    private fun heightOf(kind: Kind?): Double = if (kind == Kind.Group) 34.0 else 32.0

    private fun listFor(table: Pointer?): List<Item> = if (table != null && table == footerTable) footerItems else items

    private fun addMethod(klass: Pointer?, name: String, callback: Callback, types: String) {
        keep += callback
        MacWindow.objc.getFunction("class_addMethod").invokeInt(
            arrayOf(klass, MacWindow.sel(name), CallbackReference.getFunctionPointer(callback), types),
        )
    }

    private fun delegate(): Pointer? {
        val alloc = MacWindow.objc.getFunction("objc_allocateClassPair")
        val klass = alloc.invokePointer(arrayOf(MacWindow.cls("NSObject"), "ConvxSidebarDelegate", 0L))
            ?: return MacWindow.send(MacWindow.send(MacWindow.cls("ConvxSidebarDelegate"), "alloc"), "init")
        addMethod(klass, "numberOfRowsInTableView:", RowsCb { _, _, t -> listFor(t).size.toLong() }, "q@:@")
        addMethod(klass, "tableView:viewForTableColumn:row:", ViewCb { _, _, t, _, row -> runCatching { cell(listFor(t).getOrNull(row.toInt())) }.onFailure { Log.w(Tag, "cell failed", it) }.getOrNull() }, "@@:@@q")
        addMethod(klass, "tableView:isGroupRow:", FlagCb { _, _, t, row -> if (listFor(t).getOrNull(row.toInt())?.kind == Kind.Group) 1 else 0 }, "c@:@q")
        addMethod(klass, "tableView:shouldSelectRow:", FlagCb { _, _, t, row -> if (listFor(t).getOrNull(row.toInt())?.kind == Kind.Row) 1 else 0 }, "c@:@q")
        addMethod(
            klass, "tableView:heightOfRow:",
            HeightCb { _, _, t, row -> heightOf(listFor(t).getOrNull(row.toInt())?.kind) },
            "d@:@q",
        )
        addMethod(
            klass, "tableViewSelectionDidChange:",
            ChangedCb { _, _, note ->
                if (suppress) return@ChangedCb
                val source = MacWindow.send(note, "object")
                val row = MacWindow.sendLong(source, "selectedRow").toInt()
                val item = listFor(source).getOrNull(row) ?: return@ChangedCb
                if (item.kind == Kind.Row) javax.swing.SwingUtilities.invokeLater { item.onClick() }
            },
            "v@:@",
        )
        MacWindow.objc.getFunction("objc_registerClassPair").invoke(Void.TYPE, arrayOf(klass))
        return MacWindow.send(MacWindow.send(klass, "alloc"), "init")
    }

    // ---- Cells ---------------------------------------------------------------------------------

    private fun frame(view: Pointer?, x: Double, y: Double, w: Double, h: Double) =
        MacWindow.msgSend.invokePointer(arrayOf(view, MacWindow.sel("setFrame:"), NSRect(x, y, w, h)))

    private fun color(r: Double, g: Double, b: Double, a: Double = 1.0): Pointer? =
        MacWindow.msgSend.invokePointer(arrayOf(MacWindow.cls("NSColor"), MacWindow.sel("colorWithRed:green:blue:alpha:"), r, g, b, a))

    private fun font(size: Double, weight: Double): Pointer? =
        MacWindow.msgSend.invokePointer(arrayOf(MacWindow.cls("NSFont"), MacWindow.sel("systemFontOfSize:weight:"), size, weight))

    private val images = java.util.concurrent.ConcurrentHashMap<String, Pointer>()
    private val loading = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Fetches a cover once, then redraws the rows so it appears. */
    private fun fetch(url: String) {
        if (!loading.add(url)) return
        com.convx.desktop.core.AppScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = runCatching {
                if (url.startsWith("http")) {
                    com.convx.desktop.core.Http.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body.bytes() }
                } else {
                    java.io.File(java.net.URI(url)).readBytes()
                }
            }.getOrNull() ?: return@launch
            MacWindow.onMain {
                val memory = com.sun.jna.Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
                val data = MacWindow.send(MacWindow.cls("NSData"), "dataWithBytes:length:", memory, bytes.size.toLong())
                val image = MacWindow.send(MacWindow.send(MacWindow.cls("NSImage"), "alloc"), "initWithData:", data) ?: return@onMain
                images[url] = image
                reload()
            }
        }
    }

    private fun cell(item: Item?): Pointer? {
        item ?: return null
        val cell = MacWindow.send(MacWindow.send(MacWindow.cls("NSTableCellView"), "alloc"), "init")
        when (item.kind) {
            Kind.Group -> {
                val label = MacWindow.send(MacWindow.cls("NSTextField"), "labelWithString:", MacWindow.nsString(item.title))
                MacWindow.send(label, "setFont:", font(11.0, 0.3))
                MacWindow.send(label, "setTextColor:", color(1.0, 1.0, 1.0, 0.42))
                frame(label, 18.0, 6.0, 200.0, 16.0)
                MacWindow.send(cell, "addSubview:", label)
                MacWindow.send(cell, "setTextField:", label)
            }
            Kind.Row -> {
                if (item.selected) {
                    // The lit row: a soft light pill, as in the reference.
                    val pill = MacWindow.send(MacWindow.send(MacWindow.cls("NSView"), "alloc"), "init")
                    MacWindow.send(pill, "setWantsLayer:", 1)
                    val layer = MacWindow.send(pill, "layer")
                    val white = MacWindow.msgSend.invokePointer(arrayOf(MacWindow.cls("NSColor"), MacWindow.sel("colorWithWhite:alpha:"), 1.0, 0.13))
                    MacWindow.send(layer, "setBackgroundColor:", MacWindow.send(white, "CGColor"))
                    MacWindow.msgSend.invokePointer(arrayOf(layer, MacWindow.sel("setCornerRadius:"), 8.0))
                    frame(pill, 10.0, 1.0, 212.0, 30.0)
                    MacWindow.send(pill, "setAutoresizingMask:", 2L)
                    MacWindow.send(cell, "addSubview:", pill)
                }
                val cover = item.image?.let { url -> images[url] ?: run { fetch(url); null } }
                val icon = MacWindow.send(MacWindow.send(MacWindow.cls("NSImageView"), "alloc"), "init")
                if (cover != null) {
                    MacWindow.send(icon, "setImage:", cover)
                    MacWindow.send(icon, "setImageScaling:", 3L) // fill the square
                    MacWindow.send(icon, "setWantsLayer:", 1)
                    val layer = MacWindow.send(icon, "layer")
                    MacWindow.msgSend.invokePointer(arrayOf(layer, MacWindow.sel("setCornerRadius:"), 5.0))
                    MacWindow.send(layer, "setMasksToBounds:", 1)
                    frame(icon, 18.0, 5.0, 22.0, 22.0)
                } else {
                    val image = MacWindow.send(
                        MacWindow.cls("NSImage"), "imageWithSystemSymbolName:accessibilityDescription:",
                        MacWindow.nsString(item.symbol), null,
                    ) ?: MacWindow.send(MacWindow.cls("NSImage"), "imageWithSystemSymbolName:accessibilityDescription:", MacWindow.nsString("music.note"), null)
                    MacWindow.send(icon, "setImage:", image)
                    // Convx red, as the reference's pink icons.
                    MacWindow.send(icon, "setContentTintColor:", color(0.98, 0.176, 0.282))
                    frame(icon, 18.0, 6.0, 20.0, 20.0)
                }
                val label = MacWindow.send(MacWindow.cls("NSTextField"), "labelWithString:", MacWindow.nsString(item.title))
                MacWindow.send(label, "setFont:", font(13.0, 0.23))
                MacWindow.send(label, "setTextColor:", color(1.0, 1.0, 1.0, 0.92))
                MacWindow.send(label, "setLineBreakMode:", 4L) // truncate the tail
                frame(label, 46.0, 6.0, 170.0, 20.0)
                MacWindow.send(label, "setAutoresizingMask:", 2L)
                MacWindow.send(cell, "addSubview:", icon)
                MacWindow.send(cell, "addSubview:", label)
                MacWindow.send(cell, "setImageView:", icon)
                MacWindow.send(cell, "setTextField:", label)
            }
        }
        return cell
    }

    private var logo: Pointer? = null
    private var pendingLogo: Pair<ByteArray, Double>? = null

    /** The wordmark at the panel's top, beside the window buttons; [png] is white, [aspect] its width over its height. */
    fun setLogo(png: ByteArray, aspect: Double) {
        if (!MacWindow.isMac) return
        pendingLogo = png to aspect
        MacWindow.onMain { if (panel != null) placeLogo() }
    }

    private fun placeLogo() {
        val (png, aspect) = pendingLogo ?: return
        val box = container ?: return
        if (logo != null) return
        val memory = com.sun.jna.Memory(png.size.toLong()).apply { write(0, png, 0, png.size) }
        val data = MacWindow.send(MacWindow.cls("NSData"), "dataWithBytes:length:", memory, png.size.toLong())
        val image = MacWindow.send(MacWindow.send(MacWindow.cls("NSImage"), "alloc"), "initWithData:", data) ?: return
        val view = MacWindow.send(MacWindow.send(MacWindow.cls("NSImageView"), "alloc"), "init")
        MacWindow.send(view, "setImage:", image)
        val h = 17.0
        // Below the window buttons, in line with the rows' icons; it stays put while the list scrolls.
        frame(view, 22.0, MacWindow.heightPt - 16.0 - 46.0 - h, h * aspect, h)
        MacWindow.send(view, "setAutoresizingMask:", 8L) // stays at the top as the window grows
        MacWindow.send(box, "addSubview:", view)
        logo = view
    }

    private var container: Pointer? = null

    private fun reload() {
        suppress = true
        try {
            for ((view, list) in listOf(table to items, footerTable to footerItems)) {
                view ?: continue
                MacWindow.send(view, "reloadData")
                val at = list.indexOfFirst { it.selected && it.kind == Kind.Row }
                if (at >= 0) {
                    val set = MacWindow.send(MacWindow.cls("NSIndexSet"), "indexSetWithIndex:", at.toLong())
                    MacWindow.send(view, "selectRowIndexes:byExtendingSelection:", set, 0)
                } else {
                    MacWindow.send(view, "deselectAll:", null)
                }
            }
        } finally {
            suppress = false
        }
    }

    // ---- The views -----------------------------------------------------------------------------

    private fun makeList(delegate: Pointer?): Pointer? {
        val list = MacWindow.send(MacWindow.send(MacWindow.cls("NSTableView"), "alloc"), "init")
        val column = MacWindow.send(MacWindow.send(MacWindow.cls("NSTableColumn"), "alloc"), "initWithIdentifier:", MacWindow.nsString("c"))
        MacWindow.msgSend.invokePointer(arrayOf(column, MacWindow.sel("setWidth:"), Width))
        MacWindow.send(list, "addTableColumn:", column)
        MacWindow.send(list, "setHeaderView:", null)
        MacWindow.send(list, "setStyle:", 4L)
        MacWindow.send(list, "setBackgroundColor:", MacWindow.send(MacWindow.cls("NSColor"), "clearColor"))
        MacWindow.send(list, "setSelectionHighlightStyle:", -1L) // none: the selected row is drawn in its own cell
        MacWindow.send(list, "setFloatsGroupRows:", 0)
        MacWindow.send(list, "setAllowsEmptySelection:", 1)
        MacWindow.send(list, "setDataSource:", delegate)
        MacWindow.send(list, "setDelegate:", delegate)
        return list
    }

    /**
     * Builds the glass panel above the window's content, on AppKit's main thread. Inside it: the wordmark
     * at the top, the scrolling list under it, and Settings and Profile fixed at the bottom.
     */
    internal fun build() {
        val frameView = MacWindow.frameView ?: return
        val content = MacWindow.contentView ?: return
        val height = MacWindow.heightPt.toDouble() - 16.0

        val delegate = delegate()
        keep += delegate!!
        val list = makeList(delegate)
        val footer = makeList(delegate)
        table = list
        footerTable = footer

        val scroll = MacWindow.send(MacWindow.send(MacWindow.cls("NSScrollView"), "alloc"), "init")
        MacWindow.send(scroll, "setDocumentView:", list)
        MacWindow.send(scroll, "setDrawsBackground:", 0)
        MacWindow.send(scroll, "setHasVerticalScroller:", 1)
        MacWindow.send(scroll, "setAutohidesScrollers:", 1)
        MacWindow.send(scroll, "setBorderType:", 0L)
        // Between the header and the footer; the rows clip at its edges.
        frame(scroll, 0.0, FooterHeight, Width, height - HeaderHeight - FooterHeight)
        MacWindow.send(scroll, "setAutoresizingMask:", 18L)

        // Settings and the profile: a table of their own, pinned to the bottom, and a hairline above it.
        frame(footer, 0.0, 8.0, Width, 68.0)
        MacWindow.send(footer, "setAutoresizingMask:", 2L)
        val line = MacWindow.send(MacWindow.send(MacWindow.cls("NSView"), "alloc"), "init")
        MacWindow.send(line, "setWantsLayer:", 1)
        val white = MacWindow.msgSend.invokePointer(arrayOf(MacWindow.cls("NSColor"), MacWindow.sel("colorWithWhite:alpha:"), 1.0, 0.10))
        MacWindow.send(MacWindow.send(line, "layer"), "setBackgroundColor:", MacWindow.send(white, "CGColor"))
        frame(line, 18.0, FooterHeight - 4.0, Width - 36.0, 1.0)
        MacWindow.send(line, "setAutoresizingMask:", 2L)

        val box = MacWindow.send(MacWindow.send(MacWindow.cls("NSView"), "alloc"), "init")
        frame(box, 0.0, 0.0, Width, height)
        MacWindow.send(box, "addSubview:", scroll)
        MacWindow.send(box, "addSubview:", footer)
        MacWindow.send(box, "addSubview:", line)
        container = box

        // Liquid Glass where macOS has it; the sidebar material before that.
        val glassClass = MacWindow.cls("NSGlassEffectView")
        val view: Pointer?
        if (glassClass != null) {
            view = MacWindow.send(MacWindow.send(glassClass, "alloc"), "init")
            MacWindow.msgSend.invokePointer(arrayOf(view, MacWindow.sel("setCornerRadius:"), 24.0))
            // Sized before it is attached: the glass lays its content out to its own bounds.
            frame(view, 8.0, 8.0, Width, height)
            MacWindow.send(view, "setStyle:", 0L) // regular glass: readable over a busy wallpaper
            // A little dark in it, so the rows stay legible on any wallpaper.
            MacWindow.send(view, "setTintColor:", MacWindow.msgSend.invokePointer(arrayOf(MacWindow.cls("NSColor"), MacWindow.sel("colorWithWhite:alpha:"), 0.0, 0.30)))
            MacWindow.send(view, "setContentView:", box)
            Log.i(Tag, "Using NSGlassEffectView (Liquid Glass)")
        } else {
            view = MacWindow.send(MacWindow.send(MacWindow.cls("NSVisualEffectView"), "alloc"), "init")
            MacWindow.send(view, "setMaterial:", 7L)
            MacWindow.send(view, "setBlendingMode:", 0L)
            MacWindow.send(view, "setState:", 1L)
            MacWindow.send(view, "setWantsLayer:", 1)
            val layer = MacWindow.send(view, "layer")
            MacWindow.msgSend.invokePointer(arrayOf(layer, MacWindow.sel("setCornerRadius:"), 20.0))
            MacWindow.send(layer, "setMasksToBounds:", 1)
            MacWindow.send(box, "setAutoresizingMask:", 18L)
            MacWindow.send(view, "addSubview:", box)
            Log.i(Tag, "Using the sidebar material")
        }
        MacWindow.send(view, "setAutoresizingMask:", 16L) // height follows the window
        frame(view, 8.0, 8.0, Width, height)
        // Above the Compose content, below the window buttons.
        MacWindow.send(frameView, "addSubview:positioned:relativeTo:", view, 1L, content)
        panel = view
        scrollView = scroll
        placeLogo()
        reload()
    }
}
