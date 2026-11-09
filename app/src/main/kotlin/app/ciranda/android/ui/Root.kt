package app.ciranda.android.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.withResumed
import app.ciranda.android.AppLock
import app.ciranda.android.AppState
import app.ciranda.android.Engine
import app.ciranda.android.Event
import app.ciranda.android.MainActivity
import app.ciranda.android.Prefs
import app.ciranda.android.R
import kotlinx.coroutines.delay

@Composable
fun Root(activity: MainActivity) {
    val state by Engine.state.collectAsState()
    val locked by AppLock.locked.collectAsState()
    var offerLock by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Engine.events.collect { e ->
            when (e) {
                is Event.Toast -> Toast.makeText(activity, e.text, Toast.LENGTH_LONG).show()
                is Event.File -> activity.openFile(e)
                is Event.InviteLink -> activity.shareInvite(e)
                // Just signed in with a password: the time to offer the lock, once.
                is Event.SignedIn ->
                    if (!Prefs.lockOffered.value && !Prefs.lock.value && AppLock.available(activity)) offerLock = true
                else -> {}
            }
        }
    }
    // Nothing is behind the lock with no server signed in to; until the engine says, it holds.
    val known = state.version.isNotEmpty()
    val anySignedIn = state.saved.any { it.signedIn }
    LaunchedEffect(known, anySignedIn, locked) {
        if (locked && known && !anySignedIn) AppLock.locked.value = false
    }
    val shielded = locked && (!known || anySignedIn)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (shielded) Modifier.clearAndSetSemantics {} else Modifier)) {
            Screens(activity, state)
        }
        if (shielded) LockScreen(activity)
    }
    val leave by activity.confirmLeave.collectAsState()
    leave?.let { go ->
        AlertDialog(
            onDismissRequest = { activity.confirmLeave.value = null },
            title = { Text(tr("Leave the call?", "Sair da chamada?")) },
            text = { Text(tr("Going there ends the voice call you're in.", "Ir para lá encerra a chamada de voz em que você está.")) },
            confirmButton = {
                Button({
                    activity.confirmLeave.value = null
                    go()
                }) { Text(tr("Leave and go", "Sair e ir")) }
            },
            dismissButton = { TextButton({ activity.confirmLeave.value = null }) { Text(tr("Stay", "Ficar")) } },
        )
    }
    if (offerLock && !shielded) {
        val done = {
            offerLock = false
            Prefs.setLockOffered()
        }
        AlertDialog(
            onDismissRequest = done,
            icon = { Icon(Icons.Filled.Fingerprint, null) },
            title = { Text(tr("Lock Ciranda?", "Proteger o Ciranda?")) },
            text = {
                Text(
                    tr(
                        "Ask for your fingerprint, face or the phone's PIN to open Ciranda after a while away. You can change it in Settings.",
                        "Pedir sua digital, rosto ou o PIN do celular para abrir o Ciranda depois de um tempo fora. Dá para mudar em Configurações.",
                    ),
                )
            },
            confirmButton = {
                Button({
                    done()
                    AppLock.enable(activity) { error ->
                        if (error != null) Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                        activity.applyPrivacy()
                    }
                }) { Text(tr("Turn on", "Ativar")) }
            },
            dismissButton = { TextButton(done) { Text(tr("Not now", "Agora não")) } },
        )
    }
}

@Composable
private fun Screens(activity: MainActivity, state: AppState) {
    val session = state.session
    when {
        state.phase == "in" && session != null -> ServerScreen(activity, state, session)
        state.phase == "signedOut" -> SignInScreen(state)
        // Typed in the form: the form stays, its button turning.
        state.phase == "signingIn" && (state.signInViaForm || state.saved.none { it.signedIn }) -> SignInScreen(state)
        else -> Connecting(state)
    }
}

/** A kept sign-in on its way, or the engine not started yet. */
@Composable
private fun Connecting(state: AppState) {
    val p = LocalPalette.current
    // A server that does not answer can be left for another after a moment.
    var slow by remember(state.formServer) { mutableStateOf(false) }
    LaunchedEffect(state.formServer) {
        delay(4000)
        slow = true
    }
    Box(Modifier.fillMaxSize().background(p.bg).systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (state.phase == "signingIn" && state.formServer.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (state.signInRetrying) {
                        tr("Can't reach ${state.formServer}. Trying again…", "Sem conexão com ${state.formServer}. Tentando de novo…")
                    } else {
                        tr("Connecting to ${state.formServer}…", "Conectando a ${state.formServer}…")
                    },
                    color = p.muted, textAlign = TextAlign.Center,
                )
                if (state.signInRetrying) {
                    TextButton({ Engine.send("retryNow") }) { Text(tr("Try now", "Tentar agora")) }
                }
                if (slow || state.signInRetrying) {
                    Spacer(Modifier.height(16.dp))
                    TextButton({ Engine.send("switch") }) { Text(tr("Choose another server", "Escolher outro servidor")) }
                }
            }
        }
    }
}

/** Over everything while locked: the content stays below, as it was, for after. */
@Composable
private fun LockScreen(activity: MainActivity) {
    val p = LocalPalette.current
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    val returns by AppLock.returns.collectAsState()
    val ask = {
        if (!asking) {
            asking = true
            AppLock.unlock(activity) { e ->
                asking = false
                // Let in because the lock turned itself off: said where it will still be seen.
                if (e != null && !AppLock.locked.value) Toast.makeText(activity, e, Toast.LENGTH_LONG).show() else error = e
                activity.applyPrivacy()
            }
        }
    }
    // The prompt shows by itself when the lock comes up and each time the app comes back to it;
    // put away, it waits for the button rather than coming back at once.
    LaunchedEffect(returns) {
        activity.lifecycle.withResumed { }
        ask()
    }
    BackHandler { activity.moveTaskToBack(true) }
    Box(
        Modifier.fillMaxSize().background(p.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text(tr("Ciranda is locked", "O Ciranda está bloqueado"), style = MaterialTheme.typography.titleLarge, color = p.text)
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Unlock with your fingerprint, face or the phone's PIN.", "Desbloqueie com sua digital, rosto ou o PIN do celular."),
                color = p.muted, textAlign = TextAlign.Center,
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = p.danger, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Button(ask, Modifier.fillMaxWidth().height(50.dp), enabled = !asking, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Lock, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Unlock", "Desbloquear"))
            }
        }
    }
}
