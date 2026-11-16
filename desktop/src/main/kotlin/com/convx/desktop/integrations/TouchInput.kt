/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import com.convx.desktop.core.Log
import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.awt.event.MouseEvent
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import com.sun.jna.Memory as NativeMemory

/**
 * Touch screens on Windows. Left to the runtime, a finger becomes mouse-wheel steps and a click
 * when it lifts: lists jump instead of following the finger, nothing flings, sliders can't be
 * dragged and there is no long press. This reads the touches from Windows itself and hands them
 * to Compose as touch, which scrolls, flings, drags and long-presses as the phone does.
 */
@OptIn(InternalComposeUiApi::class)
object TouchInput {
    private const val Tag = "Touch"

    private const val WM_CREATE = 0x0001
    private const val WM_SIZE = 0x0005
    private const val WM_MOUSEFIRST = 0x0200
    private const val WM_MOUSELAST = 0x020E
    private const val WM_PARENTNOTIFY = 0x0210
    private const val WM_TOUCH = 0x0240
    private const val GWLP_WNDPROC = -4

    private const val TOUCHEVENTF_MOVE = 0x1
    private const val TOUCHEVENTF_DOWN = 0x2
    private const val TOUCHEVENTF_UP = 0x4
    private const val TOUCHEVENTF_PRIMARY = 0x10

    /** A TOUCHINPUT on 64-bit Windows: x and y at 0 and 4 (hundredths of a pixel), its id at 16, its flags at 20. */
    private const val InputSize = 48

    /** What Windows marks a mouse message with when it made it up from a finger (MI_WP_SIGNATURE, touch bit set). */
    private const val FromTouchMask = 0xFFFFFF80L
    private const val FromTouch = 0xFF515780L

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val api: TouchApi by lazy { Native.load("user32", TouchApi::class.java) }

    /** Each hooked window's own procedure, by its handle. */
    private val previous = ConcurrentHashMap<Long, Pointer>()

    @Volatile private var frame: WinDef.HWND? = null
    @Volatile private var window: ComposeWindow? = null
    private var target: Target? = null

    /** True once a touch has been read here, from when the mouse messages Windows makes from it are dropped. */
    @Volatile private var touching = false

    /** The finger being followed, by Windows' id for it; -1 while none is down. Read on the window's message thread. */
    private var finger = -1

    // Kept here so the collector never takes the windows' procedure away.
    private val procedure = object : WinUser.WindowProc {
        override fun callback(hwnd: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
            val key = Pointer.nativeValue(hwnd.pointer)
            try {
                when (msg) {
                    WM_TOUCH -> if (touched(wParam, lParam)) return WinDef.LRESULT(0)
                    in WM_MOUSEFIRST..WM_MOUSELAST -> if (touching && fromTouch()) return WinDef.LRESULT(0)
                    WM_PARENTNOTIFY -> if ((wParam.toLong() and 0xFFFF).toInt() == WM_CREATE) hook(WinDef.HWND(Pointer(lParam.toLong())))
                    WM_SIZE -> if (key == frame?.let { Pointer.nativeValue(it.pointer) }) hookChildren()
                }
            } catch (_: Throwable) {
                // Never let an exception unwind into Windows.
            }
            return User32.INSTANCE.CallWindowProc(previous[key], hwnd, msg, wParam, lParam)
        }
    }

    /** Starts reading touches for [window]; call on the UI thread once the window is on screen. */
    fun install(window: ComposeWindow) {
        if (!isWindows || this.window != null) return
        // Without a way into the window's scene, the runtime's own touch handling stays.
        val target = target(window) ?: return
        val hwnd = runCatching { WinDef.HWND(Native.getWindowPointer(window)) }.getOrNull() ?: return
        this.target = target
        this.window = window
        frame = hwnd
        runCatching {
            hook(hwnd)
            hookChildren()
        }.onFailure { Log.w(Tag, "Couldn't listen to touch", it) }
        Log.i(Tag, "Listening to touch on ${previous.size} windows")
    }

