package app.ciranda.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ciranda.android.Channel
import app.ciranda.android.ChatState
import app.ciranda.android.Engine
import app.ciranda.android.Event
import app.ciranda.android.MainActivity
import app.ciranda.android.Message
import app.ciranda.android.Pictures
import app.ciranda.android.SessionState
import app.ciranda.android.Span
import app.ciranda.android.TextBlock
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * What the composer is doing besides writing: answering a message, or editing one of yours. Each
 * belongs to the channel (on its server) it was started in, and is no use in another.
 */
private data class Aside(val where: String, val message: Message)
private val replying = MutableStateFlow<Aside?>(null)
private val editing = MutableStateFlow<Aside?>(null)

private fun where(s: SessionState, channelId: Long) = "${s.url}#$channelId"

/** Messages by the same person this close together share one header. */
private const val GROUP_WINDOW_MS = 7 * 60 * 1000L
private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

@Composable
fun ChatView(activity: MainActivity, s: SessionState, chat: ChatState) {
    val p = LocalPalette.current
    // Each channel opens at its newest message, not where the last one was left.
    val list = remember(chat.channelId) { LazyListState() }
    var viewer by remember { mutableStateOf<String?>(null) }
    val newest = chat.messages.asReversed()
    LaunchedEffect(chat.channelId, list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val c = Engine.chat.value ?: return@collect
            if (c.moreBefore && !c.loadingOlder && last >= c.messages.size - 10) Engine.send("older")
        }
    }
    // Scrolled to the newest when a message arrives while at the bottom.
    LaunchedEffect(chat.messages.lastOrNull()?.id) {
        if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0)
    }
    if (!chat.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (chat.error != null) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(chat.error, color = p.danger, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    androidx.compose.material3.TextButton({ Engine.send("open", "channelId" to chat.channelId) }) {
                        Text(tr("Try again", "Tentar de novo"))
                    }
                }
            } else {
                CircularProgressIndicator()
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
        itemsIndexed(newest, key = { _, m -> m.id }) { i, m ->
            val before = newest.getOrNull(i + 1)
            val grouped = before != null && before.userId == m.userId && m.reply == null &&
                m.createdAt - before.createdAt < GROUP_WINDOW_MS && sameDay(before.createdAt, m.createdAt)
            Column {
                if (before == null || !sameDay(before.createdAt, m.createdAt)) DayDivider(m.createdAt)
                MessageRow(s, m, grouped, onPicture = { viewer = it })
            }
        }
        item(key = "top") {
            if (chat.loadingOlder) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
            } else if (!chat.moreBefore) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        tr("This is the start of #${s.channel(chat.channelId)?.name ?: ""}.", "Este é o começo de #${s.channel(chat.channelId)?.name ?: ""}."),
                        color = p.muted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    viewer?.let { hash -> PictureViewer(hash) { viewer = null } }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DayDivider(at: Long) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).heightIn(min = 1.dp, max = 1.dp).background(p.border))
        Text(
            DateFormat.getDateInstance(DateFormat.LONG).format(Date(at)), color = p.muted,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).heightIn(min = 1.dp, max = 1.dp).background(p.border))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(s: SessionState, m: Message, grouped: Boolean, onPicture: (String) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val mine = m.userId == s.me.id
    val user = s.user(m.userId)
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(start = 12.dp, end = 12.dp, top = if (grouped) 1.dp else 10.dp, bottom = 1.dp),
        ) {
            if (grouped) {
                Spacer(Modifier.width(48.dp))
            } else {
                Avatar(m.author, user?.avatarHash ?: m.avatar, size = 38.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                m.reply?.let { r -> ReplyLine(s, r.userId, r.body.ifEmpty { r.attachmentName ?: "" }) }
                if (!grouped) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(m.author, color = nameColor(m.author), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.createdAt)), color = p.muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (m.body.isNotEmpty()) Body(s, m)
                if (m.edited) Text(tr("(edited)", "(editada)"), color = p.muted, style = MaterialTheme.typography.labelSmall)
                m.attachmentHash?.let { Attachment(m, it, onPicture) }
                if (m.reactions.isNotEmpty()) Reactions(s, m)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                for (e in QUICK) {
                    Text(
                        e, fontSize = 22.sp,
                        modifier = Modifier.clip(CircleShape).clickable {
                            menu = false
                            val on = m.reactions.none { it.emoji == e && s.me.id in it.userIds }
                            Engine.send("react", "id" to m.id, "emoji" to e, "on" to on)
                        }.padding(6.dp),
                    )
                }
            }
            DropdownMenuItem(
                { Text(tr("Reply", "Responder")) },
                { menu = false; editing.value = null; replying.value = Aside(where(s, m.channelId), m) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
            )
            if (m.body.isNotEmpty()) {
                DropdownMenuItem(
                    { Text(tr("Copy text", "Copiar texto")) },
                    {
                        menu = false
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", m.body))
                    },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                )
            }
            if (mine) {
                DropdownMenuItem(
                    { Text(tr("Edit", "Editar")) },
                    { menu = false; replying.value = null; editing.value = Aside(where(s, m.channelId), m) },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                )
            }
            if (mine || s.me.admin) {
                DropdownMenuItem(
                    { Text(tr("Delete", "Apagar"), color = p.danger) },
                    { menu = false; Engine.send("delete", "id" to m.id) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = p.danger) },
                )
            }
        }
    }
}

