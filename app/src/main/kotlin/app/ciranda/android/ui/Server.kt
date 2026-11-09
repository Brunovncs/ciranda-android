package app.ciranda.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ciranda.android.AppState
import app.ciranda.android.Channel
import app.ciranda.android.Engine
import app.ciranda.android.Event
import app.ciranda.android.MainActivity
import app.ciranda.android.Member
import app.ciranda.android.SessionState
import app.ciranda.android.Updater
import app.ciranda.android.VoiceState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(activity: MainActivity, state: AppState, s: SessionState) {
    val p = LocalPalette.current
    val voice by Engine.voice.collectAsState()
    val chat by Engine.chat.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showVoice by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }

    LaunchedEffect(Unit) {
        Engine.events.collect { e ->
            if (e is Event.NeedsPassword) askPassword = e.channelId to e.wrong
        }
    }
    val intent by activity.pending.collectAsState()
    LaunchedEffect(intent) {
        val i = intent ?: return@LaunchedEffect
        val ch = i.getLongExtra("channelId", -1)
        if (ch >= 0) {
            Engine.send("open", "channelId" to ch)
            drawer.close()
        }
        if (i.getBooleanExtra("update", false)) showSettings = true
        activity.pending.value = null
    }

    // Back closes the drawer, and otherwise sends Ciranda to the background as chat apps do,
    // rather than closing it: a call goes on either way.
    androidx.activity.compose.BackHandler(enabled = drawer.isClosed) { activity.moveTaskToBack(true) }
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    val joinVoice = { c: Channel ->
        if (voice?.channelId != c.id) activity.join(c.id)
        showVoice = true
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = p.surface, modifier = Modifier.width(320.dp)) {
                ChannelList(
                    s, voice,
                    onText = { c ->
                        Engine.send("open", "channelId" to c.id)
                        scope.launch { drawer.close() }
                    },
                    onVoice = { c ->
                        joinVoice(c)
                        scope.launch { drawer.close() }
                    },
                    onSettings = { showSettings = true },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(p.bg)) {
            val channel = s.channel(s.openChannel)
            TopBar(
                title = channel?.name ?: s.name,
                subtitle = if (!s.up) tr("Reconnecting…", "Reconectando…") else null,
                onMenu = { scope.launch { drawer.open() } },
                onPeople = if (voice != null) ({ showVoice = true }) else null,
            )
            UpdateBanner(activity)
            Box(Modifier.weight(1f)) {
                val c = chat
                if (channel != null && c != null && c.channelId == channel.id) {
                    ChatView(activity, s, c)
                } else if (channel != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(tr("Pick a channel.", "Escolha um canal."), color = p.muted)
                    }
                }
            }
            voice?.let { VoiceBar(it, s, onOpen = { showVoice = true }) }
            if (channel != null && !channel.voice) {
                Composer(activity, s, channel)
            } else {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (showVoice && voice != null) {
        ModalBottomSheet({ showVoice = false }, sheetState = rememberModalBottomSheetState(), containerColor = p.surface) {
            VoiceSheet(voice!!, s)
        }
    }
    if (showSettings) {
        SettingsScreen(activity, state, onClose = { showSettings = false })
    }
    askPassword?.let { (channelId, wrong) ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = null },
            title = { Text(s.channel(channelId)?.name ?: "") },
            text = {
                Column {
                    Text(
                        if (wrong) tr("That password is wrong.", "Essa senha está errada.") else tr("This channel has a password.", "Este canal tem senha."),
                        color = if (wrong) p.danger else p.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(pw, { pw = it }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton({
                    askPassword = null
                    activity.join(channelId, pw)
                }) { Text(tr("Join", "Entrar")) }
            },
            dismissButton = { TextButton({ askPassword = null }) { Text(tr("Cancel", "Cancelar")) } },
        )
    }
}

@Composable
private fun TopBar(title: String, subtitle: String?, onMenu: () -> Unit, onPeople: (() -> Unit)?) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.surface).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onMenu) { Icon(Icons.Filled.Menu, tr("Channels", "Canais"), tint = p.text) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tag, null, tint = p.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = p.warning) }
        }
        if (onPeople != null) {
            IconButton(onPeople) { Icon(Icons.Filled.People, tr("In voice", "Na voz"), tint = p.text) }
        }
    }
}

