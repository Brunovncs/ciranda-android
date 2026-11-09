package app.ciranda.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Settings of the phone's own, beside the engine's (which are the desktop's, and shared with it). */
object Prefs {
    private lateinit var sp: SharedPreferences

    /** Mentions as notifications with the app closed. */
    val notify = MutableStateFlow(true)

    /** The app locked behind the phone's unlock (`AppLock`), and how long away before it locks. */
    val lock = MutableStateFlow(false)
    val lockAfterSec = MutableStateFlow(60)

    /** Whether the lock was offered after a sign-in already, taken or not: it is offered once. */
    val lockOffered = MutableStateFlow(false)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("ciranda", Context.MODE_PRIVATE)
        notify.value = sp.getBoolean("notifyMentions", true)
        lock.value = sp.getBoolean("lock", false)
        lockAfterSec.value = sp.getInt("lockAfterSec", 60)
        lockOffered.value = sp.getBoolean("lockOffered", false)
    }

    fun setNotify(on: Boolean) {
        sp.edit().putBoolean("notifyMentions", on).apply()
        notify.value = on
    }

    fun setLock(on: Boolean) {
        sp.edit().putBoolean("lock", on).apply()
        lock.value = on
    }

    fun setLockAfter(seconds: Int) {
        sp.edit().putInt("lockAfterSec", seconds).apply()
        lockAfterSec.value = seconds
    }

    fun setLockOffered() {
        sp.edit().putBoolean("lockOffered", true).apply()
        lockOffered.value = true
    }
}