@Composable
private fun ReplyLine(s: SessionState, userId: Long?, text: String) {
    val p = LocalPalette.current
    Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.Reply, null, tint = p.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            "${s.user(userId)?.name ?: tr("someone", "alguém")}: $text", color = p.muted, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Body(s: SessionState, m: Message) {
    val p = LocalPalette.current
    val size = if (m.emojiOnly) 34.sp else MaterialTheme.typography.bodyLarge.fontSize
    for (b in m.blocks) {
        when (b.kind) {
            "code" -> Text(
                b.code ?: "", fontFamily = FontFamily.Monospace, color = p.text, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.raised)
                    .horizontalScroll(rememberScrollState()).padding(8.dp),
            )
            "q" -> Row(Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.width(3.dp).heightIn(min = 18.dp).background(p.strong))
                Spacer(Modifier.width(8.dp))
                Column { for (l in b.lines) Line(s, l, size) }
            }
            "l" -> Column {
                b.lines.forEachIndexed { i, l ->
                    Row {
                        Text(if (b.ordered) "${i + 1}." else "•", color = p.muted, modifier = Modifier.width(20.dp))
                        Line(s, l, size)
                    }
                }
            }
            "h" -> for (l in b.lines) Line(s, l, if (b.level <= 1) 22.sp else 19.sp, bold = true)
            else -> for (l in b.lines) Line(s, l, size)
        }
    }
}

@Composable
private fun Line(s: SessionState, spans: List<Span>, size: androidx.compose.ui.unit.TextUnit, bold: Boolean = false) {
    val p = LocalPalette.current
    val inline = mutableMapOf<String, InlineTextContent>()
    val text: AnnotatedString = buildAnnotatedString {
        for ((i, sp) in spans.withIndex()) {
            val style = SpanStyle(
                fontWeight = if (sp.bold || bold) FontWeight.Bold else null,
                fontStyle = if (sp.italic) FontStyle.Italic else null,
                textDecoration = if (sp.strike) TextDecoration.LineThrough else null,
            )
            when {
                sp.custom != null -> {
                    val id = "e$i"
                    val hash = sp.custom
                    inline[id] = InlineTextContent(Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
                        val pic = Pictures.get(hash)
                        if (pic != null) Image(pic, sp.emojiName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    appendInlineContent(id, ":${sp.emojiName}:")
                }
                sp.code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = p.raised)) { append(sp.code) }
                sp.mention != null -> withStyle(
                    SpanStyle(color = p.accent, fontWeight = FontWeight.SemiBold, background = if (sp.mentionsMe) p.accent.copy(alpha = 0.18f) else Color.Transparent),
                ) { append(sp.mention) }
                sp.url != null -> withLink(LinkAnnotation.Url(sp.url, TextLinkStyles(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline)))) {
                    withStyle(style) { append(sp.text ?: sp.url) }
                }
                else -> withStyle(style) { append(sp.text ?: "") }
            }
        }
    }
    Text(text, color = p.text, fontSize = size, inlineContent = inline, style = MaterialTheme.typography.bodyLarge.copy(fontSize = size))
}

