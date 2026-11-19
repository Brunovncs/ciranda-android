package app.ciranda.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ciranda.android.Engine
import app.ciranda.android.Event
import app.ciranda.android.KeyState
import app.ciranda.android.MainActivity
import app.ciranda.android.Notifier
import app.ciranda.android.SessionState
import java.text.DateFormat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.util.Date

/** A question or a page about private conversations, over everything; one at a time. */
sealed interface PrivateDialog {
    data object Unlock : PrivateDialog
    data object Reset : PrivateDialog
    /** The recovery key, shown; `renew` makes a new one first. */
    data class Recovery(val renew: Boolean = false) : PrivateDialog
    data object NewRecovery : PrivateDialog
    data class Safety(val userId: Long) : PrivateDialog
    data class Clear(val conversationId: Long, val userId: Long) : PrivateDialog
    data class Block(val userId: Long, val on: Boolean) : PrivateDialog
}

/**
 * The private conversations as the server screen uses them: which one is on screen, how many
 * are unread, and the dialogs they ask.
 */
@Stable
class Privates(val activity: MainActivity) {
    var session: SessionState? = null
    var dialog by mutableStateOf<PrivateDialog?>(null)

    private val st get() = session?.private

    /** The conversation on screen, if one is. */
    val open: Long? get() = st?.open?.id
    val unreadCount: Int get() = st?.unread ?: 0

    /** Whether the server has private conversations at all. */
    val available: Boolean get() = st?.state?.let { it != KeyState.Unsupported } ?: false

    fun openWith(userId: Long) = Engine.send("dmWith", "userId" to userId)
    fun openConversation(id: Long) = Engine.send("dmOpen", "id" to id)
    fun close() {
        if (open != null) Engine.send("dmClose")
    }

    @Composable
    fun Dialogs() {
        val s = session ?: return
        when (val d = dialog) {
            null -> {}
            PrivateDialog.Unlock -> UnlockDialog(this)
            PrivateDialog.Reset -> Confirm(
                tr("Start over without the recovery key?", "Recomeçar sem a chave de recuperação?"),
                tr(
                    "You get a new key and can talk privately again right away. Messages from before stay locked for you (the other person still has them), and the people you talk to will see that your key changed.",
                    "Você ganha uma chave nova e pode conversar no privado de novo na hora. As mensagens de antes continuam trancadas para você (a outra pessoa ainda as tem), e quem conversa com você vai ver que sua chave mudou.",
                ),
                tr("Start over", "Recomeçar"), danger = true,
            ) {
                Engine.send("dmReset")
                dialog = null
            }
            is PrivateDialog.Recovery -> RecoveryDialog(this, d.renew)
            PrivateDialog.NewRecovery -> Confirm(
                tr("Make a new recovery key?", "Gerar uma nova chave de recuperação?"),
                tr("The old one stops working. Your messages stay as they are.", "A antiga para de funcionar. Suas mensagens continuam como estão."),
                tr("Make a new one", "Gerar nova"),
            ) {
                dialog = PrivateDialog.Recovery(renew = true)
            }
            is PrivateDialog.Safety -> SafetyDialog(this, s, d.userId)
            is PrivateDialog.Clear -> {
                val name = s.user(d.userId)?.name ?: tr("The other person", "A outra pessoa")
                Confirm(
                    tr("Delete this conversation?", "Apagar esta conversa?"),
                    tr(
                        "Every message in it goes, for you only: $name keeps all of it and isn't told. If either of you writes again, it starts over from there. This can't be undone.",
                        "Todas as mensagens dela somem só para você: $name continua com tudo e não recebe nenhum aviso. Se algum dos dois escrever de novo, ela recomeça dali. Não dá para desfazer.",
                    ),
                    tr("Delete", "Apagar"), danger = true,
                ) {
                    Engine.send("dmClear", "id" to d.conversationId)
                    dialog = null
                }
            }
            is PrivateDialog.Block -> {
                val name = s.user(d.userId)?.name ?: ""
                if (d.on) {
                    Confirm(
                        tr("Block $name?", "Bloquear $name?"),
                        tr(
                            "Neither of you will be able to send the other messages until you unblock them. They aren't told.",
                            "Nenhum dos dois vai conseguir mandar mensagens para o outro até você desbloquear. A pessoa não é avisada.",
                        ),
                        tr("Block", "Bloquear"), danger = true,
                    ) {
                        Engine.send("dmBlock", "userId" to d.userId, "on" to true)
                        dialog = null
                    }
                } else {
                    Engine.send("dmBlock", "userId" to d.userId, "on" to false)
                    dialog = null
                }
            }
        }
    }

