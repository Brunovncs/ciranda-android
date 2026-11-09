package app.ciranda.android

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ciranda.android.ui.tr

/**
 * Keeps Ciranda connected with the app closed, so a mention arrives when it is written: Android
 * freezes an app in the background, and only a foreground service, with a notification of its
 * own, keeps it running. That one is silent and at the bottom, and its channel can be hidden. The
 * engine does the listening (`rust/src/engine/watch.rs`); this only keeps it alive.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CirandaApp.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr("Listening for mentions", "Atento às suas menções"))
            .setContentText(tr("Ciranda tells you when someone calls you.", "Ciranda avisa quando alguém te marcar."))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // Restarted by the system where Android no longer lets it: the next time the app is
            // opened starts it again.
            Log.w("ciranda", "connection service could not start: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /** After the phone starts, and after an update replaced the app. */
    class Restart : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            // Whether there is a server signed in to is the engine's to say, once it has read the
            // settings; the app stops this again if there is none.
            if (Prefs.notify.value) start(context)
        }
    }

    companion object {
        private const val ID = 2

        @Volatile
        private var running = false

        fun start(ctx: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ConnectionService::class.java))
            } catch (e: Exception) {
                // Not allowed from the background; opening the app starts it.
                Log.w("ciranda", "connection service not started: $e")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ConnectionService::class.java))
            running = false
        }
    }
}
