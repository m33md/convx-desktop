/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.convx.desktop.core.AppScope
import com.convx.desktop.data.AppSettings
import com.convx.desktop.player.ModuleStreams
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.DialogCard
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.glass.GlassSwitch
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType
import com.music.spine.EclipseAddon
import com.music.spine.ModuleManager
import com.music.spine.ModuleSetting
import com.music.spine.SpineModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** What the Modules pages show while they work: each source's last check, and the module open in detail. */
object ModulesUi {
    val sourceStatus = mutableStateMapOf<String, String>()
    val checking = mutableStateMapOf<String, Boolean>()
    var fetching by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** The module the detail page shows. */
    var openId by mutableStateOf<String?>(null)

    fun open(id: String) {
        openId = id
        SettingsNav.push(SettingsPage.ModuleDetail)
    }
}

/** The phone's own quality list, for modules that don't declare theirs. */
private val QualityOptions = listOf(
    "LOSSLESS" to "Lossless (FLAC 16-bit/44.1kHz)",
    "HIRES" to "Hi-Res (FLAC 24-bit)",
    "EAC3_JOC" to "Dolby Atmos (EAC3-JOC)",
    "HIGH" to "High (AAC 320kbps)",
    "LOW" to "Low (AAC 96kbps)",
)

private val ModuleRowHeight = 60.dp

/** A module's settings or set changed: its engine starts afresh, and songs it missed are asked again. */
private fun changed(moduleId: String? = null) {
    if (moduleId != null) ModuleManager.shared.unloadModule(moduleId) else ModuleManager.shared.unloadAll()
    ModuleStreams.forget()
}

private fun toggleModule(id: String) {
    AppSettings.update { s ->
        // Switching one on puts it last, lowest priority until it's dragged up.
        s.copy(enabledModules = if (id in s.enabledModules) s.enabledModules - id else s.enabledModules + id)
    }
    changed(id)
}

/**
 * The phone's Modules page: the sources modules come from, the modules that are on in the order
 * they're asked, and the rest a source offers.
 */
@Composable
fun ModulesSettings() {
    val settings by AppSettings.state.collectAsState()
    val enabled = settings.enabledModules
    val byId = remember(settings.fetchedModules) { settings.fetchedModules.associateBy { it.id } }
    val available = settings.fetchedModules.filter { it.id !in enabled }

    InfoNote(
        "A module finds songs on another music service, often in lossless FLAC. With modules on, each song is " +
            "looked for in them first, in the order below, and plays from YouTube when none has it. Add a source: " +
            "the address of an 8spine module index, or of an Eclipse addon's manifest. Modules run code from " +
            "their source, so add only sources you trust.",
    )
    SectionGap()
    SettingsSection("Sources") {
        settings.moduleSources.forEachIndexed { index, url ->
            if (index > 0) RowDivider(PlainInset)
            SourceRow(url)
        }
        if (settings.moduleSources.isNotEmpty()) RowDivider(PlainInset)
        SettingsRow("Add source", titleColor = SystemColors.Blue, onClick = { addSourceDialog() }, leading = {
            ConvxIcon("add", tint = SystemColors.Blue, size = 20.dp)
        })
    }
    if (settings.moduleSources.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SettingsSection {
            ButtonRow(if (ModulesUi.fetching) "Refreshing modules…" else "Fetch modules", enabled = !ModulesUi.fetching) { fetchAll() }
        }
    }
    ModulesUi.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = ConvxType.Subhead, color = SystemColors.Red, modifier = Modifier.padding(horizontal = 20.dp))
    }
    if (enabled.isNotEmpty()) {
        SectionGap()
        EnabledModules(enabled, byId)
    }
    if (available.isNotEmpty()) {
        SectionGap()
        SettingsSection("Available modules") {
            available.forEachIndexed { index, module ->
                if (index > 0) RowDivider(66.dp)
                SettingsRow(
                    module.name,
                    listOf(
                        listOfNotNull(module.author.takeIf { it.isNotBlank() }, module.version.takeIf { it.isNotBlank() }?.let { "v$it" }).joinToString(" "),
                        module.description,
                    ).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null },
                    leading = { ModuleLogo(module) },
                    onClick = { ModulesUi.open(module.id) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (module.isLossless) {
                            LosslessTag()
                            Spacer(Modifier.width(10.dp))
                        }
                        GlassSwitch(false, { toggleModule(module.id) })
                    }
                }
            }
        }
    }
    if (settings.fetchedModules.isEmpty() && settings.moduleSources.isNotEmpty() && !ModulesUi.fetching) {
        Spacer(Modifier.height(16.dp))
        Text(
            "No modules found from the added sources.",
            style = ConvxType.Subhead,
            color = ConvxColors.SecondaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    SectionGap()
    InfoNote(
        "On a PC, a module's song is saved as it plays like any other, so only answers that are one audio file " +
            "play: a hi-res stream sent as DASH or HLS falls back to the module's CD quality. Dolby Atmos is left " +
            "for stereo. Downloads always come from YouTube, as on the phone.",
    )
}

@Composable
private fun SourceRow(url: String) {
    val status = ModulesUi.sourceStatus[url]
    SettingsRow(
        url.substringAfter("://").substringBefore('/').ifBlank { url },
        listOfNotNull(url, status).joinToString("\n"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (ModulesUi.checking[url] == true) "Checking…" else "Check", { checkSource(url) })
            PillButton("Remove", { removeSource(url) })
        }
    }
}