    private fun hook(hwnd: WinDef.HWND) {
        val key = Pointer.nativeValue(hwnd.pointer)
        if (previous.containsKey(key)) return
        // Noted first: the window's thread can call the new procedure before SetWindowLongPtr returns.
        val current = User32.INSTANCE.GetWindowLongPtr(hwnd, GWLP_WNDPROC).toPointer() ?: return
        previous[key] = current
        // Registered, a window gets each touch as it happens rather than finished gestures.
        api.RegisterTouchWindow(hwnd, 0)
        val before = User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, CallbackReference.getFunctionPointer(procedure))
        if (before == null) previous.remove(key) else previous[key] = before
    }

    private fun hookChildren() {
        val parent = frame ?: return
        User32.INSTANCE.EnumChildWindows(parent, { child, _ -> hook(child); true }, null)
    }

    private fun fromTouch(): Boolean = (api.GetMessageExtraInfo().toLong() and FromTouchMask) == FromTouch

    /** Reads one WM_TOUCH and passes its first finger on; false leaves the message to the runtime. */
    private fun touched(wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): Boolean {
        val hwnd = frame ?: return false
        val count = (wParam.toLong() and 0xFFFF).toInt()
        if (count <= 0) return false
        val handle = Pointer(lParam.toLong())
        val inputs = NativeMemory(count.toLong() * InputSize)
        if (!api.GetTouchInputInfo(handle, count, inputs, InputSize)) return false
        // Where the window's client area starts on screen, to place each finger in it.
        val origin = WinDef.POINT(0, 0)
        api.ScreenToClient(hwnd, origin)
        val time = System.currentTimeMillis()
        for (i in 0 until count) {
            val at = i.toLong() * InputSize
            val id = inputs.getInt(at + 16)
            val flags = inputs.getInt(at + 20)
            val kind = when {
                flags and TOUCHEVENTF_DOWN != 0 ->
                    if (finger == -1 && flags and TOUCHEVENTF_PRIMARY != 0) PointerEventType.Press.also { finger = id } else null
                id != finger -> null
                flags and TOUCHEVENTF_UP != 0 -> PointerEventType.Release.also { finger = -1 }
                flags and TOUCHEVENTF_MOVE != 0 -> PointerEventType.Move
                else -> null
            } ?: continue
            val x = inputs.getInt(at) / 100f + origin.x
            val y = inputs.getInt(at + 4) / 100f + origin.y
            SwingUtilities.invokeLater { send(kind, x, y, time) }
        }
        api.CloseTouchInputHandle(handle)
        touching = true
        return true
    }

    /** Hands one finger's step to Compose, at [clientX], [clientY] in the window's client pixels. */
    internal fun send(kind: PointerEventType, clientX: Float, clientY: Float, time: Long = System.currentTimeMillis()) {
        val window = window ?: return
        val target = target ?: return
        com.convx.desktop.core.Memory.noteInput()
        runCatching {
            val scale = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
            val insets = window.insets
            // The client area starts past the frame's insets, in the frame's own (unscaled) units.
            val position = target.origin(window) + Offset(clientX + insets.left * scale, clientY + insets.top * scale)
            target.scene.sendPointerEvent(
                eventType = kind,
                position = position,
                timeMillis = time,
                type = PointerType.Touch,
                buttons = PointerButtons(isPrimaryPressed = kind != PointerEventType.Release),
            )
        }.onFailure { Log.w(Tag, "Couldn't pass a touch on", it) }
    }

    /** Starts reading touches for a window outside Windows, for checking [send] where there are no touches to read. */
    internal fun attachForTest(window: ComposeWindow): Boolean {
        this.target = target(window) ?: return false
        this.window = window
        return true
    }

    /** The window's Compose scene, and how it places a point of the frame in it: neither is public, so reached by name. */
    private class Target(val scene: ComposeScene, private val mediator: Any, private val positionOf: Method) {
        /** Where the frame's top-left corner sits in the scene, in pixels. */
        fun origin(window: ComposeWindow): Offset {
            val packed = positionOf.invoke(mediator, MouseEvent(window, MouseEvent.MOUSE_MOVED, 0L, 0, 0, 0, 0, false)) as Long
            return Offset(Float.fromBits((packed ushr 32).toInt()), Float.fromBits(packed.toInt()))
        }
    }

    private fun target(window: ComposeWindow): Target? = runCatching {
        val panel = field(window, "composePanel")
        val container = field(panel, "_composeContainer")
        val mediator = field(container, "mediator")
        val scene = (field(mediator, "scene\$delegate") as Lazy<*>).value as ComposeScene
        val positionOf = mediator.javaClass.declaredMethods.first {
            it.name.startsWith("getPosition") && it.parameterTypes.contentEquals(arrayOf(MouseEvent::class.java))
        }.apply { isAccessible = true }
        Target(scene, mediator, positionOf)
    }.onFailure { Log.w(Tag, "Can't reach the window's scene; touch stays as the runtime makes it", it) }.getOrNull()

    private fun field(owner: Any, name: String): Any {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val found = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (found != null) return found.apply { isAccessible = true }.get(owner) ?: error("$name is empty")
            type = type.superclass
        }
        error("No $name in ${owner.javaClass.name}")
    }
}

private interface TouchApi : StdCallLibrary {
    fun RegisterTouchWindow(hwnd: WinDef.HWND, flags: Int): Boolean
    fun GetTouchInputInfo(input: Pointer, count: Int, inputs: Pointer, size: Int): Boolean
    fun CloseTouchInputHandle(input: Pointer): Boolean
    fun ScreenToClient(hwnd: WinDef.HWND, point: WinDef.POINT): Boolean
    fun GetMessageExtraInfo(): WinDef.LPARAM
}
