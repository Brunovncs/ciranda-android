package app.ciranda.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ciranda.android.BlockKind
import app.ciranda.android.Channel
import app.ciranda.android.ChatState
import app.ciranda.android.Engine
import app.ciranda.android.Event
import app.ciranda.android.MainActivity
import app.ciranda.android.Message
import app.ciranda.android.Pictures
import app.ciranda.android.SessionState
import app.ciranda.android.Span
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * What the composer is doing besides writing: answering a message, or editing one of yours. It
 * belongs to the channel (on its server) it was started in, and is no use in another. Held as one
 * thing, not as a flag for each, so that it cannot be both.
 */
private enum class AsideKind { Reply, Edit }

private data class Aside(val where: String, val kind: AsideKind, val message: Message)

private val aside = MutableStateFlow<Aside?>(null)

private fun where(s: SessionState, channelId: Long) = "${s.url}#$channelId"

/** Messages by the same person this close together share one header. */
private const val GROUP_WINDOW_MS = 7 * 60 * 1000L
private val QUICK = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

/** A message with what its place in the list decides: under the last one's header, or after a new day's divider. */
private class ChatRow(val message: Message, val grouped: Boolean, val newDay: Boolean)

/** The rows newest first, as the list draws them from the bottom; worked out once per update, not per redraw. */
private fun rowsOf(messages: List<Message>): List<ChatRow> {
    val zone = ZoneId.systemDefault()
    fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay()
    val rows = ArrayList<ChatRow>(messages.size)
    var before: Message? = null
    var beforeDay = 0L
    for (m in messages) {
        val d = day(m.createdAt)
        val newDay = before == null || d != beforeDay
        val grouped = before != null && !newDay && before.userId == m.userId && m.reply == null &&
            m.createdAt - before.createdAt < GROUP_WINDOW_MS
        rows += ChatRow(m, grouped, newDay)
        before = m
        beforeDay = d
    }
    return rows.asReversed()
}

@Composable
fun ChatView(activity: MainActivity, s: SessionState, chat: ChatState) {
    val p = LocalPalette.current
    // Each channel opens at its newest message, not where the last one was left.
    val list = remember(chat.channelId) { LazyListState() }
    var viewer by remember { mutableStateOf<String?>(null) }
    val rows = remember(chat.messages) { rowsOf(chat.messages) }
    LaunchedEffect(chat.channelId, list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val c = Engine.chat.value ?: return@collect
            if (c.moreBefore && !c.loadingOlder && last >= c.messages.size - 10) Engine.send("older")
        }
    }
    // Scrolled to the newest when a message arrives while at the bottom.
    LaunchedEffect(chat.messages.lastOrNull()?.id) {
        if (list.firstVisibleItemIndex <= 1) list.animateScrollToItem(0)
    }
    if (!chat.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (chat.error != null) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(chat.error, color = p.danger, textAlign = TextAlign.Center)
                    TextButton({ Engine.send("open", "channelId" to chat.channelId) }) {
                        Text(tr("Try again", "Tentar de novo"))
                    }
                }
            } else {
                LoadingIndicator()
            }
        }
        return
    }
    // Far enough up that the newest is out of sight: the way back is offered.
    val away by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 3 } }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
            items(rows, key = { it.message.id }, contentType = { "message" }) { row ->
                Column {
                    if (row.newDay) DayDivider(row.message.createdAt)
                    MessageRow(s, row.message, row.grouped, onPicture = { viewer = it })
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
        AnimatedVisibility(
            away, Modifier.align(Alignment.BottomEnd).padding(12.dp),
            enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.8f),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.8f),
        ) {
            Box(
                Modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(p.surface).border(1.dp, p.border, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = tr("Go to the latest message", "Ir para a mensagem mais recente")) {
                        scope.launch { list.animateScrollToItem(0) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, tr("Latest messages", "Mensagens mais recentes"), tint = p.text)
            }
        }
    }
    viewer?.let { hash -> PictureViewer(hash) { viewer = null } }
}

@Composable
private fun DayDivider(at: Long) {
    val p = LocalPalette.current
    val day = remember(at) { DateFormat.getDateInstance(DateFormat.LONG).format(Date(at)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = p.border)
        Text(day, color = p.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = p.border)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(s: SessionState, m: Message, grouped: Boolean, onPicture: (String) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val mine = m.userId == s.me.id
    val user = s.user(m.userId)
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClickLabel = tr("Message options", "Opções da mensagem"),
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
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
                    val time = remember(m.createdAt) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.createdAt)) }
                    Row {
                        Text(
                            m.author, color = nameTextColor(m.author), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(time, color = p.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alignByBaseline())
                    }
                }
                if (m.body.isNotEmpty()) Body(m)
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
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) {
                            menu = false
                            val on = m.reactions.none { it.emoji == e && s.me.id in it.userIds }
                            Engine.send("react", "id" to m.id, "emoji" to e, "on" to on)
                        }.padding(8.dp),
                    )
                }
            }
            DropdownMenuItem(
                { Text(tr("Reply", "Responder")) },
                { menu = false; aside.value = Aside(where(s, m.channelId), AsideKind.Reply, m) },
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
                    { menu = false; aside.value = Aside(where(s, m.channelId), AsideKind.Edit, m) },
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
private fun Body(m: Message) {
    val p = LocalPalette.current
    val size = if (m.emojiOnly) 34.sp else MaterialTheme.typography.bodyLarge.fontSize
    for (b in m.blocks) {
        when (b.kind) {
            BlockKind.Code -> Text(
                b.code ?: "", fontFamily = FontFamily.Monospace, color = p.text, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.raised)
                    .horizontalScroll(rememberScrollState()).padding(8.dp),
            )
            // The bar is as tall as the quote: the row is as tall as its text, and the bar takes that.
            BlockKind.Quote -> Row(Modifier.padding(vertical = 2.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(p.strong))
                Spacer(Modifier.width(8.dp))
                Column { for (l in b.lines) Line(l, size) }
            }
            BlockKind.List -> Column {
                b.lines.forEachIndexed { i, l ->
                    Row {
                        Text(if (b.ordered) "${i + 1}." else "•", color = p.muted, modifier = Modifier.width(20.dp))
                        Line(l, size)
                    }
                }
            }
            BlockKind.Heading -> for (l in b.lines) Line(l, if (b.level <= 1) 22.sp else 19.sp, bold = true)
            BlockKind.Paragraph -> for (l in b.lines) Line(l, size)
        }
    }
}

