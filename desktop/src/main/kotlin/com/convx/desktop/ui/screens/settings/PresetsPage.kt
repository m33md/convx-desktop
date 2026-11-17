/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.data.PresetCategory
import com.convx.desktop.data.Presets
import com.convx.desktop.player.PlayerController
import com.convx.desktop.ui.components.AppMenu
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.theme.LocalAccent
import com.convx.desktop.ui.components.MenuItem
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.components.onSecondaryClick
import com.convx.desktop.ui.components.openAtProvider
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.components.rememberMenuAnchor
import com.convx.desktop.ui.shell.UiState
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

/**
 * The phone's Presets: your looks saved by name, applied in parts, and shared as files the
 * phone opens too. Cards show the player as it looked when the preset was saved.
 */
@Composable
fun PresetsSettings() {
    val presets by Presets.all.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Presets.refresh() } }

    Text(
        "Save how Convx looks and put it back with a click, or share it as a .convxpreset file. Presets from the phone open here " +
            "too, minus its colours and wallpaper.",
        style = ConvxType.Subhead,
        color = ConvxColors.SecondaryLabel,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
    )
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Save current look", { savePresetDialog() }, icon = "add", filled = true)
        PillButton("Open a preset file", { importPreset() }, icon = "download")
    }
    SectionGap()
    if (presets.isEmpty()) {
        MessageState("No presets yet", "Save your current look to make one.", icon = "palette")
    } else {
        FlowRow(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            presets.forEach { meta -> androidx.compose.runtime.key(meta.id) { PresetCard(meta) } }
        }
    }
}

@Composable
private fun PresetCard(meta: Presets.Meta) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val menu = rememberMenuAnchor()
    val thumbnail by produceState<ImageBitmap?>(null, meta.id) {
        value = withContext(Dispatchers.IO) {
            Presets.thumbnail(meta.id)?.let { runCatching { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }.getOrNull() }
        }
    }
    Column(
        Modifier
            .width(232.dp)
            .hoverable(interaction)
            .onSecondaryClick(menu.openAtProvider())
            .pressable({ applyPresetDialog(meta) }, interaction = interaction),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(145.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1C1C1E))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val image = thumbnail
            if (image != null) {
                // The phone's thumbnails are of a tall player; those fit rather than fill.
                Image(
                    image,
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = if (image.height > image.width) ContentScale.Fit else ContentScale.Crop,
                )
            } else {
                ConvxIcon("palette", tint = ConvxColors.SecondaryLabel, size = 36.dp)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                if (hovered || menu.expanded) {
                    IconButton("more_horiz", {
                        menu.offset = DpOffset(0.dp, 34.dp)
                        menu.expanded = true
                    }, size = 30.dp, iconSize = 18.dp, filled = true)
                }
                AppMenu(menu) { PresetMenu(meta) { menu.expanded = false } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(meta.name, style = ConvxType.BodyStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (meta.fromPhone) "From the phone · ${parts(meta.categories)}" else parts(meta.categories),
            style = ConvxType.Subhead,
            color = ConvxColors.SecondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun parts(categories: Set<PresetCategory>): String =
    if (categories.size == PresetCategory.entries.size) "Everything" else categories.joinToString(", ") { it.label }

@Composable
private fun PresetMenu(meta: Presets.Meta, dismiss: () -> Unit) {
    MenuItem("Apply", "check") {
        dismiss()
        applyPresetDialog(meta)
    }
    MenuItem("Rename", "edit") {
        dismiss()
        Overlays.prompt("Rename preset", meta.name, confirm = "Rename") { name ->
            AppScope.launch(Dispatchers.IO) { Presets.rename(meta.id, name) }
        }
    }
    MenuItem("Share preset", "share") {
        dismiss()
        exportPreset(meta)
    }
    MenuItem("Delete", "delete", destructive = true) {
        dismiss()
        Overlays.confirm("Delete \"${meta.name}\"?", "The preset is removed from this PC. Files you shared keep working.", "Delete") {
            AppScope.launch(Dispatchers.IO) { Presets.delete(meta.id) }
        }
    }
}

/** The phone's apply dialog: only the parts the preset holds, all ticked; what's left unticked stays as it is. */
private fun applyPresetDialog(meta: Presets.Meta) {
    Overlays.showDialog {
        var selected by remember(meta.id) { mutableStateOf(meta.categories) }
        DialogCard(meta.name) {
            Text(
                "Choose which parts of this preset to apply. Anything you leave unticked stays as it is now.",
                style = ConvxType.Body,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(12.dp))
            CategoryChecks(selected, meta.categories) { selected = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Apply", {
                    if (selected.isEmpty()) return@PillButton
                    val chosen = selected
                    Overlays.dismissDialog()
                    AppScope.launch(Dispatchers.IO) {
                        val message = runCatching { Presets.apply(meta.id, chosen) }.getOrElse { "Couldn't apply this preset: ${it.message}" }
                        Overlays.toast(message)
                    }
                }, filled = true)
            }
        }
    }
}

/**
 * Names the preset and picks its parts, over a small copy of the player that becomes its card's
 * picture, as the phone's dialog does with its mockup. [autoSaveAs] saves by itself, for the
 * screenshots the build takes.
 */
internal fun savePresetDialog(autoSaveAs: String? = null) {
    Overlays.showDialog {
        val settings by AppSettings.state.collectAsState()
        val player by PlayerController.state.collectAsState()
        var name by remember { mutableStateOf(autoSaveAs.orEmpty()) }
        var categories by remember { mutableStateOf(PresetCategory.entries.toSet()) }
        var saving by remember { mutableStateOf(false) }
        val layer = rememberGraphicsLayer()
        val scope = rememberCoroutineScope()
        fun save() {
            val title = name.trim().take(60)
            if (saving || title.isEmpty() || categories.isEmpty()) return
            saving = true
            val chosen = categories
            scope.launch {
                // The preview as it is on screen now, covers loaded, while the dialog is still up.
                val png = runCatching {
                    SkiaImage.makeFromBitmap(layer.toImageBitmap().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
                }.getOrNull()
                Overlays.dismissDialog()
                AppScope.launch(Dispatchers.IO) {
                    runCatching { Presets.capture(title, chosen, png) }
                        .onSuccess { Overlays.toast("Preset saved") }
                        .onFailure { Overlays.toast("Couldn't save the preset: ${it.message}", "error") }
                }
            }
        }
        if (autoSaveAs != null) {
            LaunchedEffect(Unit) {
                delay(2_500)
                save()
            }
        }
        DialogCard("Save current look") {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                ScaledStage(372.dp, settings, player)
            }
            Spacer(Modifier.height(14.dp))
            NameField(name, { name = it }, onDone = ::save)
            Spacer(Modifier.height(14.dp))
            Text("What it keeps", style = ConvxType.SubheadStrong, color = ConvxColors.SecondaryLabel)
            Spacer(Modifier.height(6.dp))
            CategoryChecks(categories, PresetCategory.entries.toSet()) { categories = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton(if (saving) "Saving…" else "Save", { save() }, filled = true)
            }
        }
    }
}

@Composable
private fun NameField(text: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    DisposableEffect(Unit) { onDispose { UiState.typingFocusChanged("preset-name", false) } }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (text.isEmpty()) Text("Preset name", style = ConvxType.Body, color = ConvxColors.TertiaryLabel)
        BasicTextField(
            value = text,
            onValueChange = { onChange(it.take(60)) },
            singleLine = true,
            textStyle = ConvxType.Body.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { UiState.typingFocusChanged("preset-name", it.isFocused) },
        )
    }
}