    @Composable
    private fun Confirm(title: String, text: String, ok: String, danger: Boolean = false, onOk: () -> Unit) {
        val p = LocalPalette.current
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                Button(onOk, colors = if (danger) ButtonDefaults.buttonColors(containerColor = p.danger) else ButtonDefaults.buttonColors()) { Text(ok) }
            },
            dismissButton = { TextButton({ dialog = null }) { Text(tr("Cancel", "Cancelar")) } },
        )
    }
}

@Composable
fun rememberPrivate(activity: MainActivity, s: SessionState): Privates {
    val privates = remember(activity) { Privates(activity) }
    privates.session = s
    // A conversation on screen has nothing left to tell in a notification.
    val resumed by activity.resumes.collectAsState()
    val open = s.private.open?.id
    LaunchedEffect(s.url, s.me.nickname, open, resumed) {
        open?.let { Notifier.seenPrivate(activity, s.url, s.me.nickname, it) }
    }
    return privates
}

/** The top of a private conversation: who it is with, that it is sealed, and its menu. */
@Composable
fun PrivateTopBar(privates: Privates, s: SessionState, onProfile: (Long) -> Unit) {
    val p = LocalPalette.current
    val open = s.private.open ?: return
    val peer = s.user(open.peer)
    val name = peer?.name ?: tr("Someone", "Alguém")
    var menu by remember { mutableStateOf(false) }
    // Back leaves the conversation for the channel under it.
    BackHandler { privates.close() }
    Row(
        Modifier.fillMaxWidth().background(p.surface).statusBarsPadding().heightIn(min = 56.dp).bottomBorder(p.border).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton({ privates.close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Voltar"), tint = p.text) }
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = tr("Open profile", "Abrir perfil")) { onProfile(open.peer) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name, peer?.avatarHash, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { privates.dialog = PrivateDialog.Safety(open.peer) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (open.verified) Icons.Filled.Verified else Icons.Filled.Lock, null, tint = if (open.verified) p.success else p.muted, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (open.verified) tr("Verified", "Verificada") else tr("End-to-end encrypted", "Criptografia de ponta a ponta"),
                        color = if (open.verified) p.success else p.muted, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        // A call needs their key, as a message does; one already with them just shows again.
        val voice by Engine.voice.collectAsState()
        val inThisCall = voice?.conversationId == open.id
        if (inThisCall || (open.peerHasKey && !open.blocked && s.private.state == KeyState.Ready)) {
            IconButton({ if (inThisCall) privates.activity.openCall() else privates.activity.call(open.id) }) {
                Icon(Icons.Filled.Call, if (inThisCall) tr("Back to the call", "Voltar para a chamada") else tr("Call $name", "Ligar para $name"), tint = if (inThisCall) p.success else p.text)
            }
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, tr("More", "Mais"), tint = p.text) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    { Text(tr("Compare safety number", "Comparar código de segurança")) },
                    { menu = false; privates.dialog = PrivateDialog.Safety(open.peer) },
                    leadingIcon = { Icon(Icons.Filled.VerifiedUser, null) },
                )
                DropdownMenuItem(
                    { Text(if (open.blocked) tr("Unblock $name", "Desbloquear $name") else tr("Block $name", "Bloquear $name")) },
                    { menu = false; privates.dialog = PrivateDialog.Block(open.peer, !open.blocked) },
                    leadingIcon = { Icon(Icons.Filled.Block, null) },
                )
                DropdownMenuItem(
                    { Text(tr("Delete conversation", "Apagar conversa"), color = p.danger) },
                    { menu = false; privates.dialog = PrivateDialog.Clear(open.id, open.peer) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = p.danger) },
                )
            }
        }
    }
}

