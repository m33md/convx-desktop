/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.convx.desktop.integrations.party.Party
import com.convx.desktop.integrations.party.PartyMember
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.Overlays
import com.convx.desktop.ui.components.PillButton
import com.convx.desktop.ui.screens.settings.ButtonRow
import com.convx.desktop.ui.screens.settings.DialogField
import com.convx.desktop.ui.screens.settings.InfoNote
import com.convx.desktop.ui.screens.settings.RowDivider
import com.convx.desktop.ui.screens.settings.SectionGap
import com.convx.desktop.ui.screens.settings.SettingsSection
import com.convx.desktop.ui.screens.settings.SwitchRow
import com.convx.desktop.ui.screens.settings.SystemColors
import com.convx.desktop.ui.shapes.ContinuousRoundedRectangle
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxType

private val CardShape = ContinuousRoundedRectangle(26.dp)

/**
 * The phone's Party page (JamScreen): start a party or join one with a code, and once in, the
 * code to share, what's playing, who's here and whether only the host drives the music.
 */
@Composable
fun PartyScreen() {
    val session by Party.session.collectAsState()
    val busy by Party.busy.collectAsState()
    val message by Party.message.collectAsState()
    // Wake a sleeping server while the name and code are typed.
    LaunchedEffect(Unit) { Party.warm() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PageList(tint = Color.Black) {
            item("party") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        Text("Party", style = ConvxType.LargeTitle, color = ConvxColors.Label, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                        Text(
                            "Start a party with a code. Everyone who joins hears the same song at the same moment, kept in step by the server clock.",
                            style = ConvxType.Body,
                            color = ConvxColors.SecondaryLabel,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 20.dp),
                        )
                        message?.let {
                            Text(it, style = ConvxType.Body, color = SystemColors.Red, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
                        }
                        val current = session
                        if (current == null) StartParty(busy) else InParty(current)
                    }
                }
            }
        }
    }
}

@Composable
private fun StartParty(busy: Boolean) {
    var name by remember { mutableStateOf(Party.displayName) }
    var code by remember { mutableStateOf("") }
    val usable = name.isNotBlank() && !busy

    Card {
        DialogField("Your name", name) { name = it.take(80) }
        Spacer(Modifier.height(14.dp))
        PillButton(
            if (busy) "Starting…" else "Create party",
            {
                if (usable) {
                    Party.create(name)
                }
            },
            icon = "add",
            filled = usable,
        )
    }
    Spacer(Modifier.height(14.dp))
    Card {
        // A phone's invite link works here too; only a bare code is shown in capitals.
        DialogField("Party code or invite link", code) { code = if ("://" in it) it.trim() else it.uppercase().take(12) }
        Spacer(Modifier.height(14.dp))
        PillButton(
            if (busy) "Joining…" else "Join party",
            {
                if (usable && code.isNotBlank()) {
                    Party.join(code, name)
                }
            },
            icon = "group",
            filled = usable && code.isNotBlank(),
        )
    }
    Spacer(Modifier.height(16.dp))
    InfoNote(
        "Phones with Convx can join a party started here, and this PC can join theirs with the same codes. " +
            "A phone's invite link opens Convx on a phone; on this PC, paste it above. Songs from files on this PC can't be shared, " +
            "because the others don't have them.",
    )
}

@Composable
private fun InParty(session: Party.Session) {
    val clipboard = LocalClipboardManager.current

    Card(horizontal = Alignment.CenterHorizontally) {
        Text("Party code", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
        Text(
            session.code,
            style = ConvxType.LargeTitle.copy(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp),
            color = Color.White,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (session.connected) SystemColors.Green else SystemColors.Orange))
            Spacer(Modifier.width(6.dp))
            Text(if (session.connected) "Connected" else "Reconnecting…", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Copy code", {
                clipboard.setText(AnnotatedString(session.code))
                Overlays.toast("Code copied", "content_copy")
            }, icon = "content_copy")
            PillButton("Copy invite", {
                clipboard.setText(AnnotatedString("Join my Convx party: ${Party.inviteLink(session.code)} (code ${session.code})"))
                Overlays.toast("Invite copied", "link")
            }, icon = "share", filled = true)
        }
    }

    session.playback.track?.let { track ->
        SectionGap()
        SettingsSection("Now playing") {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.thumbnailUrl, Modifier.size(52.dp), size = 120, shape = ContinuousRoundedRectangle(10.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = ConvxType.BodyStrong, color = ConvxColors.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(track.artist.ifBlank { null }, session.playback.startedByName?.let { "Picked by $it" }).joinToString(" · "),
                        style = ConvxType.Subhead,
                        color = ConvxColors.SecondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ConvxIcon(if (session.playback.isPlaying) "pause" else "play", tint = ConvxColors.SecondaryLabel, size = 18.dp)
            }
        }
    }

    SectionGap()
    SettingsSection("Members (${session.members.size} of ${session.maxMembers})") {
        session.members.forEachIndexed { index, member ->
            if (index > 0) RowDivider()
            MemberRow(member, you = member.memberId == session.you.memberId)
        }
    }

    SectionGap()
    if (session.you.isHost) {
        SettingsSection(footer = "Guests listen along but can't pause, skip or change the queue.") {
            SwitchRow("Only the host controls playback", null, session.hostOnlyControl, icon = "lock", tint = SystemColors.Indigo) {
                Party.setHostOnlyControl(it)
            }
        }
        SectionGap()
    } else if (!session.canControl) {
        InfoNote("The host has turned off playback controls for guests.")
        SectionGap()
    }
    SettingsSection {
        ButtonRow("Leave party", color = SystemColors.Red) { Party.leave() }
    }
}

@Composable
private fun MemberRow(member: PartyMember, you: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(ConvxColors.AccentRed.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(member.displayName.take(1).uppercase().ifEmpty { "?" }, style = ConvxType.BodyStrong, color = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                member.displayName.ifBlank { "Someone" } + if (you) " (you)" else "",
                style = ConvxType.Body,
                color = ConvxColors.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (member.isHost) Text("Host", style = ConvxType.Subhead, color = ConvxColors.SecondaryLabel)
        }
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (member.connected) SystemColors.Green else ConvxColors.TertiaryLabel))
    }
}

@Composable
private fun Card(horizontal: Alignment.Horizontal = Alignment.Start, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(ConvxColors.Card).padding(20.dp),
        horizontalAlignment = horizontal,
    ) { content() }
}