/** Enabled modules, dragged into the order they're asked in, as the phone's priority list. */
@Composable
private fun EnabledModules(enabled: List<String>, byId: Map<String, SpineModule>) {
    val order = remember { mutableStateListOf<String>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    // Follows Settings, except while a row is held, so a drag isn't undone under the pointer.
    LaunchedEffect(enabled, dragging) {
        if (dragging == null && order.toList() != enabled) {
            order.clear()
            order.addAll(enabled)
        }
    }
    val rowPx = with(LocalDensity.current) { ModuleRowHeight.toPx() }
    fun drop() {
        if (dragging != null && order.toList() != AppSettings.value.enabledModules) {
            AppSettings.update { it.copy(enabledModules = order.toList()) }
            ModuleStreams.forget()
        }
        dragging = null
        offset = 0f
    }
    SettingsSection("Enabled, drag to set priority", footer = "The first module that has a song plays it.") {
        Column(
            Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { at ->
                        // The remove button keeps its own clicks; anywhere else on a row picks it up.
                        dragging = if (at.x < size.width - 110.dp.toPx()) order.getOrNull((at.y / rowPx).toInt()) else null
                        offset = 0f
                    },
                    onDragEnd = { drop() },
                    onDragCancel = { drop() },
                ) { change, amount ->
                    val id = dragging ?: return@detectDragGestures
                    change.consume()
                    offset += amount.y
                    val from = order.indexOf(id)
                    if (offset > rowPx / 2 && from < order.lastIndex) {
                        order.add(from + 1, order.removeAt(from))
                        offset -= rowPx
                    } else if (offset < -rowPx / 2 && from > 0) {
                        order.add(from - 1, order.removeAt(from))
                        offset += rowPx
                    }
                }
            },
        ) {
            order.forEachIndexed { index, id ->
                key(id) {
                    val held = dragging == id
                    val module = byId[id]
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ModuleRowHeight)
                            .zIndex(if (held) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (held) offset else 0f
                                shadowElevation = if (held) 16.dp.toPx() else 0f
                            }
                            .background(if (held) ConvxColors.Card else Color.Transparent),
                    ) {
                        SettingsRow(
                            module?.name ?: id,
                            module?.author?.takeIf { it.isNotBlank() } ?: if (module == null) "Not in any source now" else null,
                            leading = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ConvxIcon("drag_handle", tint = ConvxColors.TertiaryLabel, size = 20.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("${index + 1}", style = ConvxType.BodyStrong, color = SystemColors.Blue, modifier = Modifier.width(20.dp))
                                    if (module != null) ModuleLogo(module)
                                }
                            },
                            onClick = { ModulesUi.open(id) },
                        ) {
                            PillButton("Turn off", { toggleModule(id) })
                        }
                        if (index < order.lastIndex && !held) {
                            Box(Modifier.align(Alignment.BottomStart)) { RowDivider(48.dp) }
                        }
                    }
                }
            }
        }
    }
}

