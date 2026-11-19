package app.ciranda.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ciranda.android.AppState
import app.ciranda.android.Channel
import app.ciranda.android.Engine
import app.ciranda.android.Event
import app.ciranda.android.MainActivity
import app.ciranda.android.Member
import app.ciranda.android.SavedServer
import app.ciranda.android.SessionState
import app.ciranda.android.Updater
import app.ciranda.android.VoiceLink
import app.ciranda.android.VoiceState
import kotlinx.coroutines.launch

/** What is over the chat, if anything: one sheet at a time, so that two can never be up together. */
private enum class Sheet { None, Channels, People, Voice, Settings }

private fun Sheet.tab(): Tab? = when (this) {
    Sheet.Channels -> Tab.Channels
    Sheet.People -> Tab.People
    Sheet.Voice -> Tab.Voice
    Sheet.Settings -> Tab.Settings
    Sheet.None -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServerScreen(activity: MainActivity, state: AppState, s: SessionState) {
    val p = LocalPalette.current
    // Not read here: the call changes many times a second while people talk, and what draws it
    // reads it itself, so the rest of the screen is left alone.
    val voice = Engine.voice.collectAsState()
    val inVoice by remember { derivedStateOf { voice.value != null } }
    val chat by Engine.chat.collectAsState()
    var sheet by rememberSaveable { mutableStateOf(Sheet.None) }
    // Over the sheets: a voice channel looked at before joining, and someone's profile.
    var room by rememberSaveable { mutableStateOf<Long?>(null) }
    var profile by rememberSaveable { mutableStateOf<Long?>(null) }
    var askPassword by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    val privates = rememberPrivate(activity, s)

    LaunchedEffect(Unit) {
        Engine.events.collect { e ->
            if (e is Event.NeedsPassword) askPassword = e.channelId to e.wrong
        }
    }
    // The call over (left, or dropped by someone): its controls have nothing to show.
    LaunchedEffect(inVoice) { if (!inVoice && sheet == Sheet.Voice) sheet = Sheet.None }
    // A channel on screen has nothing left to tell in a notification.
    val resumed by activity.resumes.collectAsState()
    LaunchedEffect(s.url, s.me.nickname, s.openChannel, resumed) {
        s.openChannel?.let { app.ciranda.android.Notifier.seen(activity, s.url, s.me.nickname, it) }
    }
    val intent by activity.pending.collectAsState()
    LaunchedEffect(intent) {
        val i = intent ?: return@LaunchedEffect
        // The engine opens the channel (`MainActivity.takeMention`); the chat shows it.
        if (i.action == app.ciranda.android.Notifier.OPEN) sheet = Sheet.None
        if (i.getBooleanExtra("update", false)) sheet = Sheet.Settings
        activity.pending.value = null
    }

    // Back sends Ciranda to the background as chat apps do, rather than closing it: a call goes
    // on either way. The sheets take Back for themselves while open.
    androidx.activity.compose.BackHandler { activity.moveTaskToBack(true) }

    val openProfile = { id: Long -> profile = id }
    val showRoom = { c: Channel -> room = c.id }
    val joinVoice = { c: Channel ->
        if (voice.value?.channelId != c.id) activity.join(c.id)
        room = null
        profile = null
        sheet = Sheet.Voice
    }
    val switch = { target: SavedServer? ->
        activity.leavingCall {
            sheet = Sheet.None
            if (target == null) Engine.send("switch") else Engine.send("resume", "url" to target.url, "username" to target.username)
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        val conversation = privates.open
        val channel = s.channel(s.openChannel)
        if (conversation != null) {
            PrivateTopBar(privates, s, onProfile = openProfile)
        } else {
            TopBar(channel = channel, title = channel?.name ?: s.name, subtitle = if (!s.up) tr("Reconnecting…", "Reconectando…") else null)
        }
        UpdateBanner(activity)
        Box(Modifier.weight(1f)) {
            val c = chat
            when {
                conversation != null -> PrivateChat(activity, privates, s, onProfile = openProfile)
                channel != null && c != null && c.channelId == channel.id ->
                    // Each channel's chat comes in on its own, rather than replacing the last in one frame.
                    key(channel.id) { FadeIn { ChatView(activity, s, c, Place(channel.id, false, channel.name), onProfile = openProfile) } }
                channel != null || s.channels.isEmpty() ->
                    // The channel is on its way (or, with no channels yet, the server's list is).
                    // Shown only if it takes a moment: a quick one has nothing to spin for.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                else -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(tr("Pick a channel.", "Escolha um canal."), color = p.muted)
                }
            }
        }
        VoiceBarHost(voice, onOpen = { if (voice.value?.private == true) activity.openCall() else sheet = Sheet.Voice })
        when {
            conversation != null -> PrivateComposer(activity, privates, s)
            channel != null && !channel.voice -> Composer(activity, s, Place(channel.id, false, channel.name))
        }
        // While typing, the keyboard has the bottom: the bar comes back when it goes.
        if (!WindowInsets.isImeVisible) {
            BottomBar(
                s, state.saved, sheet.tab(), inVoice, privates.unreadCount,
                onTab = { t ->
                    sheet = when (t) {
                        Tab.Channels -> Sheet.Channels
                        Tab.People -> Sheet.People
                        Tab.Voice -> Sheet.Voice
                        Tab.Settings -> Sheet.Settings
                    }
                },
                onSwitch = switch,
            )
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    when (sheet) {
        Sheet.Channels -> ModalBottomSheet({ sheet = Sheet.None }, sheetState = sheetState, containerColor = p.surface) {
            ChannelList(
                s, voice,
                onText = { c ->
                    privates.close()
                    Engine.send("open", "channelId" to c.id)
                    sheet = Sheet.None
                },
                onRoom = showRoom,
                onProfile = openProfile,
            )
        }
        Sheet.People -> ModalBottomSheet({ sheet = Sheet.None }, sheetState = sheetState, containerColor = p.surface) {
            PeopleSheet(
                s,
                conversations = { Conversations(privates, s) { sheet = Sheet.None } },
                onProfile = openProfile,
            )
        }
        Sheet.Voice -> ModalBottomSheet({ sheet = Sheet.None }, sheetState = sheetState, containerColor = p.surface) {
            // A private call has its own screen: the tab shows the rooms to go to instead.
            if (inVoice && voice.value?.private != true) VoiceSheet(voice, s, openProfile) else VoiceRooms(s, showRoom, openProfile)
        }
        Sheet.Settings -> SettingsScreen(activity, state, privates, onClose = { sheet = Sheet.None })
        Sheet.None -> {}
    }
    room?.let { id -> s.channel(id) }?.let { c ->
        ModalBottomSheet({ room = null }, containerColor = p.surface) {
            RoomSheet(c, s, voice, onJoin = { joinVoice(c) }, onControls = { room = null; sheet = Sheet.Voice }, onProfile = openProfile)
        }
    }
    profile?.let { id -> s.user(id) }?.let { u ->
        ModalBottomSheet({ profile = null }, containerColor = p.surface) {
            ProfileSheet(
                u, s, voice,
                canMessage = privates.available,
                onMessage = {
                    profile = null
                    room = null
                    sheet = Sheet.None
                    privates.openWith(u.id)
                },
                onRoom = { c -> profile = null; room = c.id },
            )
        }
    }
    askPassword?.let { (channelId, wrong) ->
        var pw by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        val join = {
            if (pw.isNotEmpty()) {
                askPassword = null
                activity.join(channelId, pw)
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = { askPassword = null },
            title = { Text(s.channel(channelId)?.name ?: "") },
            text = {
                Column {
                    Text(
                        if (wrong) tr("That password is wrong.", "Essa senha está errada.") else tr("This channel has a password.", "Este canal tem senha."),
                        color = if (wrong) p.danger else p.muted,
                        modifier = Modifier.semantics { if (wrong) liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordField(
                        pw, { pw = it }, tr("Password", "Senha"), Modifier.focusRequester(focus),
                        isError = wrong, imeAction = ImeAction.Done, keyboardActions = KeyboardActions(onDone = { join() }),
                    )
                }
            },
            confirmButton = { TextButton(join, enabled = pw.isNotEmpty()) { Text(tr("Join", "Entrar")) } },
            dismissButton = { TextButton({ askPassword = null }) { Text(tr("Cancel", "Cancelar")) } },
        )
    }
    privates.Dialogs()
}

@Composable
private fun TopBar(channel: Channel?, title: String, subtitle: String?) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.surface).statusBarsPadding().heightIn(min = 56.dp).bottomBorder(p.border).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The server's own name, shown while no channel is open, is not a channel's.
                if (channel != null) {
                    Icon(if (channel.locked) Icons.Filled.Lock else Icons.Filled.Tag, null, tint = p.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    title, style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = p.warning, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
private fun UpdateBanner(activity: MainActivity) {
    val p = LocalPalette.current
    val u by Updater.state.collectAsState()
    val visible = u is Updater.State.Available || u is Updater.State.Downloading || u is Updater.State.Ready
    // Slides in, pushing the chat down gently, rather than shoving it a row in one frame.
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val (text, action) = when (val st = u) {
            is Updater.State.Available -> tr("Ciranda ${st.release.version} is out.", "Ciranda ${st.release.version} está disponível.") to
                { Updater.download(activity, st.release) }
            is Updater.State.Downloading -> tr("Downloading ${(st.fraction * 100).toInt()}%…", "Baixando ${(st.fraction * 100).toInt()}%…") to null
            is Updater.State.Ready -> tr("Version ${st.release.version} is ready to install.", "A versão ${st.release.version} está pronta para instalar.") to
                { Updater.install(activity, st.file) }
            else -> return@AnimatedVisibility
        }
        Row(
            Modifier.fillMaxWidth().background(p.accent.copy(alpha = 0.18f)).padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = p.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            if (action != null) {
                TextButton(action) {
                    Text(if (u is Updater.State.Ready) tr("Install", "Instalar") else tr("Update", "Atualizar"))
                }
            }
        }
    }
}

/** The channels, in their groups, as the list will draw them: ordered once, not on every redraw. */
private class Section(val id: Long?, val name: String?, val channels: List<Channel>)

@Composable
private fun ChannelList(
    s: SessionState,
    voice: State<VoiceState?>,
    onText: (Channel) -> Unit,
    onRoom: (Channel) -> Unit,
    onProfile: (Long) -> Unit,
) {
    val p = LocalPalette.current
    val sections = remember(s.channels, s.groups) {
        val groups = s.groups.sortedBy { it.position }
        buildList {
            add(Section(null, null, s.channels.filter { c -> c.groupId == null || groups.none { g -> g.id == c.groupId } }.sortedBy { it.position }))
            for (g in groups) add(Section(g.id, g.name, s.channels.filter { it.groupId == g.id }.sortedBy { it.position }))
        }.filter { it.channels.isNotEmpty() }
    }
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "title") { SheetTitle(tr("Channels", "Canais"), s.name) }
        for (section in sections) {
            if (section.name != null) {
                // By id: two groups can have the same name, and a list refuses two of one key.
                item(key = "g${section.id}", contentType = "group") {
                    Text(
                        section.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.muted,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
            }
            items(section.channels, key = { it.id }, contentType = { if (it.voice) "voice" else "text" }) { c ->
                if (c.voice) {
                    VoiceChannelRow(c, s, voice, onRoom, onProfile)
                } else {
                    TextChannelRow(c, s, onText)
                }
            }
        }
        item(key = "end") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun TextChannelRow(c: Channel, s: SessionState, onText: (Channel) -> Unit) {
    val p = LocalPalette.current
    val open = s.openChannel == c.id
    val unread = c.id in s.unread
    val mentions = s.mentioned[c.id] ?: 0
    val unreadLabel = tr("unread", "não lida")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(8.dp))
            .background(if (open) p.raised else Color.Transparent)
            .selectable(selected = open, role = Role.Tab, onClick = { onText(c) })
            .semantics { if (unread && !open) stateDescription = unreadLabel }
            // 48dp with the margin around it: what a thumb can be expected to find.
            .heightIn(min = 46.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (c.locked) Icons.Filled.Lock else Icons.Filled.Tag, null, tint = p.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            c.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (open || unread) p.text else p.muted,
            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (mentions > 0) {
            val said = tr("$mentions mentions", "$mentions menções")
            Box(
                Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(p.danger).padding(horizontal = 6.dp)
                    .clearAndSetSemantics { contentDescription = said },
                contentAlignment = Alignment.Center,
            ) {
                Text("$mentions", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun VoiceChannelRow(c: Channel, s: SessionState, voice: State<VoiceState?>, onRoom: (Channel) -> Unit, onProfile: (Long) -> Unit) {
    val p = LocalPalette.current
    val v = voice.value
    val here = v?.channelId == c.id
    val roster = s.rosters[c.id].orEmpty()
    val look = tr("See the room", "Ver a sala")
    val speakingLabel = tr("speaking", "falando")
    val speakers = if (here) v.speaking else emptySet()
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(8.dp))
                .background(if (here) p.raised else Color.Transparent)
                .clickable(role = Role.Button, onClickLabel = look) { onRoom(c) }
                .semantics { selected = here }
                .heightIn(min = 46.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = if (here) p.success else p.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(c.name, modifier = Modifier.weight(1f), color = if (here) p.text else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.locked) Icon(Icons.Filled.Lock, tr("Has a password", "Tem senha"), tint = p.muted, modifier = Modifier.size(14.dp))
        }
        for (m in roster) {
            val u = s.user(m.userId)
            val speaking = m.userId in speakers
            Row(
                Modifier.fillMaxWidth().padding(start = 36.dp, end = 8.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = tr("Open profile", "Abrir perfil")) { onProfile(m.userId) }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .semantics(mergeDescendants = true) { if (speaking) stateDescription = speakingLabel },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(u?.name ?: m.nickname, u?.avatarHash, size = 24.dp, speaking = speaking)
                Spacer(Modifier.width(8.dp))
                Text(u?.name ?: m.nickname, color = p.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                MemberMarks(m)
            }
        }
    }
}

@Composable
fun MemberMarks(m: Member) {
    val p = LocalPalette.current
    if (m.deafened) {
        Icon(Icons.Filled.HeadsetOff, tr("Can't hear", "Não está ouvindo"), tint = p.danger, modifier = Modifier.size(16.dp))
    } else if (m.muted || m.forceMuted) {
        Icon(Icons.Filled.MicOff, tr("Muted", "Microfone desligado"), tint = p.danger, modifier = Modifier.size(16.dp))
    }
}

/** The call's bar under the chat: there while a call is, sliding in and out with it. */
@Composable
private fun VoiceBarHost(voice: State<VoiceState?>, onOpen: () -> Unit) {
    val v = voice.value
    // The last call is kept for the bar to show while it slides away: by then the call is gone.
    val last = remember { arrayOfNulls<VoiceState>(1) }
    if (v != null) last[0] = v
    val shown = v ?: last[0]
    AnimatedVisibility(v != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        shown?.let { VoiceBar(it, onOpen) }
    }
}

@Composable
private fun VoiceBar(v: VoiceState, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().background(p.surface).topBorder(p.border)
            .clickable(role = Role.Button, onClickLabel = tr("Voice controls", "Controles da voz"), onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val (label, color) = when {
                v.state == VoiceLink.Reconnecting -> tr("Reconnecting…", "Reconectando…") to p.warning
                v.private && v.ringing -> tr("Calling…", "Chamando…") to p.warning
                v.state == VoiceLink.Joining -> tr("Joining…", "Entrando…") to p.warning
                v.private -> tr("In a call", "Em chamada") to p.success
                else -> tr("Voice connected", "Voz conectada") to p.success
            }
            Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(v.channel, v.pingMs?.let { "$it ms" }, if (v.micFailed) tr("microphone not connected", "microfone sem conexão") else null)
                    .joinToString(" · "),
                color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton({
            haptic.performHapticFeedback(if (v.muted) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            Engine.send("mute", "on" to !v.muted)
        }) {
            Icon(
                if (v.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                if (v.muted) tr("Unmute", "Ativar microfone") else tr("Mute", "Mutar"),
                tint = if (v.muted) p.danger else p.text,
            )
        }
        IconButton({
            haptic.performHapticFeedback(if (v.deafened) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            Engine.send("deafen", "on" to !v.deafened)
        }) {
            Icon(
                if (v.deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
                if (v.deafened) tr("Undeafen", "Voltar a ouvir") else tr("Deafen", "Ensurdecer"),
                tint = if (v.deafened) p.danger else p.text,
            )
        }
        IconButton({
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            Engine.send("leave")
        }) { Icon(Icons.Filled.CallEnd, if (v.private) tr("Hang up", "Desligar") else tr("Leave", "Sair"), tint = p.danger) }
    }
}

@Composable
private fun VoiceSheet(voice: State<VoiceState?>, s: SessionState, onProfile: (Long) -> Unit) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    // The call may end under the sheet; its host closes the sheet a moment later.
    val v = voice.value ?: return
    val roster = v.roster
    val speakingLabel = tr("speaking", "falando")
    // Scrolls: in landscape, or with many people, the controls at the bottom stay reachable.
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(v.channel ?: "", style = MaterialTheme.typography.titleLarge, color = p.text, modifier = Modifier.semantics { heading() })
        Text(
            when (roster.size) {
                1 -> tr("Just you here", "Só você aqui")
                else -> tr("${roster.size} people", "${roster.size} pessoas")
            },
            color = p.muted, style = MaterialTheme.typography.bodySmall,
        )
        if (v.forceMuted) {
            Text(
                tr("You can't speak here right now.", "Você não pode falar aqui agora."),
                color = p.warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        for (m in roster) {
            val u = s.user(m.userId)
            val me = m.userId == s.me.id
            val name = u?.name ?: m.nickname
            val speaking = m.userId in v.speaking
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = tr("Open profile", "Abrir perfil")) { onProfile(m.userId) }
                        .semantics(mergeDescendants = true) { if (speaking) stateDescription = speakingLabel },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, u?.avatarHash, size = 40.dp, speaking = speaking)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = p.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (me) Text(tr("you", "você"), color = p.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    MemberMarks(m)
                }
                if (!me) {
                    val vol = v.volumes[m.userId] ?: 1f
                    var drag by remember(m.userId) { mutableStateOf<Float?>(null) }
                    val said = tr("Volume of $name", "Volume de $name")
                    Row(Modifier.padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = drag ?: vol,
                            onValueChange = { drag = it },
                            onValueChangeFinished = {
                                drag?.let { Engine.send("volume", "userId" to m.userId, "gain" to it.toDouble()) }
                                drag = null
                            },
                            valueRange = 0f..3.5f,
                            modifier = Modifier.weight(1f).semantics { contentDescription = said },
                        )
                        Text("${((drag ?: vol) * 100).toInt()}%", color = p.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp).padding(start = 8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton(
                if (v.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                if (v.muted) tr("Unmute", "Ativar microfone") else tr("Mute", "Mutar"),
                if (v.muted) p.danger else p.raised,
            ) {
                haptic.performHapticFeedback(if (v.muted) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                Engine.send("mute", "on" to !v.muted)
            }
            RoundButton(
                if (v.deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
                if (v.deafened) tr("Undeafen", "Voltar a ouvir") else tr("Deafen", "Ensurdecer"),
                if (v.deafened) p.danger else p.raised,
            ) {
                haptic.performHapticFeedback(if (v.deafened) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                Engine.send("deafen", "on" to !v.deafened)
            }
            RoundButton(Icons.Filled.CallEnd, tr("Leave", "Sair"), p.danger) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                Engine.send("leave")
            }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(60.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (bg == p.raised) p.text else Color.White)
    }
}
