package app.ciranda.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ciranda.android.Channel
import app.ciranda.android.Engine
import app.ciranda.android.Role as UserRole
import app.ciranda.android.SavedServer
import app.ciranda.android.SessionState
import app.ciranda.android.User
import app.ciranda.android.VoiceState

/** What the bar at the bottom opens; the chat is what is under it all. */
enum class Tab { Channels, People, Voice, Settings }

/**
 * The bar under the chat: the server (its picture, with the menu to switch above it), the
 * channels, the people, the voice and the settings, as shopping apps lay theirs out.
 */
@Composable
fun BottomBar(
    s: SessionState,
    saved: List<SavedServer>,
    open: Tab?,
    inVoice: Boolean,
    unreadPeople: Int,
    onTab: (Tab) -> Unit,
    onSwitch: (SavedServer?) -> Unit,
) {
    val p = LocalPalette.current
    val mentions = s.mentioned.values.sum()
    val unread = s.unread.any { id -> s.channel(id)?.voice == false }
    Row(
        Modifier.fillMaxWidth().background(p.surface).topBorder(p.border).navigationBarsPadding().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerItem(s, saved, onSwitch)
        BarItem(Icons.Filled.Tag, tr("Channels", "Canais"), open == Tab.Channels, onClick = { onTab(Tab.Channels) }) {
            when {
                mentions > 0 -> Badge(containerColor = p.danger) { Text(if (mentions > 99) "99+" else "$mentions") }
                unread -> Badge(containerColor = p.accent)
            }
        }
        BarItem(Icons.Filled.People, tr("People", "Pessoas"), open == Tab.People, onClick = { onTab(Tab.People) }) {
            if (unreadPeople > 0) Badge(containerColor = p.danger) { Text(if (unreadPeople > 99) "99+" else "$unreadPeople") }
        }
        BarItem(
            if (inVoice) Icons.Filled.Headset else Icons.AutoMirrored.Filled.VolumeUp,
            tr("Voice", "Voz"), open == Tab.Voice, tint = if (inVoice) p.success else null, onClick = { onTab(Tab.Voice) },
        ) {
            if (inVoice) Badge(containerColor = p.success)
        }
        BarItem(Icons.Filled.Settings, tr("Settings", "Ajustes"), open == Tab.Settings, onClick = { onTab(Tab.Settings) })
    }
}

@Composable
private fun RowScope.BarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tint: Color? = null,
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    val color = tint ?: if (selected) p.accent else p.muted
    Column(
        Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (badge != null) {
            BadgedBox(badge = { badge() }) { Icon(icon, null, tint = color, modifier = Modifier.size(24.dp)) }
        } else {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (selected) p.text else p.muted, fontSize = 11.sp, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** The server's own picture in the bar; a tap opens, above it, the servers to switch to. */
@Composable
private fun RowScope.ServerItem(s: SessionState, saved: List<SavedServer>, onSwitch: (SavedServer?) -> Unit) {
    val p = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    // The others signed in to: this one is already on screen.
    val others = remember(saved, s.url, s.me.nickname) {
        saved.filter { it.signedIn && !(it.username == s.me.nickname && sameServer(it.url, s.url)) }
    }
    Box(Modifier.weight(1f).fillMaxHeight()) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().clickable(role = Role.Button, onClickLabel = tr("Switch server", "Trocar de servidor")) { menu = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Avatar(s.name, s.icon, size = 26.dp, kind = "server", modifier = Modifier.clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.height(3.dp))
            Text(s.name, color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        }
        DropdownMenu(menu, { menu = false }, containerColor = p.surface) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.name, s.icon, size = 36.dp, kind = "server")
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, color = p.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (s.up) tr("${s.online.size} online", "${s.online.size} online") else tr("Reconnecting…", "Reconectando…"),
                        color = if (s.up) p.muted else p.warning, style = MaterialTheme.typography.labelSmall,
                    )
                }
                Icon(Icons.Filled.Check, tr("On screen", "Na tela"), tint = p.accent, modifier = Modifier.size(20.dp))
            }
            HorizontalDivider(color = p.border)
            for (o in others) {
                DropdownMenuItem(
                    { Column { Text(o.name.ifEmpty { o.host }, maxLines = 1); Text("${o.username} · ${o.host}", color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1) } },
                    { menu = false; onSwitch(o) },
                    leadingIcon = { Icon(Icons.Filled.Dns, null, tint = p.accent) },
                )
            }
            DropdownMenuItem({ Text(tr("Another server", "Outro servidor")) }, { menu = false; onSwitch(null) }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) })
            HorizontalDivider(color = p.border)
            DropdownMenuItem(
                { Text(tr("Invite friends", "Convidar amigos")) },
                { menu = false; Engine.send("shareInvite") },
                leadingIcon = { Icon(Icons.Filled.PersonAdd, null, tint = p.accent) },
            )
        }
    }
}

