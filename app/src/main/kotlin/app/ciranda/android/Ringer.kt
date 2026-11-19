package app.ciranda.android

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import app.ciranda.android.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A private call ringing for you (`Ring`, as the engine says it in its state): with Ciranda on
 * screen, the call screen comes up at once; away, a notification rings, with the call screen as
 * its full-screen intent over the lock screen, and Answer and Decline on it. Android 14 and later
 * let an app take the whole screen only when allowed (`canUseFullScreenIntent`); without that it
 * is a heads-up notification that rings the same.
 */
object Ringer {
    /** How many of Ciranda's screens are started: none is "away". */
    private val started = MutableStateFlow(0)

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        CirandaApp.scope.launch {
            combine(Engine.state.map { it.ring }.distinctUntilChanged(), started.map { it > 0 }.distinctUntilChanged()) { r, on -> r to on }
                .collect { (ring, onScreen) -> show(app, ring, onScreen) }
        }
    }

    fun screenStarted() {
        started.value += 1
    }

    fun screenStopped() {
        started.value = (started.value - 1).coerceAtLeast(0)
    }

    private fun show(ctx: Context, ring: Ring?, onScreen: Boolean) {
        val nm = NotificationManagerCompat.from(ctx)
        if (ring == null) {
            nm.cancel(TAG, 0)
            return
        }
        if (onScreen) {
            // The call screen rings for itself, over whatever is showing.
            nm.cancel(TAG, 0)
            if (!CallActivity.showing && !ring.answering) CallActivity.open(ctx)
            return
        }
        if (ring.answering) return
        post(ctx, ring)
    }

    private fun post(ctx: Context, ring: Ring) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val screen = PendingIntent.getActivity(
            ctx, 1, CallActivity.intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Answering opens the call screen, which asks for the microphone if it has to: a call's
        // microphone can only be taken with Ciranda in front.
        val answer = PendingIntent.getActivity(
            ctx, 2, CallActivity.intent(ctx).setAction(CallActivity.ANSWER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            ctx, 3, Intent(ctx, Decline::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(ctx, CirandaApp.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ring.name)
            .setContentText(tr("Calling you · end-to-end encrypted", "Te ligando · criptografia de ponta a ponta"))
            .setSubText(ring.serverName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(screen)
        if (canTakeScreen(ctx)) {
            // The call style is for a ring that can take the screen; the system holds it to that.
            val caller = Person.Builder().setName(ring.name).setImportant(true).build()
            builder.setFullScreenIntent(screen, true).setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
        } else {
            builder.addAction(0, tr("Decline", "Recusar"), decline).addAction(0, tr("Answer", "Atender"), answer)
        }
        val n = builder.build()
        // Rings and vibrates until answered, declined or over, as a phone call does.
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        try {
            nm.notify(TAG, 0, n)
        } catch (_: SecurityException) {
        }
    }

    /** Whether a ring may take the whole screen; before Android 14 it always may. */
    fun canTakeScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** A call that rang while away, and nobody picked up. */
    fun missed(ctx: Context, e: Event.MissedCall) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val tag = "missed:${e.server.trimEnd('/').lowercase()}|${e.username.lowercase()}|${e.conversationId}"
        val open = PendingIntent.getActivity(
            ctx, tag.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .setAction(Notifier.OPEN)
                .putExtra(Notifier.SERVER, e.server)
                .putExtra(Notifier.USERNAME, e.username)
                .putExtra(Notifier.CONVERSATION, e.conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CirandaApp.CHANNEL_PRIVATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(e.author)
            .setContentText(tr("Missed call", "Chamada perdida"))
            .setSubText(e.serverName)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(tag, 0, n)
        } catch (_: SecurityException) {
        }
    }

    /** The notification's Decline. */
    class Decline : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val r = Engine.state.value.ring ?: return
            Engine.send("callDecline", "server" to r.server, "username" to r.username, "conversationId" to r.conversationId)
        }
    }

    private const val TAG = "ring"
}