/** The conversation on screen, with the notice above it while the other person's key changed. */
@Composable
fun PrivateChat(activity: MainActivity, privates: Privates, s: SessionState, onProfile: (Long) -> Unit) {
    val p = LocalPalette.current
    val open = s.private.open ?: return
    val chat by Engine.privateChat.collectAsState()
    val name = s.user(open.peer)?.name ?: tr("Someone", "Alguém")
    Column(Modifier.fillMaxSize()) {
        if (open.keyChanged) {
            Column(Modifier.fillMaxWidth().background(p.warning.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Warning, null, tint = p.warning, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (open.held != null) {
                            tr(
                                "$name's security key changed, so your message wasn't sent: it's still in the box. It happens when someone sets Ciranda up again without their recovery key; if you weren't expecting it, compare your safety number with them.",
                                "A chave de segurança de $name mudou, então sua mensagem não foi enviada: ela continua na caixa. Isso acontece quando alguém configura o Ciranda de novo sem a chave de recuperação; se você não esperava por isso, comparem o código de segurança.",
                            )
                        } else {
                            tr(
                                "$name's security key changed. It happens when someone sets Ciranda up again without their recovery key; if you weren't expecting it, compare your safety number with them. Nothing is sent to the new key until you accept it.",
                                "A chave de segurança de $name mudou. Isso acontece quando alguém configura o Ciranda de novo sem a chave de recuperação; se você não esperava por isso, comparem o código de segurança. Nada é enviado para a nova chave até você aceitá-la.",
                            )
                        },
                        color = p.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ privates.dialog = PrivateDialog.Safety(open.peer) }) { Text(tr("Compare", "Comparar")) }
                    TextButton({ Engine.send("dmAccept", "userId" to open.peer, "send" to (open.held != null)) }) {
                        Text(if (open.held != null) tr("Accept and send", "Aceitar e enviar") else tr("Accept new key", "Aceitar nova chave"))
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            val c = chat
            if (c != null && c.channelId == open.id) {
                androidx.compose.runtime.key(open.id) {
                    FadeIn { ChatView(activity, s, c, Place(open.id, true, name), onProfile) }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
        }
    }
}

/** The box to write in, or why it can't be written in. */
@Composable
fun PrivateComposer(activity: MainActivity, privates: Privates, s: SessionState) {
    val p = LocalPalette.current
    val open = s.private.open ?: return
    val name = s.user(open.peer)?.name ?: tr("Someone", "Alguém")
    val (why, action) = when {
        open.blocked -> tr("You blocked $name. Unblock them to write here again.", "Você bloqueou $name. Desbloqueie para voltar a escrever aqui.") to
            (tr("Unblock", "Desbloquear") to { privates.dialog = PrivateDialog.Block(open.peer, false) })
        s.private.state == KeyState.Locked -> tr("Private messages are locked on this phone.", "As mensagens privadas estão trancadas neste celular.") to
            (tr("Unlock", "Desbloquear") to { privates.dialog = PrivateDialog.Unlock })
        s.private.state == KeyState.Ready && !open.peerHasKey -> tr(
            "$name can't receive private messages yet: they need to update Ciranda and open it once.",
            "$name ainda não pode receber mensagens privadas: precisa atualizar o Ciranda e abrir o app uma vez.",
        ) to null
        s.private.state == KeyState.Failed -> tr(
            "Private messages aren't available right now.",
            "As mensagens privadas não estão disponíveis agora.",
        ) to null
        s.private.state == KeyState.Loading -> tr("Getting ready…", "Preparando…") to null
        else -> null to null
    }
    if (why == null) {
        Composer(activity, s, Place(open.id, true, name))
        return
    }
    Row(
        Modifier.fillMaxWidth().background(p.surface).topBorder(p.border).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(why, color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        action?.let { (label, go) -> TextButton(go) { Text(label) } }
    }
}

/** The private conversations, above the people: or what keeps them from this phone. */
@Composable
fun Conversations(privates: Privates, s: SessionState, onOpened: () -> Unit) {
    val p = LocalPalette.current
    val st = s.private
    Column(Modifier.fillMaxWidth()) {
        GroupLabel(tr("Conversations", "Conversas"))
        when (st.state) {
            KeyState.Unsupported -> Note(tr("This server has no private messages.", "Este servidor não tem mensagens privadas."))
            KeyState.Failed -> Note(st.error ?: tr("Private messages aren't available right now.", "As mensagens privadas não estão disponíveis agora."))
            KeyState.Loading -> Note(tr("Getting ready…", "Preparando…"))
            KeyState.Locked -> Card(
                tr(
                    "Your private messages are locked on this phone. Unlock them with your recovery key.",
                    "Suas mensagens privadas estão trancadas neste celular. Desbloqueie com a sua chave de recuperação.",
                ),
                tr("Unlock", "Desbloquear"),
            ) { privates.dialog = PrivateDialog.Unlock }
            KeyState.Ready -> if (st.needsBackup) {
                Card(
                    tr(
                        "Keep your recovery key: it's how you read your private messages on another device.",
                        "Guarde sua chave de recuperação: é com ela que você lê suas mensagens privadas em outro aparelho.",
                    ),
                    tr("Show key", "Ver chave"),
                ) {
                    privates.dialog = PrivateDialog.Recovery()
                }
            }
        }
        if (st.state != KeyState.Unsupported) {
            if (st.conversations.isEmpty() && st.state == KeyState.Ready) {
                Note(tr("Talk to someone in private: tap a person below, then Send a message.", "Converse com alguém no privado: toque em uma pessoa abaixo e em Mandar mensagem."))
            }
            for (c in st.conversations) {
                val u = s.user(c.peer)
                val name = u?.name ?: tr("Someone", "Alguém")
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = tr("Open conversation", "Abrir conversa")) {
                        privates.openConversation(c.id)
                        onOpened()
                    }.heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Avatar(name, u?.avatarHash, size = 42.dp)
                        if (c.peer in s.online) {
                            Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(p.surface).padding(2.dp).clip(CircleShape).background(p.success))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                fontWeight = if (c.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (c.lastAt > 0) Text(shortTime(c.lastAt), color = p.muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.preview.ifEmpty { tr("No messages yet", "Nenhuma mensagem ainda") },
                                color = if (c.unread > 0) p.text else p.muted, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (c.unread > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(p.accent).padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("${c.unread}", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shortTime(at: Long): String {
    val today = java.time.LocalDate.now()
    val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    return if (day == today) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)) else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(at))
}

@Composable
private fun Note(text: String) {
    val p = LocalPalette.current
    Text(text, color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
private fun Card(text: String, action: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(p.raised).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Key, null, tint = p.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = p.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick) { Text(action) } }
    }
}

/** The recovery key typed on a phone that doesn't have this account's key yet. */
@Composable
private fun UnlockDialog(privates: Privates) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        Engine.events.collect { e ->
            if (e is Event.Unlocked) {
                if (e.ok) {
                    Toast.makeText(ctx, tr("Unlocked. Your private messages are here now.", "Desbloqueado. Suas mensagens privadas estão aqui agora."), Toast.LENGTH_LONG).show()
                    privates.dialog = null
                } else {
                    wrong = true
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { privates.dialog = null },
        icon = { Icon(Icons.Filled.LockOpen, null) },
        title = { Text(tr("Unlock your private messages", "Desbloquear suas mensagens privadas")) },
        text = {
            Column {
                Text(
                    tr(
                        "This phone doesn't have your key yet. Type the recovery key you kept when you first set up private messages.",
                        "Este celular ainda não tem a sua chave. Digite a chave de recuperação que você guardou quando ativou as mensagens privadas.",
                    ),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    key, { key = it; wrong = false },
                    Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("XXXX-XXXX-…") },
                    isError = wrong,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
                if (wrong) {
                    Text(
                        tr(
                            "That isn't the right key. Check each group of four, or find the file you saved.",
                            "Essa não é a chave certa. Confira cada grupo de quatro, ou procure o arquivo que você salvou.",
                        ),
                        color = p.danger, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                TextButton({ privates.dialog = PrivateDialog.Reset }, Modifier.padding(top = 4.dp)) {
                    Text(tr("I lost my recovery key", "Perdi minha chave de recuperação"))
                }
            }
        },
        confirmButton = { Button({ Engine.send("dmUnlock", "key" to key) }, enabled = key.isNotBlank()) { Text(tr("Unlock", "Desbloquear")) } },
        dismissButton = { TextButton({ privates.dialog = null }) { Text(tr("Cancel", "Cancelar")) } },
    )
}

/** The recovery key, shown until the person says they kept it somewhere safe. */
@Composable
private fun RecoveryDialog(privates: Privates, renew: Boolean) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    // Asked of the engine when the dialog opened; unknown until it answers.
    var key by remember { mutableStateOf<String?>(null) }
    var answered by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf(false) }
    LaunchedEffect(renew) {
        // Listening before asking: the engine may answer at once.
        launch(start = CoroutineStart.UNDISPATCHED) {
            Engine.events.collect { e ->
                if (e is Event.RecoveryKey) {
                    key = e.key
                    answered = true
                }
            }
        }
        Engine.send(if (renew) "dmNewRecovery" else "dmRecovery")
    }
    AlertDialog(
        onDismissRequest = { privates.dialog = null },
        icon = { Icon(Icons.Filled.Key, null) },
        title = { Text(tr("Your recovery key", "Sua chave de recuperação")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val k = key
                when {
                    !answered -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    k == null -> Text(
                        tr(
                            "Once you said it was somewhere safe, this phone stopped keeping it, so it can't be shown again. Lost it? Make a new one: the old one stops working.",
                            "Depois que você confirmou que guardou, este celular deixou de mantê-la, então ela não pode ser mostrada de novo. Perdeu? Gere uma nova: a antiga para de funcionar.",
                        ),
                    )
                    else -> {
                        Text(
                            tr(
                                "Your private messages are locked with a key that only exists on your devices. This code is the spare: with it you can read them on a new phone or computer, or after reinstalling Ciranda.",
                                "Suas mensagens privadas são trancadas com uma chave que só existe nos seus aparelhos. Este código é a cópia de segurança: com ele você consegue lê-las em um celular ou computador novo, ou depois de reinstalar o Ciranda.",
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            k, fontFamily = FontFamily.Monospace, fontSize = 17.sp, color = p.text, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.raised).padding(12.dp),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            TextButton({
                                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("recovery", k))
                                Toast.makeText(ctx, tr("Copied.", "Copiado."), Toast.LENGTH_SHORT).show()
                            }) { Text(tr("Copy", "Copiar")) }
                            TextButton({
                                privates.activity.saveTextFile(
                                    "ciranda-chave-de-recuperacao.txt",
                                    "Ciranda recovery key / Chave de recuperação do Ciranda\n\n$k\n\n" +
                                        tr("It opens your private messages on another device. Keep it to yourself.", "Ela abre suas mensagens privadas em outro aparelho. Não mostre a ninguém.") + "\n",
                                )
                            }) { Text(tr("Save as a file", "Salvar em um arquivo")) }
                        }
                        Spacer(Modifier.height(8.dp))
                        for (line in listOf(
                            tr("Keep it somewhere only you can get to: a password manager, or on paper.", "Guarde em um lugar que só você acessa: um gerenciador de senhas, ou no papel."),
                            tr("Never send it to anyone. Nobody from the server will ever ask for it.", "Nunca envie para ninguém. Ninguém do servidor vai pedir por ele."),
                            tr("Lose it and every device that has your key, and your old messages can't be opened again.", "Se perder o código e todos os aparelhos com a sua chave, as mensagens antigas não abrem mais."),
                        )) {
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text("•", color = p.muted, modifier = Modifier.width(14.dp))
                                Text(line, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).toggleable(kept, role = Role.Checkbox) { kept = it },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(kept, null)
                            Text(tr("I put my recovery key somewhere safe", "Guardei minha chave de recuperação em um lugar seguro"))
                        }
                        Text(
                            tr(
                                "Once you say so, this phone forgets it: it can't be shown again, only replaced by a new one.",
                                "Depois disso, este celular a esquece: ela não pode ser mostrada de novo, só trocada por uma nova.",
                            ),
                            color = p.muted, style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                answered && key == null -> Button({ privates.dialog = PrivateDialog.NewRecovery }) { Text(tr("Make a new one", "Gerar uma nova")) }
                key != null -> Button({
                    Engine.send("dmBackedUp")
                    privates.dialog = null
                }, enabled = kept) { Text(tr("Done", "Pronto")) }
                else -> {}
            }
        },
        dismissButton = { TextButton({ privates.dialog = null }) { Text(if (key != null) tr("Later", "Depois") else tr("Close", "Fechar")) } },
    )
}

/** The number both people compare, in a call or in person, to know nobody is in the middle. */
@Composable
private fun SafetyDialog(privates: Privates, s: SessionState, userId: Long) {
    val p = LocalPalette.current
    val name = s.user(userId)?.name ?: tr("them", "a pessoa")
    var number by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(false) }
    var answered by remember { mutableStateOf(false) }
    LaunchedEffect(userId) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            Engine.events.collect { e ->
                if (e is Event.SafetyNumber && e.userId == userId) {
                    number = e.number
                    verified = e.verified
                    answered = true
                }
            }
        }
        Engine.send("dmSafety", "userId" to userId)
    }
    AlertDialog(
        onDismissRequest = { privates.dialog = null },
        icon = { Icon(Icons.Filled.VerifiedUser, null) },
        title = { Text(tr("Safety number with $name", "Código de segurança com $name")) },
        text = {
            Column {
                Text(
                    tr(
                        "Your messages with $name are encrypted on your devices. To be sure they reach $name and nobody else, compare this number with theirs, in a call or in person. If it's the same on both screens, you're safe.",
                        "Suas mensagens com $name são criptografadas nos seus aparelhos. Para ter certeza de que chegam a $name e a mais ninguém, comparem este número, numa chamada ou pessoalmente. Se for igual nas duas telas, está tudo certo.",
                    ),
                )
                Spacer(Modifier.height(12.dp))
                val n = number
                when {
                    !answered -> LoadingIndicator()
                    n == null -> Text(
                        tr("There's no number yet: one of you hasn't set up private messages.", "Ainda não há número: um de vocês não ativou as mensagens privadas."),
                        color = p.muted,
                    )
                    else -> {
                        val groups = n.split(' ', '\n').filter { it.isNotBlank() }
                        FlowRow(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.raised).padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 3,
                        ) {
                            for (g in groups) Text(g, fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = p.text, modifier = Modifier.padding(horizontal = 6.dp))
                        }
                        if (verified) {
                            Text(tr("You marked this as checked.", "Você marcou como conferido."), color = p.success, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (number != null) {
                Button({
                    Engine.send("dmVerify", "userId" to userId, "on" to !verified)
                    privates.dialog = null
                }) { Text(if (verified) tr("Unmark as checked", "Desmarcar") else tr("They match", "São iguais")) }
            }
        },
        dismissButton = { TextButton({ privates.dialog = null }) { Text(tr("Close", "Fechar")) } },
    )
}

/** Settings → Privacy: where private messages stand on this phone, the recovery key, who is blocked. */
@Composable
fun PrivacySection(privates: Privates, s: SessionState) {
    val p = LocalPalette.current
    val st = s.private
    Text(
        when (st.state) {
            KeyState.Ready -> tr("On, on this phone", "Ativas neste celular")
            KeyState.Locked -> tr("Locked on this phone", "Trancadas neste celular")
            KeyState.Loading -> tr("Getting ready…", "Preparando…")
            KeyState.Unsupported -> tr("This server is too old for them", "Este servidor é antigo demais para elas")
            KeyState.Failed -> tr("Not available right now", "Indisponíveis agora")
        },
        color = if (st.state == KeyState.Ready) p.success else p.text,
    )
    Text(
        tr(
            "Private messages are encrypted on your devices before they leave. Only you and the person you talk to can open them: not the server, and not whoever runs it.",
            "Mensagens privadas são criptografadas nos seus aparelhos antes de sair. Só você e a pessoa com quem conversa conseguem abrir: nem o servidor, nem quem o administra.",
        ),
        color = p.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
    )
    when (st.state) {
        KeyState.Ready -> {
            Text(
                tr(
                    "Recovery key: it opens your private messages on another device, or after reinstalling. Keep it somewhere only you can get to.",
                    "Chave de recuperação: ela abre suas mensagens privadas em outro aparelho ou depois de reinstalar. Guarde em um lugar que só você acessa.",
                ),
                color = p.muted, style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.hasRecovery) {
                    OutlinedButton({ privates.dialog = PrivateDialog.Recovery() }) { Text(tr("Show my key", "Mostrar minha chave")) }
                }
                OutlinedButton({ privates.dialog = PrivateDialog.NewRecovery }) { Text(tr("Make a new one", "Gerar uma nova")) }
            }
        }
        KeyState.Locked -> OutlinedButton({ privates.dialog = PrivateDialog.Unlock }, Modifier.padding(vertical = 6.dp)) {
            Text(tr("Unlock with my recovery key", "Desbloquear com minha chave"))
        }
        else -> {}
    }
    if (st.state != KeyState.Unsupported) {
        Text(tr("Blocked people", "Pessoas bloqueadas"), color = p.text, modifier = Modifier.padding(top = 10.dp))
        if (st.blocked.isEmpty()) {
            Text(
                tr("Nobody. Block someone from the ⋮ menu of your conversation with them.", "Ninguém. Bloqueie alguém pelo menu ⋮ da conversa com a pessoa."),
                color = p.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        for (id in st.blocked) {
            val u = s.user(id)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(u?.name ?: "?", u?.avatarHash, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text(u?.name ?: "#$id", color = p.text, modifier = Modifier.weight(1f))
                TextButton({ Engine.send("dmBlock", "userId" to id, "on" to false) }) { Text(tr("Unblock", "Desbloquear")) }
            }
        }
    }
    HorizontalDivider(color = p.border, modifier = Modifier.padding(top = 8.dp))
}