@Composable
private fun UpdateBanner(activity: MainActivity) {
    val p = LocalPalette.current
    val u by Updater.state.collectAsState()
    val (text, action) = when (val st = u) {
        is Updater.State.Available -> tr("Ciranda ${st.release.version} is out.", "Ciranda ${st.release.version} está disponível.") to
            { Updater.download(activity, st.release) }
        is Updater.State.Downloading -> tr("Downloading ${(st.fraction * 100).toInt()}%…", "Baixando ${(st.fraction * 100).toInt()}%…") to null
        is Updater.State.Ready -> tr("Version ${st.release.version} is ready to install.", "A versão ${st.release.version} está pronta para instalar.") to
            { Updater.install(activity, st.file) }
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().background(p.accent.copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = p.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(action) {
                Text(if (u is Updater.State.Ready) tr("Install", "Instalar") else tr("Update", "Atualizar"))
            }
        }
    }
}

@Composable
private fun ChannelList(
    s: SessionState,
    voice: VoiceState?,
    onText: (Channel) -> Unit,
    onVoice: (Channel) -> Unit,
    onSettings: () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.name, s.icon, size = 40.dp, kind = "server")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (s.up) tr("${s.online.size} online", "${s.online.size} online") else tr("Reconnecting…", "Reconectando…"),
                    style = MaterialTheme.typography.labelSmall, color = if (s.up) p.muted else p.warning,
                )
            }
        }
        HorizontalDivider(color = p.border)
        val groups = s.groups.sortedBy { it.position }
        val sections = buildList {
            add(null to s.channels.filter { it.groupId == null || groups.none { g -> g.id == it.groupId } })
            for (g in groups) add(g.name to s.channels.filter { it.groupId == g.id })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            for ((name, list) in sections) {
                if (list.isEmpty()) continue
                if (name != null) {
                    item(key = "g$name") {
                        Text(
                            name.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.muted,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                }
                items(list.sortedBy { it.position }, key = { it.id }) { c ->
                    if (c.voice) {
                        VoiceChannelRow(c, s, voice, onVoice)
                    } else {
                        TextChannelRow(c, s, onText)
                    }
                }
            }
        }
        HorizontalDivider(color = p.border)
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.me.name, s.me.avatarHash, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.me.name, color = p.text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("@${s.me.nickname}", color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            IconButton(onSettings) { Icon(Icons.Filled.Settings, tr("Settings", "Configurações"), tint = p.muted) }
        }
    }
}

@Composable
private fun TextChannelRow(c: Channel, s: SessionState, onText: (Channel) -> Unit) {
    val p = LocalPalette.current
    val open = s.openChannel == c.id
    val unread = c.id in s.unread
    val mentions = s.mentioned[c.id] ?: 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(8.dp))
            .background(if (open) p.raised else Color.Transparent).clickable { onText(c) }.padding(horizontal = 10.dp, vertical = 9.dp),
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
            Box(Modifier.clip(CircleShape).background(p.danger).padding(horizontal = 7.dp, vertical = 1.dp)) {
                Text("$mentions", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun VoiceChannelRow(c: Channel, s: SessionState, voice: VoiceState?, onVoice: (Channel) -> Unit) {
    val p = LocalPalette.current
    val here = voice?.channelId == c.id
    val roster = s.rosters[c.id].orEmpty()
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(8.dp))
                .background(if (here) p.raised else Color.Transparent).clickable { onVoice(c) }.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = if (here) p.success else p.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(c.name, modifier = Modifier.weight(1f), color = if (here) p.text else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.locked) Icon(Icons.Filled.Lock, null, tint = p.muted, modifier = Modifier.size(14.dp))
        }
        for (m in roster) {
            val u = s.user(m.userId)
            val speaking = here && voice != null && m.userId in voice.speaking
            Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 12.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(u?.name ?: m.nickname, u?.avatarHash, size = 24.dp, speaking = speaking)
                Spacer(Modifier.width(8.dp))
                Text(u?.name ?: m.nickname, color = p.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                MemberMarks(m)
            }
        }
    }
}

