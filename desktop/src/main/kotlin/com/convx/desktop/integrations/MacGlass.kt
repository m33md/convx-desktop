/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.Log
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Pointer

/**
 * Round buttons made of macOS' own Liquid Glass: an NSGlassEffectView holding an NSButton with an
 * SF Symbol. Compose keeps each button's place in the layout and says where it is; the native view
 * is drawn there, above the window's content, which is the only place AppKit glass can go.
 */
object MacGlass {
    private const val Tag = "MacGlass"

    private class Native(val tag: Long, val glass: Pointer, val button: Pointer, var symbol: String, var onClick: () -> Unit)

    private val buttons = HashMap<String, Native>()
    private val handlers = HashMap<Long, Native>()
    private var nextTag = 1L
    private var target: Pointer? = null
    private var windowHeight = 0.0
    private var hidden = false

    private fun interface ClickCb : Callback { fun invoke(self: Pointer?, cmd: Pointer?, sender: Pointer?) }
    private val keep = ArrayList<Any>()

    private fun target(): Pointer? {
        target?.let { return it }
        val klass = MacWindow.objc.getFunction("objc_allocateClassPair").invokePointer(arrayOf(MacWindow.cls("NSObject"), "ConvxGlassTarget", 0L)) ?: return null
        val click = ClickCb { _, _, sender ->
            val tag = MacWindow.sendLong(sender, "tag")
            val native = handlers[tag] ?: return@ClickCb
            javax.swing.SwingUtilities.invokeLater { native.onClick() }
        }
        keep += click
        MacWindow.objc.getFunction("class_addMethod").invokeInt(arrayOf(klass, MacWindow.sel("glassClick:"), CallbackReference.getFunctionPointer(click), "v@:@"))
        MacWindow.objc.getFunction("objc_registerClassPair").invoke(Void.TYPE, arrayOf(klass))
        return MacWindow.send(MacWindow.send(klass, "alloc"), "init").also { target = it }
    }

    private fun symbolImage(name: String, size: Double): Pointer? {
        val base = MacWindow.send(MacWindow.cls("NSImage"), "imageWithSystemSymbolName:accessibilityDescription:", MacWindow.nsString(name), null) ?: return null
        val config = MacWindow.msgSend.invokePointer(arrayOf(MacWindow.cls("NSImageSymbolConfiguration"), MacWindow.sel("configurationWithPointSize:weight:"), size, 0.23))
        return MacWindow.send(base, "imageWithSymbolConfiguration:", config) ?: base
    }

    private fun frame(view: Pointer?, x: Double, y: Double, w: Double, h: Double) =
        MacWindow.msgSend.invokePointer(arrayOf(view, MacWindow.sel("setFrame:"), NSRect(x, y, w, h)))

    /** The window's height in points, to turn Compose's top-left positions into AppKit's bottom-left ones. */
    fun setWindowHeight(points: Double) {
        if (!MacWindow.isMac) return
        windowHeight = points
    }

    /** Shows or hides every glass button (while the full-screen player covers the window). */
    fun setHidden(on: Boolean) {
        if (!MacWindow.isMac || on == hidden) return
        hidden = on
        MacWindow.onMain { buttons.values.forEach { MacWindow.send(it.glass, "setHidden:", if (on) 1 else 0) } }
    }

    private class Spec(
        val symbol: String, val left: Double, val top: Double, val width: Double, val height: Double,
        val enabled: Boolean, val accent: Boolean, val onClick: () -> Unit,
    )

    /** What each button should look like, kept so it can be drawn once the native window exists. */
    private val specs = java.util.concurrent.ConcurrentHashMap<String, Spec>()

    /**
     * Puts the button [id] at [left], [top], [width] by [height] points from the window's top left, with
     * [symbol] on it. [enabled] false dims it; [accent] tints the symbol Convx red.
     */
    fun place(id: String, symbol: String, left: Double, top: Double, width: Double, height: Double, enabled: Boolean, accent: Boolean, onClick: () -> Unit) {
        if (!MacWindow.isMac) return
        specs[id] = Spec(symbol, left, top, width, height, enabled, accent, onClick)
        apply(id)
    }

    /** Draws every button asked for before the native window was ready. */
    fun flush() {
        if (!MacWindow.isMac) return
        specs.keys.toList().forEach { apply(it) }
    }

    private fun apply(id: String) {
        MacWindow.onMain {
            runCatching {
                val frameView = MacWindow.frameView ?: return@runCatching
                val content = MacWindow.contentView ?: return@runCatching
                val spec = specs[id] ?: return@runCatching
                val h = if (windowHeight > 0) windowHeight else MacWindow.heightPt.toDouble()
                var native = buttons[id]
                if (native == null) {
                    val tag = nextTag++
                    val button = MacWindow.send(MacWindow.send(MacWindow.cls("NSButton"), "alloc"), "init")
                    MacWindow.send(button, "setBordered:", 0)
                    MacWindow.send(button, "setImagePosition:", 1L) // image only
                    MacWindow.send(button, "setTarget:", target())
                    MacWindow.send(button, "setAction:", MacWindow.sel("glassClick:"))
                    MacWindow.send(button, "setTag:", tag)
                    val glass = MacWindow.send(MacWindow.send(MacWindow.cls("NSGlassEffectView"), "alloc"), "init")
                    frame(glass, spec.left, h - spec.top - spec.height, spec.width, spec.height)
                    frame(button, 0.0, 0.0, spec.width, spec.height)
                    MacWindow.send(glass, "setStyle:", 0L)
                    MacWindow.send(glass, "setContentView:", button)
                    MacWindow.send(glass, "setAutoresizingMask:", 8L) // keeps its distance from the window's top
                    MacWindow.send(frameView, "addSubview:positioned:relativeTo:", glass, 1L, content)
                    native = Native(tag, glass!!, button!!, "", spec.onClick)
                    buttons[id] = native
                    handlers[tag] = native
                    Log.i(Tag, "Glass button $id at ${spec.left},${spec.top} ${spec.width}x${spec.height}")
                }
                native.onClick = spec.onClick
                MacWindow.msgSend.invokePointer(arrayOf(native.glass, MacWindow.sel("setCornerRadius:"), minOf(spec.width, spec.height) / 2))
                frame(native.glass, spec.left, h - spec.top - spec.height, spec.width, spec.height)
                if (native.symbol != spec.symbol) {
                    native.symbol = spec.symbol
                    MacWindow.send(native.button, "setImage:", symbolImage(spec.symbol, 15.0))
                }
                MacWindow.send(native.button, "setEnabled:", if (spec.enabled) 1 else 0)
                val tint = if (spec.accent) MacWindow.msgSend.invokePointer(arrayOf(MacWindow.cls("NSColor"), MacWindow.sel("colorWithRed:green:blue:alpha:"), 0.98, 0.176, 0.282, 1.0))
                else MacWindow.msgSend.invokePointer(arrayOf(MacWindow.cls("NSColor"), MacWindow.sel("colorWithWhite:alpha:"), 1.0, if (spec.enabled) 0.9 else 0.35))
                MacWindow.send(native.button, "setContentTintColor:", tint)
                MacWindow.send(native.glass, "setHidden:", if (hidden) 1 else 0)
            }.onFailure { Log.w(Tag, "Glass button $id failed", it) }
        }
    }

    /** Takes the button away (its screen was left). */
    fun remove(id: String) {
        if (!MacWindow.isMac) return
        specs.remove(id)
        MacWindow.onMain {
            val native = buttons.remove(id) ?: return@onMain
            handlers.remove(native.tag)
            MacWindow.send(native.glass, "removeFromSuperview")
        }
    }
}
