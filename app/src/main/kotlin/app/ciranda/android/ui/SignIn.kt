package app.ciranda.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ciranda.android.AppState
import app.ciranda.android.Engine
import app.ciranda.android.R

@Composable
fun SignInScreen(state: AppState) {
    val p = LocalPalette.current
    // Started from what the engine keeps of the form, so a sign-in that failed while this screen
    // was away (a kept one, from the list) comes back with everything but the account's password.
    var server by rememberSaveable { mutableStateOf(state.formServer) }
    var serverPassword by rememberSaveable { mutableStateOf(state.formServerPassword) }
    var nickname by rememberSaveable { mutableStateOf(state.formNickname) }
    var password by rememberSaveable { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(state.formRegister) }
    var showServerPassword by rememberSaveable { mutableStateOf(state.formServerPassword.isNotEmpty() || state.needsServerPassword) }
    var forgot by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    // The engine fills the form from a saved server; it takes over until the user types.
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(state.formServer, state.formNickname) {
        if (!seeded || server.isEmpty()) {
            if (state.formServer.isNotEmpty()) server = state.formServer
            if (state.formNickname.isNotEmpty()) nickname = state.formNickname
            seeded = true
        }
    }
    LaunchedEffect(state.needsServerPassword) { if (state.needsServerPassword) showServerPassword = true }
    // A new invite link fills everything in but the account; one already in the fields is not
    // filled in again over what was typed since.
    var seenSeq by rememberSaveable { mutableStateOf(state.formSeq) }
    LaunchedEffect(state.formSeq) {
        if (state.formSeq != seenSeq) {
            seenSeq = state.formSeq
            server = state.formServer
            serverPassword = state.formServerPassword
            showServerPassword = state.formServerPassword.isNotEmpty()
            nickname = state.formNickname
            password = ""
            register = state.formRegister
            seeded = true
        }
    }
    // A wrong password empties that field alone, ready for another try.
    LaunchedEffect(state.signInFailures) {
        if (state.signInFailures > 0 && state.wrongPassword) {
            password = ""
            try {
                passwordFocus.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }
    val busy = state.phase == "signingIn"
    val submit = {
        Engine.send(
            "signIn", "server" to server, "serverPassword" to serverPassword, "nickname" to nickname,
            "password" to password, "register" to register,
        )
    }

    Box(Modifier.fillMaxSize().background(p.bg).systemBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(84.dp))
            Spacer(Modifier.height(12.dp))
            Text("Ciranda", style = MaterialTheme.typography.headlineMedium, color = p.text)
            Text(
                tr("Voice and chat for your group of friends.", "Voz e chat para o seu grupo de amigos."),
                style = MaterialTheme.typography.bodyMedium, color = p.muted,
            )
            Spacer(Modifier.height(24.dp))

            state.formInvite?.let { place ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accent.copy(alpha = 0.16f)).padding(14.dp),
                ) {
                    Text(tr("You were invited to", "Você foi convidado para"), color = p.muted, style = MaterialTheme.typography.bodySmall)
                    Text(place, color = p.text, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (register) {
                            tr("Choose a name and a password to make your account.", "Escolha um nome e uma senha para criar sua conta.")
                        } else {
                            tr("Sign in with your account there.", "Entre com a sua conta de lá.")
                        },
                        color = p.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            val saved = state.saved.filter { it.signedIn }
            if (saved.isNotEmpty()) {
                Text(
                    tr("Your servers", "Seus servidores"), style = MaterialTheme.typography.labelLarge, color = p.muted,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                for (s in saved) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(p.surface)
                            .clickable(enabled = !busy) { Engine.send("resume", "url" to s.url, "username" to s.username) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Dns, null, tint = p.accent)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifEmpty { s.host }, color = p.text, style = MaterialTheme.typography.titleSmall)
                            Text("${s.username} · ${s.host}", color = p.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = p.muted)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    tr("Or sign in to another", "Ou entre em outro"), style = MaterialTheme.typography.labelLarge, color = p.muted,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }

            OutlinedTextField(
                server, { server = it }, Modifier.fillMaxWidth(),
                label = { Text(tr("Server address", "Endereço do servidor")) },
                placeholder = { Text("bloquinho.duckdns.org:8444") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            if (showServerPassword) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    serverPassword, { serverPassword = it }, Modifier.fillMaxWidth(),
                    label = { Text(tr("Server password", "Senha do servidor")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
            } else {
                TextButton({ showServerPassword = true }, Modifier.fillMaxWidth()) {
                    Text(tr("This server has a password", "Este servidor tem senha"))
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                nickname, { nickname = it }, Modifier.fillMaxWidth(),
                label = { Text(tr("Your name", "Seu nome")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth().focusRequester(passwordFocus),
                label = { Text(tr("Password", "Senha")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { if (!busy) submit() }),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Create an account", "Criar uma conta"), color = p.text)
                    Text(
                        tr("First time on this server", "Primeira vez neste servidor"),
                        color = p.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(register, { register = it })
            }
            if (!register) {
                TextButton({ forgot = true }, Modifier.fillMaxWidth()) {
                    Text(tr("Forgot your password?", "Esqueceu a senha?"))
                }
            }
            state.signInError?.let {
                Text(it, color = p.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Button(submit, Modifier.fillMaxWidth().height(50.dp), enabled = !busy, shape = RoundedCornerShape(12.dp)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = p.text)
                } else {
                    Text(if (register) tr("Create account", "Criar conta") else tr("Sign in", "Entrar"))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("v${state.version}", color = p.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (forgot) {
        val place = state.formInvite ?: server.ifBlank { tr("the server", "o servidor") }
        AlertDialog(
            onDismissRequest = { forgot = false },
            confirmButton = { TextButton({ forgot = false }) { Text("OK") } },
            title = { Text(tr("Forgot your password?", "Esqueceu a senha?")) },
            text = {
                Text(
                    tr(
                        "A server has no e-mail to send a new one to: whoever runs $place sets a new password for your account in its panel. Ask them, then sign in with it. Still signed in on another device? Change it there, in Settings.",
                        "O servidor não tem e-mail para mandar uma nova: quem cuida de $place define uma senha nova para a sua conta no painel. Peça para essa pessoa e entre com ela. Ainda está conectado em outro aparelho? Dá para trocar a senha lá, em Configurações.",
                    ),
                )
            },
        )
    }
}