/** A module's picture, or its first letter on grey, as the phone draws it. */
@Composable
private fun ModuleLogo(module: SpineModule) {
    val image = module.logo?.takeIf(String::isNotBlank) ?: module.icon?.takeIf(String::isNotBlank)
    Box(
        Modifier.size(36.dp).clip(ContinuousRoundedRectangle(9.dp)).background(ConvxColors.CardTertiary),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Artwork(image, Modifier.size(36.dp), size = 96, shape = ContinuousRoundedRectangle(9.dp), contentScale = ContentScale.Fit)
        } else {
            Text(module.name.take(1).uppercase(), style = ConvxType.BodyStrong, color = ConvxColors.SecondaryLabel)
        }
    }
}

@Composable
private fun LosslessTag() {
    Text(
        "LOSSLESS",
        style = ConvxType.Footnote,
        color = SystemColors.Blue,
        modifier = Modifier.clip(ContinuousRoundedRectangle(5.dp)).background(SystemColors.Blue.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun checkSource(url: String) {
    if (ModulesUi.checking[url] == true) return
    ModulesUi.checking[url] = true
    AppScope.launch(Dispatchers.IO) {
        try {
            val result = ModuleManager.shared.fetchIndex(url, refresh = true)
            ModulesUi.sourceStatus[url] = result.fold(
                onSuccess = { if (it.isEmpty()) "Source answered, but has no modules" else pluralize(it.size, "module") + " available" },
                onFailure = { "Could not reach this source" + (it.message?.let { m -> ": $m" } ?: "") },
            )
        } finally {
            ModulesUi.checking[url] = false
        }
    }
}

private fun pluralize(count: Int, word: String) = "$count ${if (count == 1) word else word + "s"}"

/** Asks every source again; a source that fails keeps what it offered before. */
private fun fetchAll() {
    if (ModulesUi.fetching) return
    ModulesUi.fetching = true
    ModulesUi.error = null
    AppScope.launch(Dispatchers.IO) {
        try {
            val sources = AppSettings.value.moduleSources
            val results = coroutineScope {
                sources.map { url -> async { url to ModuleManager.shared.fetchIndex(url, refresh = true) } }.awaitAll()
            }
            results.forEach { (url, result) ->
                ModulesUi.sourceStatus[url] = result.fold(
                    onSuccess = { pluralize(it.size, "module") + " available" },
                    onFailure = { "Could not reach this source" },
                )
            }
            val failed = results.any { it.second.isFailure }
            if (failed) ModulesUi.error = "Some sources could not be refreshed. Previously fetched modules were kept."
            val fresh = results.flatMap { it.second.getOrNull().orEmpty() }
            AppSettings.update { s ->
                s.copy(fetchedModules = (fresh + if (failed) s.fetchedModules else emptyList()).distinctBy { it.id })
            }
            changed()
        } finally {
            ModulesUi.fetching = false
        }
    }
}

private fun removeSource(url: String) {
    AppSettings.update { s ->
        val removed = s.fetchedModules.filter {
            it.sourceUrl == url || (it.type == EclipseAddon.TYPE && it.download == EclipseAddon.baseUrl(url))
        }.mapTo(hashSetOf()) { it.id }
        s.copy(
            moduleSources = s.moduleSources - url,
            enabledModules = s.enabledModules.filterNot { it in removed },
            fetchedModules = s.fetchedModules.filterNot { it.id in removed },
        )
    }
    ModulesUi.sourceStatus.remove(url)
    changed()
}

/** The phone's Add Module Source dialog: the source's modules are added and switched on. */
private fun addSourceDialog() {
    Overlays.showDialog {
        var url by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        DialogCard("Add module source") {
            Text(
                "The address of an 8spine module index (a .json file), or of an Eclipse addon's manifest.",
                style = ConvxType.Subhead,
                color = ConvxColors.SecondaryLabel,
            )
            Spacer(Modifier.height(14.dp))
            DialogField("Source URL", url) { url = it }
            problem?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = ConvxType.Subhead, color = SystemColors.Red)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton(if (busy) "Adding…" else "Add", {
                    val source = url.trim().trimEnd('/')
                    if (source.isNotBlank() && !busy) {
                        busy = true
                        problem = null
                        AppScope.launch(Dispatchers.IO) {
                            try {
                                val modules = ModuleManager.shared.fetchIndex(source, refresh = true).getOrThrow()
                                require(modules.isNotEmpty()) { "No addons or modules found at that URL" }
                                AppSettings.update { s ->
                                    val known = s.fetchedModules.mapTo(hashSetOf()) { it.id }
                                    s.copy(
                                        moduleSources = (s.moduleSources + source).distinct(),
                                        fetchedModules = (modules + s.fetchedModules).distinctBy { it.id },
                                        enabledModules = (s.enabledModules + modules.filterNot { it.id in known }.map { it.id }).distinct(),
                                    )
                                }
                                ModulesUi.sourceStatus[source] = pluralize(modules.size, "module") + " available"
                                changed()
                                Overlays.dismissDialog()
                                Overlays.toast("Added ${pluralize(modules.size, "module")}")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                problem = e.message ?: "Unable to add this source"
                            } finally {
                                busy = false
                            }
                        }
                    }
                }, filled = url.isNotBlank() && !busy)
            }
        }
    }
}

/**
 * The phone's Module Detail page: the switch, what the module is, a check that it loads, its
 * quality and whatever settings it declares.
 */
@Composable
fun ModuleDetailSettings() {
    val id = ModulesUi.openId ?: return
    val settings by AppSettings.state.collectAsState()
    val module = settings.fetchedModules.firstOrNull { it.id == id }
    var runtime by remember(id) { mutableStateOf<ModuleManager.LoadedModule?>(null) }
    var checking by remember(id) { mutableStateOf(false) }
    var status by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(module) {
        val descriptor = module?.takeIf { it.download.startsWith("https://") || it.type == EclipseAddon.TYPE } ?: return@LaunchedEffect
        runtime = kotlinx.coroutines.withContext(Dispatchers.IO) { ModuleManager.shared.loadModule(descriptor).getOrNull() }
    }
    val values = settings.moduleSettings[id].orEmpty()
    fun set(key: String, value: String) {
        AppSettings.update { s ->
            s.copy(moduleSettings = s.moduleSettings + (id to (s.moduleSettings[id].orEmpty() + (key to value))))
        }
        changed(id)
    }
    val isEnabled = id in settings.enabledModules

    SettingsSection {
        SwitchRow("Enable module", checked = isEnabled) { toggleModule(id) }
    }
    SectionGap()
    SettingsSection("Module info") {
        if (module != null) {
            SettingsRow(module.name, listOf(module.author, module.version).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }, leading = {
                ModuleLogo(module)
            }) { if (module.isLossless) LosslessTag() }
            RowDivider(PlainInset)
        }
        SettingsRow("Module ID") { Text(id, style = ConvxType.Body, color = ConvxColors.SecondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        RowDivider(PlainInset)
        SettingsRow("Status") {
            Text(if (isEnabled) "Enabled" else "Disabled", style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
        }
        if (module != null && (module.download.startsWith("https://") || module.type == EclipseAddon.TYPE)) {
            RowDivider(PlainInset)
            SettingsRow(if (checking) "Checking…" else "Check", status, titleColor = SystemColors.Blue, enabled = !checking, onClick = {
                checking = true
                AppScope.launch(Dispatchers.IO) {
                    try {
                        val result = ModuleManager.shared.loadModule(module)
                        runtime = result.getOrNull()
                        status = result.fold({ "Ready to search" }, { "Could not load this module" + (it.message?.let { m -> ": $m" } ?: "") })
                    } finally {
                        checking = false
                    }
                }
            })
        }
    }
    module?.description?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel, modifier = Modifier.padding(horizontal = 20.dp))
    }
    if (module != null && module.tags.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            module.tags.forEach { tag ->
                Text(
                    tag,
                    style = ConvxType.Caption,
                    color = ConvxColors.SecondaryLabel,
                    modifier = Modifier.clip(ContinuousRoundedRectangle(5.dp)).background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
    SectionGap()
    val declared = runtime?.manifest?.settings.orEmpty()
    val quality = declared["quality"]
    val qualityOptions = quality?.options?.takeIf { it.isNotEmpty() }?.map { it.value to it.label.ifBlank { it.value } } ?: QualityOptions
    val qualityDefault = runtime?.manifest?.defaults?.get("quality").orEmpty()
    SettingsSection("Quality settings", footer = quality?.description?.takeIf { it.isNotBlank() }) {
        ChoiceRow(
            quality?.label?.takeIf { it.isNotBlank() } ?: "Primary quality",
            options = listOf("" to "Default" + (qualityOptions.firstOrNull { it.first == qualityDefault }?.let { " (${it.second})" } ?: "")) + qualityOptions,
            selected = values["quality"].orEmpty(),
        ) { set("quality", it) }
        declared["tidalQuality"]?.let { tidal ->
            RowDivider(PlainInset)
            val options = tidal.options.takeIf { it.isNotEmpty() }?.map { it.value to it.label.ifBlank { it.value } } ?: QualityOptions
            ChoiceRow(tidal.label.ifBlank { "Tidal fallback quality" }, options = listOf("" to "Default") + options, selected = values["tidalQuality"].orEmpty()) {
                set("tidalQuality", it)
            }
        }
        declared.filterKeys { it != "quality" && it != "tidalQuality" }.forEach { (key, setting) ->
            RowDivider(PlainInset)
            DeclaredSetting(setting, values[key] ?: runtime?.manifest?.defaults?.get(key).orEmpty()) { set(key, it) }
        }
    }
    if (runtime == null && module != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            "The module's own settings show once it has loaded.",
            style = ConvxType.Subhead,
            color = ConvxColors.SecondaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/** A setting a module declares: a list to pick from, a switch, or text (hidden for a password). */
@Composable
private fun DeclaredSetting(setting: ModuleSetting, value: String, onChange: (String) -> Unit) {
    val label = setting.label.ifBlank { "Setting" }
    val description = setting.description.takeIf { it.isNotBlank() }
    when {
        setting.options.isNotEmpty() -> ChoiceRow(label, description, setting.options.map { it.value to it.label.ifBlank { it.value } }, value) { onChange(it) }
        setting.type.lowercase() in setOf("boolean", "switch", "toggle") -> SwitchRow(label, description, value == "true") { onChange(it.toString()) }
        else -> {
            val secret = setting.type.equals("password", true)
            LinkRow(label, description, value = if (value.isBlank()) "Not set" else if (secret) "Set" else value.take(28)) {
                textDialog(label, value, secret, onChange)
            }
        }
    }
}

private fun textDialog(label: String, current: String, secret: Boolean, onSave: (String) -> Unit) {
    Overlays.showDialog {
        var text by remember { mutableStateOf(current) }
        DialogCard(label) {
            DialogField(label, text, secret = secret) { text = it }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Cancel", { Overlays.dismissDialog() })
                PillButton("Save", {
                    onSave(text.trim())
                    Overlays.dismissDialog()
                }, filled = true)
            }
        }
    }
}
