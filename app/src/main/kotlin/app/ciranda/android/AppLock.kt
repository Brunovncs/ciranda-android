package app.ciranda.android

import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.ciranda.android.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ciranda locked behind the phone's own unlock (a fingerprint, the face, or its PIN), when the
 * setting is on. When it locks, and when it does not:
 *
 * - When the app starts, and when it comes back after being away longer than the setting says.
 * - Not when it comes back from something it opened itself (a file to send, the share sheet, a
 *   system setting, the installer, the phone's PIN screen), up to [OURS_GRACE_MS] away.
 * - Not on a turn of the screen (the activity made again).
 * - Not with no server signed in to: there is nothing behind it then.
 * - Not once the phone can no longer check (the fingerprint and the screen lock removed): the
 *   setting turns itself off rather than shut you out.
 */
object AppLock {
    /** Whether the lock is up. */
    val locked = MutableStateFlow(false)

    /** Counts coming back to a locked app, when the prompt shows again by itself. */
    val returns = MutableStateFlow(0)

    private const val OURS_GRACE_MS = 5 * 60 * 1000L
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private var awaySince: Long? = null
    private var oursAt = 0L
    private var leftForOurs = false

    /** Whether this phone can check who holds it at all. */
    fun available(ctx: android.content.Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun onAppStart() {
        if (Prefs.lock.value) locked.value = true
    }

    /** About to open something of ours that covers the app. */
    fun expectReturn() {
        oursAt = SystemClock.elapsedRealtime()
    }

    fun onStop(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        val now = SystemClock.elapsedRealtime()
        awaySince = now
        leftForOurs = now - oursAt < 3000
    }

    /** Comes back to the front: locked if it was away too long, and asked again if locked. */
    fun onStart() {
        val since = awaySince ?: return
        awaySince = null
        if (!Prefs.lock.value) return
        val away = SystemClock.elapsedRealtime() - since
        val ours = leftForOurs && away < OURS_GRACE_MS
        if (ours) return
        if (away >= Prefs.lockAfterSec.value * 1000L) locked.value = true
        if (locked.value) returns.value += 1
    }

    /** Back in the foreground for good: what was opened has been come back from. */
    fun onResume() {
        oursAt = 0L
        leftForOurs = false
    }

    /**
     * The phone's prompt. `done` gets null when it let you in, or why it didn't (null too for a
     * prompt put away, which says nothing worth showing).
     */
    fun authenticate(activity: FragmentActivity, title: String, done: (ok: Boolean, error: String?) -> Unit) {
        if (!available(activity)) {
            done(false, tr("This phone has no fingerprint, face or screen lock set up.", "Este celular não tem digital, rosto nem bloqueio de tela configurados."))
            return
        }
        expectReturn()
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true, null)

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    val quiet = code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    done(false, if (quiet) null else message.toString())
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(tr("Fingerprint, face or the phone's PIN", "Digital, rosto ou o PIN do celular"))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            done(false, e.message)
        }
    }

    /** Turned on only once the phone has shown it can check. */
    fun enable(activity: FragmentActivity, done: (String?) -> Unit) {
        authenticate(activity, tr("Turn on the lock", "Ativar o bloqueio")) { ok, error ->
            if (ok) {
                Prefs.setLock(true)
                Prefs.setLockOffered()
            }
            done(error)
        }
    }

    fun disable() {
        Prefs.setLock(false)
        locked.value = false
    }

    /** Let in, or (the phone can no longer check) the setting gone rather than you shut out. */
    fun unlock(activity: FragmentActivity, done: (String?) -> Unit) {
        if (!available(activity)) {
            disable()
            done(tr(
                "The lock is off: this phone no longer has a fingerprint, face or screen lock.",
                "O bloqueio foi desligado: este celular não tem mais digital, rosto nem bloqueio de tela.",
            ))
            return
        }
        authenticate(activity, tr("Unlock Ciranda", "Desbloquear o Ciranda")) { ok, error ->
            if (ok) locked.value = false
            done(error)
        }
    }
}
