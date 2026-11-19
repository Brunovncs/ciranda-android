package app.ciranda.android

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import app.ciranda.android.ui.tr

/**
 * Mentions as notifications: one per channel, its messages piled up in it until the channel is
 * opened or the notification dismissed. The engine says what calls you (`rust/src/engine/watch.rs`).
 */
object Notifier {
    private data class Line(val author: String, val body: String, val at: Long)

    private val piles = HashMap<String, MutableList<Line>>()

    /** A mention can come twice while the server on screen changes sockets; it shows once. */
    private val shown = ArrayDeque<String>()

    private fun tag(server: String, username: String, channelId: Long) =
        "mention:${server.trimEnd('/').lowercase()}|${username.lowercase()}|$channelId"

    fun show(ctx: Context, e: Event.Mention) {
        val key = "${e.server.trimEnd('/').lowercase()}#${e.messageId}"
        if (key in shown) return
        shown.addLast(key)
        if (shown.size > 200) shown.removeFirst()
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val tag = tag(e.server, e.username, e.channelId)
        // Dismissed since: the pile starts over.
        val active = ctx.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == tag }
        if (!active) piles.remove(tag)
        val pile = piles.getOrPut(tag) { mutableListOf() }
        pile += Line(e.author, e.body, System.currentTimeMillis())
        while (pile.size > 8) pile.removeAt(0)

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(tr("You", "Você")).build())
            .setConversationTitle("#${e.channel} · ${e.serverName}")
            .setGroupConversation(true)
        for (l in pile) style.addMessage(l.body, l.at, Person.Builder().setName(l.author).build())
        val open = PendingIntent.getActivity(
            ctx, tag.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .setAction(OPEN)
                .putExtra(SERVER, e.server)
                .putExtra(USERNAME, e.username)
                .putExtra(CHANNEL, e.channelId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CirandaApp.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle("${e.author} · #${e.channel}")
            .setContentText(e.body)
            .setSubText(e.serverName)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(tag, 0, n)
        } catch (_: SecurityException) {
        }
    }

    private fun privateTag(server: String, username: String, conversationId: Long) =
        "dm:${server.trimEnd('/').lowercase()}|${username.lowercase()}|$conversationId"

    /**
     * A private message: who sent it, and how many came, never what they say (it is sealed, and
     * the lock screen is no place for it).
     */
    fun showPrivate(ctx: Context, e: Event.PrivateMessage) {
        val key = "${e.server.trimEnd('/').lowercase()}#dm${e.messageId}"
        if (key in shown) return
        shown.addLast(key)
        if (shown.size > 200) shown.removeFirst()
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val tag = privateTag(e.server, e.username, e.conversationId)
        val active = ctx.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == tag }
        val count = if (active) (counts[tag] ?: 0) + 1 else 1
        counts[tag] = count
        val open = PendingIntent.getActivity(
            ctx, tag.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .setAction(OPEN)
                .putExtra(SERVER, e.server)
                .putExtra(USERNAME, e.username)
                .putExtra(CONVERSATION, e.conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (count == 1) {
            tr("sent you a private message", "te mandou uma mensagem privada")
        } else {
            tr("sent you $count private messages", "te mandou $count mensagens privadas")
        }
        val n = NotificationCompat.Builder(ctx, CirandaApp.CHANNEL_PRIVATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(e.author)
            .setContentText(text)
            .setSubText(e.serverName)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(tag, 0, n)
        } catch (_: SecurityException) {
        }
    }

    private val counts = HashMap<String, Int>()

    /** The conversation is on screen: what came in it has been seen. */
    fun seenPrivate(ctx: Context, server: String, username: String, conversationId: Long) {
        val tag = privateTag(server, username, conversationId)
        counts.remove(tag)
        NotificationManagerCompat.from(ctx).cancel(tag, 0)
    }

    /** The channel is on screen: what called you there has been seen. */
    fun seen(ctx: Context, server: String, username: String, channelId: Long) {
        val tag = tag(server, username, channelId)
        piles.remove(tag)
        NotificationManagerCompat.from(ctx).cancel(tag, 0)
    }

    const val OPEN = "app.ciranda.android.OPEN_MENTION"
    const val SERVER = "server"
    const val USERNAME = "username"
    const val CHANNEL = "mentionChannel"
    const val CONVERSATION = "privateConversation"
}