@Composable
private fun MemberMarks(m: Member) {
    val p = LocalPalette.current
    if (m.deafened) {
        Icon(Icons.Filled.HeadsetOff, null, tint = p.danger, modifier = Modifier.size(16.dp))
    } else if (m.muted || m.forceMuted) {
        Icon(Icons.Filled.MicOff, null, tint = p.danger, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun VoiceBar(v: VoiceState, s: SessionState, onOpen: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.surface).clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val (label, color) = when (v.state) {
                "connected" -> tr("Voice connected", "Voz conectada") to p.success
                "joining" -> tr("Joining…", "Entrando…") to p.warning
                else -> tr("Reconnecting…", "Reconectando…") to p.warning
            }
            Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(v.channel, v.pingMs?.let { "$it ms" }, if (v.micFailed) tr("microphone not connected", "microfone sem conexão") else null)
                    .joinToString(" · "),
                color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            )
        }
        IconButton({ Engine.send("mute", "on" to !v.muted) }) {
            Icon(
                if (v.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                if (v.muted) tr("Unmute", "Ativar microfone") else tr("Mute", "Mutar"),
                tint = if (v.muted) p.danger else p.text,
            )
        }
        IconButton({ Engine.send("deafen", "on" to !v.deafened) }) {
            Icon(
                if (v.deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
                if (v.deafened) tr("Undeafen", "Voltar a ouvir") else tr("Deafen", "Ensurdecer"),
                tint = if (v.deafened) p.danger else p.text,
            )
        }
        IconButton({ Engine.send("leave") }) { Icon(Icons.Filled.CallEnd, tr("Leave", "Sair"), tint = p.danger) }
    }
}

@Composable
private fun VoiceSheet(v: VoiceState, s: SessionState) {
    val p = LocalPalette.current
    val roster = s.rosters[v.channelId].orEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(v.channel ?: "", style = MaterialTheme.typography.titleLarge, color = p.text)
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
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u?.name ?: m.nickname, u?.avatarHash, size = 40.dp, speaking = m.userId in v.speaking)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u?.name ?: m.nickname, color = p.text, style = MaterialTheme.typography.titleSmall)
                        if (me) Text(tr("you", "você"), color = p.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    MemberMarks(m)
                }
                if (!me) {
                    val vol = v.volumes[m.userId] ?: 1f
                    var drag by remember(m.userId) { mutableStateOf<Float?>(null) }
                    Row(Modifier.padding(start = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = drag ?: vol,
                            onValueChange = { drag = it },
                            onValueChangeFinished = {
                                drag?.let { Engine.send("volume", "userId" to m.userId, "gain" to it.toDouble()) }
                                drag = null
                            },
                            valueRange = 0f..3.5f,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${((drag ?: vol) * 100).toInt()}%", color = p.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp).padding(start = 8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton(if (v.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (v.muted) p.danger else p.raised) {
                Engine.send("mute", "on" to !v.muted)
            }
            RoundButton(if (v.deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset, if (v.deafened) p.danger else p.raised) {
                Engine.send("deafen", "on" to !v.deafened)
            }
            RoundButton(Icons.Filled.CallEnd, p.danger) { Engine.send("leave") }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(60.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (bg == p.raised) p.text else Color.White)
    }
}