@Composable
private fun Attachment(m: Message, hash: String, onPicture: (String) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val image = m.mediaType == "image" && (m.attachmentBytes ?: 0) <= 64L shl 20
    if (image && !Pictures.failed(hash)) {
        val pic = Pictures.get(hash)
        Box(
            Modifier.padding(top = 4.dp).widthIn(max = 300.dp).heightIn(min = 80.dp, max = 320.dp).clip(RoundedCornerShape(10.dp))
                .background(p.raised).clickable { onPicture(hash) },
            contentAlignment = Alignment.Center,
        ) {
            if (pic != null) {
                Image(pic, m.attachmentName, contentScale = ContentScale.Fit)
            } else {
                CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
            }
        }
        return
    }
    Row(
        Modifier.padding(top = 4.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, p.border, RoundedCornerShape(10.dp))
            .background(p.surface).clickable {
                Engine.send("file", "hash" to hash, "name" to m.attachmentName, "size" to m.attachmentBytes)
                android.widget.Toast.makeText(ctx, tr("Downloading…", "Baixando…"), android.widget.Toast.LENGTH_SHORT).show()
            }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = p.accent)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(m.attachmentName ?: tr("File", "Arquivo"), color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            m.attachmentBytes?.let { Text(formatBytes(it), color = p.muted, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Reactions(s: SessionState, m: Message) {
    val p = LocalPalette.current
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in m.reactions) {
            val mine = s.me.id in r.userIds
            val custom = s.emojis.firstOrNull { ":${it.name}:" == r.emoji || it.name == r.emoji }
            Row(
                Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (mine) p.accent.copy(alpha = 0.22f) else p.raised)
                    .border(1.dp, if (mine) p.accent else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { Engine.send("react", "id" to m.id, "emoji" to r.emoji, "on" to !mine) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pic = custom?.let { Pictures.get(it.hash) }
                if (pic != null) Image(pic, r.emoji, Modifier.size(18.dp)) else Text(r.emoji, fontSize = 15.sp)
                Spacer(Modifier.width(4.dp))
                Text("${r.count}", color = if (mine) p.accent else p.muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun PictureViewer(hash: String, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Pictures.get(hash)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
    }
}

@Composable
fun Composer(activity: MainActivity, s: SessionState, channel: Channel) {
    val p = LocalPalette.current
    val here = where(s, channel.id)
    // The draft survives the screen turning, per channel.
    var text by rememberSaveable(here) { mutableStateOf("") }
    val reply = replying.collectAsState().value?.takeIf { it.where == here }?.message
    val edit = editing.collectAsState().value?.takeIf { it.where == here }?.message
    var uploading by remember { mutableStateOf(false) }
    LaunchedEffect(here) {
        Engine.events.collect { e ->
            when {
                e is Event.Uploading -> {
                    uploading = e.on
                    // The caption went with the file.
                    if (e.on) text = ""
                }
                // Not sent: the text comes back, unless something else was typed since.
                e is Event.SendFailed && e.channelId == channel.id && text.isBlank() -> text = e.body
            }
        }
    }
    LaunchedEffect(edit?.id) { edit?.let { text = it.body } }
    Column(Modifier.fillMaxWidth().background(p.surface).navigationBarsPadding().imePadding()) {
        val banner = when {
            edit != null -> tr("Editing your message", "Editando sua mensagem")
            reply != null -> tr("Replying to ${reply.author}", "Respondendo a ${reply.author}")
            uploading -> tr("Sending file…", "Enviando arquivo…")
            else -> null
        }
        banner?.let {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = p.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (!uploading) {
                    IconButton({ replying.value = null; editing.value = null; if (edit != null) text = "" }) {
                        Icon(Icons.Filled.Close, null, tint = p.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
            IconButton({ activity.pickAttachment(channel.id, text) }, enabled = edit == null && !uploading) {
                Icon(Icons.Filled.AddCircle, tr("Send a file", "Enviar arquivo"), tint = p.muted)
            }
            TextField(
                text, { text = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp),
                placeholder = { Text(tr("Message #${channel.name}", "Mensagem em #${channel.name}"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = RoundedCornerShape(22.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = p.raised, unfocusedContainerColor = p.raised,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(
                {
                    val body = text.trim()
                    if (body.isEmpty()) return@IconButton
                    val e = edit
                    val r = reply
                    when {
                        e != null -> Engine.send("edit", "id" to e.id, "body" to body)
                        r != null -> Engine.send("send", "channelId" to channel.id, "body" to body, "replyTo" to r.id)
                        else -> Engine.send("send", "channelId" to channel.id, "body" to body)
                    }
                    text = ""
                    replying.value = null
                    editing.value = null
                },
                enabled = text.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, tr("Send", "Enviar"), tint = if (text.isNotBlank()) p.accent else p.muted)
            }
        }
    }
}

@Suppress("unused")
private fun TextBlock.isEmpty() = lines.isEmpty() && code.isNullOrEmpty()