/** Whether two addresses are the same server, however they were typed (as the engine compares them). */
fun sameServer(a: String, b: String): Boolean {
    fun norm(u: String) = u.trim().let { if ("://" in it) it else "http://$it" }.trimEnd('/')
    return a.isNotBlank() && norm(a).equals(norm(b), ignoreCase = true)
}

/** The voice channel someone is in, if any. */
fun SessionState.roomOf(userId: Long): Channel? =
    rosters.entries.firstOrNull { (_, r) -> r.any { it.userId == userId } }?.key?.let { channel(it) }

@Composable
fun SheetTitle(text: String, detail: String? = null) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = p.text, modifier = Modifier.semantics { heading() })
        detail?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall) }
    }
}

/** A person in a list: their picture (with a dot when online), name, and a line under it. */
@Composable
fun PersonRow(u: User, online: Boolean, detail: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = tr("Open profile", "Abrir perfil"), onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(u.name, u.avatarHash, size = 40.dp)
            if (online) {
                Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(p.surface).padding(2.dp).clip(CircleShape).background(p.success))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.name, color = if (online) p.text else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = p.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

/** Everyone on the server, those online first; above them, the private conversations. */
@Composable
fun PeopleSheet(s: SessionState, conversations: @Composable () -> Unit, onProfile: (Long) -> Unit) {
    val (online, offline) = remember(s.users, s.online) {
        s.users.values.sortedBy { it.name.lowercase() }.partition { it.id in s.online }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        item(key = "title") { SheetTitle(tr("People", "Pessoas")) }
        item(key = "conversations") { conversations() }
        item(key = "online") { GroupLabel(tr("Online — ${online.size}", "Online — ${online.size}")) }
        items(online, key = { "on${it.id}" }) { u ->
            val room = s.roomOf(u.id)
            PersonRow(u, true, room?.let { tr("In ${it.name}", "Em ${it.name}") } ?: "@${u.nickname}", { onProfile(u.id) })
        }
        if (offline.isNotEmpty()) {
            item(key = "offline") { GroupLabel(tr("Offline — ${offline.size}", "Offline — ${offline.size}")) }
            items(offline, key = { "off${it.id}" }) { u -> PersonRow(u, false, "@${u.nickname}", { onProfile(u.id) }) }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }
    }
}

@Composable
fun GroupLabel(text: String) {
    val p = LocalPalette.current
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.muted, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** The voice channels with who is in each, for when you are in none: a tap shows the room first. */
@Composable
fun VoiceRooms(s: SessionState, onRoom: (Channel) -> Unit, onProfile: (Long) -> Unit) {
    val p = LocalPalette.current
    val rooms = remember(s.channels) { s.channels.filter { it.voice }.sortedBy { it.position } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SheetTitle(tr("Voice", "Voz"), tr("You're not in a voice channel.", "Você não está em nenhuma sala de voz."))
        if (rooms.isEmpty()) {
            Text(tr("This server has no voice channels.", "Este servidor não tem salas de voz."), color = p.muted, modifier = Modifier.padding(20.dp))
        }
        for (c in rooms) {
            val roster = s.rosters[c.id].orEmpty()
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(p.raised)
                    .clickable(role = Role.Button, onClickLabel = tr("See the room", "Ver a sala")) { onRoom(c) }.padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = p.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(c.name, color = p.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.locked) Icon(Icons.Filled.Lock, tr("Has a password", "Tem senha"), tint = p.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (roster.isEmpty()) tr("empty", "vazia") else "${roster.size}", color = p.muted, style = MaterialTheme.typography.labelSmall)
                }
                if (roster.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                        for (m in roster.take(8)) {
                            val u = s.user(m.userId)
                            Avatar(
                                u?.name ?: m.nickname, u?.avatarHash, size = 28.dp,
                                modifier = Modifier.border(2.dp, p.raised, CircleShape).clip(CircleShape).clickable { onProfile(m.userId) },
                            )
                        }
                        if (roster.size > 8) Text("+${roster.size - 8}", color = p.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp).align(Alignment.CenterVertically))
                    }
                }
            }
        }
    }
}