/** The phone's checklist of a preset's parts; parts the preset doesn't hold aren't offered. */
@Composable
private fun CategoryChecks(selected: Set<PresetCategory>, available: Set<PresetCategory>, onChange: (Set<PresetCategory>) -> Unit) {
    val accent = LocalAccent.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PresetCategory.entries.filter { it in available }.forEach { category ->
            val on = category in selected
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f))
                    .pressable({ onChange(if (on) selected - category else selected + category) })
                    .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (on) accent else Color.Transparent)
                        .border(1.5.dp, if (on) accent else ConvxColors.SecondaryLabel, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) ConvxIcon("check", tint = Color.White, size = 12.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text(category.label, style = ConvxType.Subhead, color = Color.White)
            }
        }
    }
}

private fun importPreset() {
    val file = pickFile("Open a Convx preset", listOf(Presets.Extension, "zip")) ?: return
    AppScope.launch(Dispatchers.IO) {
        when (val result = Presets.import(file)) {
            is Presets.ImportResult.Ok -> Overlays.toast(result.note?.let { "Preset added. $it" } ?: "Preset added")
            is Presets.ImportResult.Failed -> Overlays.toast(result.message, "error")
        }
    }
}

/** Windows' save dialog for the shared file, named as the phone names it. */
private fun exportPreset(meta: Presets.Meta) {
    val picker = FileDialog(null as Frame?, "Share preset", FileDialog.SAVE).apply {
        file = Presets.fileName(meta)
        isVisible = true
    }
    val name = picker.file ?: return
    val target = File(picker.directory, if (name.endsWith(".${Presets.Extension}", ignoreCase = true)) name else "$name.${Presets.Extension}")
    AppScope.launch(Dispatchers.IO) {
        runCatching { Presets.export(meta, target) }
            .onSuccess { Overlays.toast("Saved ${target.name}. Send it to your phone or another PC to use it there.") }
            .onFailure { Overlays.toast("Couldn't save the preset: ${it.message}", "error") }
    }
}