/** A line of spans as styled text, and the pictures its custom emojis stand in for. */
private class Styled(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

private fun styled(spans: List<Span>, bold: Boolean, p: Palette): Styled {
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
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
    return Styled(text, inline)
}

@Composable
private fun Line(spans: List<Span>, size: TextUnit, bold: Boolean = false) {
    val p = LocalPalette.current
    // Built when the line changes, not on every redraw of the row it is in.
    val line = remember(spans, bold, p) { styled(spans, bold, p) }
    val base = MaterialTheme.typography.bodyLarge
    // Bigger text (a heading, an emoji on its own) needs a taller line than the body's, or it is cut.
    val lineHeight = if (size.value <= base.fontSize.value) base.lineHeight else (size.value * 1.25f).sp
    Text(line.text, color = p.text, fontSize = size, inlineContent = line.inline, style = base.copy(fontSize = size, lineHeight = lineHeight))
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
                .background(p.raised).clickable(role = Role.Button, onClickLabel = tr("Open picture", "Abrir imagem")) { onPicture(hash) }
                .semantics { contentDescription = m.attachmentName ?: "" },
            contentAlignment = Alignment.Center,
        ) {
            if (pic != null) {
                Image(pic, null, contentScale = ContentScale.Fit)
            } else {
                CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
            }
        }
        return
    }
    Row(
        Modifier.padding(top = 4.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, p.border, RoundedCornerShape(10.dp))
            .background(p.surface).clickable(role = Role.Button, onClickLabel = tr("Download", "Baixar")) {
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
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in m.reactions) {
            val mine = s.me.id in r.userIds
            val custom = s.emojis.firstOrNull { ":${it.name}:" == r.emoji || it.name == r.emoji }
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.heightIn(min = 32.dp).clip(shape)
                    .background(if (mine) p.accent.copy(alpha = 0.22f) else p.raised)
                    .border(1.dp, if (mine) p.accent else Color.Transparent, shape)
                    .toggleable(mine, role = Role.Switch) { Engine.send("react", "id" to m.id, "emoji" to r.emoji, "on" to !mine) }
                    .padding(horizontal = 10.dp),
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
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = tr("Close", "Fechar"), onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Pictures.get(hash)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            // Tapping anywhere closes it; this is for whoever does not know that.
            IconButton(
                onClose, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(4.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
            ) { Icon(Icons.Filled.Close, tr("Close", "Fechar")) }
        }
    }
}

@Composable
fun Composer(activity: MainActivity, s: SessionState, channel: Channel) {
    val p = LocalPalette.current
    val here = where(s, channel.id)
    // The draft survives the screen turning, per channel.
    var text by rememberSaveable(here) { mutableStateOf("") }
    val now = aside.collectAsState().value?.takeIf { it.where == here }
    val reply = now?.takeIf { it.kind == AsideKind.Reply }?.message
    val edit = now?.takeIf { it.kind == AsideKind.Edit }?.message
    var uploading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
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
    // Answering or editing is for writing: the keyboard comes up with it.
    LaunchedEffect(now?.message?.id, now?.kind) {
        if (now != null) {
            try {
                focus.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }
    val canSend = text.isNotBlank()
    val sendTint by animateColorAsState(if (canSend) p.accent else p.muted, tween(120), label = "send")
    Column(Modifier.fillMaxWidth().background(p.surface).topBorder(p.border).navigationBarsPadding().imePadding()) {
        val banner = when {
            edit != null -> tr("Editing your message", "Editando sua mensagem")
            reply != null -> tr("Replying to ${reply.author}", "Respondendo a ${reply.author}")
            uploading -> tr("Sending file…", "Enviando arquivo…")
            else -> null
        }
        banner?.let {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = p.muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(top = 6.dp))
                if (!uploading) {
                    IconButton({
                        aside.value = null
                        if (edit != null) text = ""
                    }) {
                        Icon(Icons.Filled.Close, tr("Cancel", "Cancelar"), tint = p.muted, modifier = Modifier.size(18.dp))
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
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp).focusRequester(focus),
                placeholder = { Text(tr("Message #${channel.name}", "Mensagem em #${channel.name}"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
                    aside.value = null
                },
                enabled = canSend,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, tr("Send", "Enviar"), tint = sendTint)
            }
        }
    }
}