/** A voice channel, before going in: who is there, and the button that joins. */
@Composable
fun RoomSheet(c: Channel, s: SessionState, voice: State<VoiceState?>, onJoin: () -> Unit, onControls: () -> Unit, onProfile: (Long) -> Unit) {
    val p = LocalPalette.current
    val roster = s.rosters[c.id].orEmpty()
    val here = voice.value?.channelId == c.id
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = if (here) p.success else p.muted)
            Spacer(Modifier.width(8.dp))
            Text(c.name, style = MaterialTheme.typography.titleLarge, color = p.text, modifier = Modifier.weight(1f).semantics { heading() })
            if (c.locked) Icon(Icons.Filled.Lock, tr("Has a password", "Tem senha"), tint = p.muted)
        }
        Text(
            when {
                here -> tr("You're in this room.", "Você está nesta sala.")
                roster.isEmpty() -> tr("Nobody here yet.", "Ninguém aqui ainda.")
                roster.size == 1 -> tr("1 person here", "1 pessoa aqui")
                else -> tr("${roster.size} people here", "${roster.size} pessoas aqui")
            },
            color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        for (m in roster) {
            val u = s.user(m.userId) ?: User(m.userId, m.nickname, null, UserRole.Member, null)
            PersonRow(u, true, if (m.userId == s.me.id) tr("you", "você") else "@${u.nickname}", { onProfile(m.userId) }) { MemberMarks(m) }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.padding(horizontal = 20.dp)) {
            if (here) {
                OutlinedButton(onControls, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text(tr("Voice controls", "Controles da voz")) }
            } else {
                Button(onJoin, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.Headset, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (voice.value != null) tr("Switch to this room", "Mudar para esta sala") else tr("Join the room", "Entrar na sala"))
                }
            }
        }
    }
}

/** Someone's profile: who they are, where they are, and what can be done with them. */
@Composable
fun ProfileSheet(
    u: User,
    s: SessionState,
    voice: State<VoiceState?>,
    canMessage: Boolean,
    onMessage: () -> Unit,
    onRoom: (Channel) -> Unit,
) {
    val p = LocalPalette.current
    val me = u.id == s.me.id
    val online = u.id in s.online
    val room = s.roomOf(u.id)
    val v = voice.value
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Avatar(u.name, u.avatarHash, size = 96.dp)
            if (online) {
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(p.surface).padding(3.dp).clip(CircleShape).background(p.success))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(u.name, style = MaterialTheme.typography.headlineSmall, color = p.text, fontWeight = FontWeight.SemiBold)
        Text("@${u.nickname}", color = p.muted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (u.role) {
                UserRole.Owner -> Chip(tr("Owner", "Dono"), p.warning)
                UserRole.Admin -> Chip(tr("Admin", "Admin"), p.accent)
                UserRole.Member -> {}
            }
            Chip(if (online) tr("Online", "Online") else tr("Offline", "Offline"), if (online) p.success else p.muted)
            if (me) Chip(tr("You", "Você"), p.muted)
        }
        if (room != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.raised)
                    .clickable(role = Role.Button, onClickLabel = tr("See the room", "Ver a sala")) { onRoom(room) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = p.success)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("In a voice channel", "Numa sala de voz"), color = p.muted, style = MaterialTheme.typography.labelSmall)
                    Text(room.name, color = p.text)
                }
            }
        }
        if (!me && canMessage) {
            Spacer(Modifier.height(16.dp))
            Button(onMessage, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.AutoMirrored.Filled.Chat, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Send a message", "Mandar mensagem"))
            }
        }
        // In your call: how loud you hear them, as in the call's own sheet.
        if (!me && v != null && room?.id == v.channelId) {
            val vol = v.volumes[u.id] ?: 1f
            var drag by remember(u.id) { mutableStateOf<Float?>(null) }
            val said = tr("Volume of ${u.name}", "Volume de ${u.name}")
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth()) {
                Row {
                    Text(tr("Volume for you", "Volume para você"), color = p.text, modifier = Modifier.weight(1f))
                    Text("${((drag ?: vol) * 100).toInt()}%", color = p.muted)
                }
                Slider(
                    drag ?: vol, { drag = it },
                    Modifier.semantics { contentDescription = said },
                    valueRange = 0f..3.5f,
                    onValueChangeFinished = {
                        drag?.let { Engine.send("volume", "userId" to u.id, "gain" to it.toDouble()) }
                        drag = null
                    },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.55f), CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = text },
    )
}
