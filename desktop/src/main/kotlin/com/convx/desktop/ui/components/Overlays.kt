/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import com.convx.desktop.ui.glass.LocalMenuBackdrop
import com.convx.desktop.ui.glass.MenuGlass
import com.convx.desktop.ui.glass.liquidGlass
import com.convx.desktop.ui.shell.UiState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxMotion
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/** App-wide modal dialog and notices, drawn by the shell above everything else. */
object Overlays {
    var dialog by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    internal val toasts = mutableStateListOf<Toast>()
    private var nextToast = 0L

    data class Toast(val id: Long, val text: String, val icon: String?, val action: String? = null, val onAction: (() -> Unit)? = null)

    fun showDialog(content: @Composable () -> Unit) {
        dialog = content
    }

    fun dismissDialog() {
        dialog = null
    }

    /** A short note at the bottom; with an [action] (the phone's snackbar Undo) it stays a little longer. */
    fun toast(text: String, icon: String? = "check", action: String? = null, onAction: (() -> Unit)? = null) {
        toasts += Toast(nextToast++, text, icon, action, onAction)
        while (toasts.size > 3) toasts.removeAt(0)
    }

    /** Asks for a name, e.g. a new playlist. */
    fun prompt(title: String, initial: String = "", confirm: String = "Save", onConfirm: (String) -> Unit) {
        showDialog {
            var text by remember { mutableStateOf(initial) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            DisposableEffect(Unit) { onDispose { UiState.typingFocusChanged("prompt", false) } }
            DialogCard(title) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(ConvxShapes.Control)
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = ConvxType.Body.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (text.isNotBlank()) {
                                onConfirm(text.trim())
                                dismissDialog()
                            }
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { UiState.typingFocusChanged("prompt", it.isFocused) }
                            .onPreviewKeyEvent {
                                when (it.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        if (text.isNotBlank()) {
                                            onConfirm(text.trim())
                                            dismissDialog()
                                        }
                                        true
                                    }
                                    Key.Escape -> {
                                        dismissDialog()
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    PillButton("Cancel", { dismissDialog() })
                    PillButton(confirm, {
                        if (text.isNotBlank()) {
                            onConfirm(text.trim())
                            dismissDialog()
                        }
                    }, filled = true)
                }
            }
        }
    }

    fun confirm(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        showDialog {
            DialogCard(title) {
                Text(message, style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    PillButton("Cancel", { dismissDialog() })
                    PillButton(confirm, {
                        onConfirm()
                        dismissDialog()
                    }, accent = Color(0xFFFF453A))
                }
            }
        }
    }
}

/** Menu glass a shade darker, so long text and fields read on it over any page. */
private val DialogGlass = MenuGlass.copy(surfaceOpacity = 0.9f, blurRadius = 12f)

@Composable
fun DialogCard(title: String, content: @Composable () -> Unit) {
    // The menus' glass over the page, where there is one to frost.
    val backdrop = LocalMenuBackdrop.current
    Column(
        Modifier
            .width(420.dp)
            .shadow(32.dp, ConvxShapes.CardLarge)
            .clip(ConvxShapes.CardLarge)
            .then(
                if (backdrop != null) {
                    Modifier.liquidGlass(ConvxShapes.CardLarge, DialogGlass, highlightAlpha = 0.22f, outerShadow = false, backdrop = backdrop)
                } else {
                    Modifier.background(Color(0xFF232325)).glassRim(ConvxShapes.CardLarge, 0.1f)
                },
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(24.dp),
    ) {
        Text(title, style = ConvxType.Title3)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

/**
 * Dialogs, then notices. Notices rise out from behind the bottom bar: [noticeLine] is the bar's
 * top edge above the window's bottom, and they stay hidden below it. With no bar to hide behind,
 * [clipNotices] is false and they rise and fade in where they stand. [noticeStart] and
 * [noticeEnd] centre them over the bar between the side panels.
 */
@Composable
fun OverlayHost(
    noticeLine: Dp = 0.dp,
    clipNotices: Boolean = false,
    noticeStart: Dp = 0.dp,
    noticeEnd: Dp = 0.dp,
    glassNotices: Boolean = true,
) {
    val dialog = Overlays.dialog
    // What the dialog showed, kept on screen while it leaves.
    var shown by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    if (dialog != null) shown = dialog
    // The page dims while the dialog springs up from a little smaller; closing, it shrinks a touch as it fades.
    AnimatedVisibility(
        dialog != null,
        enter = fadeIn(tween(220, easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(200)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Overlays.dismissDialog() },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.animateEnterExit(
                    enter = scaleIn(ConvxMotion.dialog(), initialScale = 0.86f) + fadeIn(tween(120)),
                    exit = scaleOut(tween(150, easing = ConvxMotion.ExitEasing), targetScale = 0.95f) +
                        fadeOut(tween(150, easing = ConvxMotion.ExitEasing)),
                ),
            ) {
                shown?.invoke()
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = noticeStart, end = noticeEnd, bottom = noticeLine)
            .then(if (clipNotices) Modifier.clipToBounds() else Modifier),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.padding(bottom = NoticeGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (toast in Overlays.toasts) {
                androidx.compose.runtime.key(toast.id) { NoticeView(toast, glassNotices) }
            }
        }
    }
}

/** How far above the bar a notice rests. */
private val NoticeGap = 36.dp

/** Menu glass, a little lighter: a notice is short and sits over the page only for a moment. */
private val NoticeGlass = MenuGlass.copy(surfaceOpacity = 0.72f)

@Composable
private fun NoticeView(toast: Overlays.Toast, glass: Boolean) {
    // 0 is tucked under the line it rises from, 1 is in place; the spring carries it a touch past.
    val rise = remember { Animatable(0f) }
    val opacity = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(toast.id) {
        launch { opacity.animateTo(1f, tween(120)) }
        launch { rise.animateTo(1f, ConvxMotion.notice()) }
        delay(if (toast.action != null) 4000 else 2600)
        leaving = true
    }
    // It sinks back the way it came.
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        launch { opacity.animateTo(0f, tween(240, easing = ConvxMotion.ExitEasing)) }
        rise.animateTo(0f, tween(240, easing = ConvxMotion.ExitEasing))
        Overlays.toasts.remove(toast)
    }
    val backdrop = if (glass) LocalMenuBackdrop.current else null
    val gap = NoticeGap
    Row(
        Modifier
            .graphicsLayer {
                val p = rise.value
                translationY = (1f - p) * (size.height + gap.toPx())
                val scale = 0.82f + 0.18f * p
                scaleX = scale
                scaleY = scale
                alpha = opacity.value
            }
            .shadow(16.dp, ConvxShapes.Capsule)
            .clip(ConvxShapes.Capsule)
            .then(
                if (backdrop != null) {
                    Modifier.liquidGlass(ConvxShapes.Capsule, NoticeGlass, highlightAlpha = 0.24f, outerShadow = false, backdrop = backdrop)
                } else {
                    Modifier.background(Color(0xFF2C2C2E)).glassRim(ConvxShapes.Capsule, 0.12f)
                },
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (toast.icon != null) ConvxIcon(toast.icon, tint = Color.White, size = 18.dp)
        Text(toast.text, style = ConvxType.SubheadStrong, color = Color.White)
        if (toast.action != null) {
            Text(
                toast.action,
                style = ConvxType.SubheadStrong,
                color = Color(0xFFFF453A),
                modifier = Modifier.padding(start = 6.dp).pressable({
                    toast.onAction?.invoke()
                    leaving = true
                }),
            )
        }
    }
}
