/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.Log
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * The Mac window, made by macOS instead of drawn by us: a real titled window (its own rounded
 * corners, shadow and traffic lights) with the system's sidebar material behind the side panel.
 *
 * The Compose window is transparent; the side panel's rows are drawn over an NSVisualEffectView,
 * which is the blur Finder, Music and Kaset use, so it costs the app nothing per frame.
 * Reached through the Objective-C runtime with JNA, as [MacMediaControls] is. Best effort: if
 * anything is refused it is logged and the window stays as it was.
 */
/** An NSRect passed by value; JNA lays it out the way each architecture wants (the stack on Intel, d0-d3 on Apple Silicon). */
@Structure.FieldOrder("x", "y", "width", "height")
class NSRect(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
) : Structure(), Structure.ByValue

object MacWindow {
    private const val Tag = "MacWindow"

    val isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac")

    internal val objc by lazy { NativeLibrary.getInstance("objc") }
    internal val msgSend: Function by lazy { objc.getFunction("objc_msgSend") }
    internal val getClass: Function by lazy { objc.getFunction("objc_getClass") }
    private val selector: Function by lazy { objc.getFunction("sel_registerName") }
    private val system by lazy { NativeLibrary.getInstance("System") }
    private val dispatchAsync: Function by lazy { system.getFunction("dispatch_async_f") }
    private val mainQueue: Pointer by lazy { system.getGlobalVariableAddress("_dispatch_main_q") }

    private val selectors = HashMap<String, Pointer>()
    internal fun sel(name: String): Pointer = selectors.getOrPut(name) { selector.invokePointer(arrayOf(name)) ?: error("No selector $name") }
    internal fun cls(name: String): Pointer? = getClass.invokePointer(arrayOf(name))

    internal fun send(target: Pointer?, name: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(target, sel(name), *args))

    internal fun sendLong(target: Pointer?, name: String, vararg args: Any?): Long =
        msgSend.invokeLong(arrayOf(target, sel(name), *args))

    private fun interface Work : Callback {
        fun invoke(context: Pointer?)
    }

    private val pending = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private val drain = Work {
        pending.poll()?.let { job -> runCatching(job).onFailure { Log.w(Tag, "Native window call failed", it) } }
    }

    /** AppKit only takes window changes on its main thread. */
    internal fun onMain(job: () -> Unit) {
        pending.add(job)
        dispatchAsync.invoke(Void.TYPE, arrayOf(mainQueue, null, CallbackReference.getFunctionPointer(drain)))
    }

    private var effect: Pointer? = null
    internal var frameView: Pointer? = null
    internal var contentView: Pointer? = null
    internal var heightPt: Int = 0
    private var window: Pointer? = null

    /** The macOS sidebar material, behind the whole window. Call once the window is on screen. */
    fun install(title: String, width: Int, height: Int) {
        if (!isMac) return
        onMain {
            NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")
            val app = send(cls("NSApplication"), "sharedApplication")
            val windows = send(app, "windows")
            val count = sendLong(windows, "count")
            var target: Pointer? = null
            for (i in 0 until count) {
                val w = send(windows, "objectAtIndex:", i)
                val text = send(send(w, "title"), "UTF8String")?.getString(0)
                if (text == title) target = w
            }
            val w = target ?: error("No window titled $title")
            window = w

            // A titled window whose content runs under the title bar: macOS rounds it, shadows it and
            // draws the traffic lights. Closable, miniaturizable and resizable, with the full-size content view.
            val mask = 1L or 2L or 4L or 8L or (1L shl 15)
            send(w, "setStyleMask:", mask)
            send(w, "setTitlebarAppearsTransparent:", 1)
            send(w, "setTitleVisibility:", 1)
            send(w, "setOpaque:", 0)
            send(w, "setBackgroundColor:", send(cls("NSColor"), "clearColor"))
            send(w, "setHasShadow:", 1)
            // Convx is dark: the material, the glass and the list all follow the window.
            send(w, "setAppearance:", send(cls("NSAppearance"), "appearanceNamed:", nsString("NSAppearanceNameDarkAqua")))
            // A toolbar gives the larger corner radius and the lower traffic lights of a Mac app's window.
            val toolbar = send(send(cls("NSToolbar"), "alloc"), "initWithIdentifier:", nsString("convx"))
            send(w, "setToolbar:", toolbar)
            send(w, "setToolbarStyle:", 3L)

            val content = send(w, "contentView")
            val view = send(send(cls("NSVisualEffectView"), "alloc"), "init")
            send(view, "setMaterial:", 21L) // under-window background
            send(view, "setBlendingMode:", 0L) // behind the window
            send(view, "setState:", 1L) // active
            send(view, "setWantsLayer:", 1)
            send(view, "setAutoresizingMask:", 18L) // width and height follow the window
            frame(view, 0.0, 0.0, width.toDouble(), height.toDouble())
            // Under everything the window draws.
            val frameView = send(content, "superview")
            this.frameView = frameView
            contentView = content
            heightPt = height
            // Beneath the content view, in the window's own frame view, where Finder and Music keep theirs.
            // The window's own blurred background, which the floating glass panel sits over.
            send(frameView, "addSubview:positioned:relativeTo:", view, -1L, content)
            effect = view
            // Finder's corner: the whole window, glass and content, is cut to the one rounded shape.
            send(frameView, "setWantsLayer:", 1)
            val shape = send(frameView, "layer")
            msgSend.invokePointer(arrayOf(shape, sel("setCornerRadius:"), 26.0))
            send(shape, "setMasksToBounds:", 1)
            send(w, "invalidateShadow")
            runCatching { MacSidebar.build() }.onFailure { Log.w(Tag, "Native sidebar failed", it) }
            MacGlass.flush()
            run {
                fun name(o: Pointer?) = send(send(o, "className"), "UTF8String")?.getString(0)
                val subs = send(send(content, "superview"), "subviews")
                val n = sendLong(subs, "count")
                val names = (0 until n).joinToString { name(send(subs, "objectAtIndex:", it)) ?: "?" }
                Log.i(Tag, "contentView ${name(content)} subviews [$names] hidden=${sendLong(view, "isHidden")} window=${send(view, "window") != null} layerBacked=${sendLong(content, "wantsLayer")}")
            }
            Log.i(Tag, "Native window material installed in a ${width}x$height window")
        }
    }

    // NSRect by value: four doubles, which is how both Intel and Apple Silicon take it.
    internal fun frame(view: Pointer?, left: Double, top: Double, width: Double, height: Double) {
        msgSend.invokePointer(arrayOf(view, sel("setFrame:"), NSRect(left, top, width, height)))
    }

    internal fun nsString(text: String): Pointer {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val buffer = com.sun.jna.Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
        return send(cls("NSString"), "stringWithUTF8String:", buffer) ?: error("NSString")
    }
}
