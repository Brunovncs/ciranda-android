package app.ciranda.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ciranda.android.AppLock
import app.ciranda.android.AppState
import app.ciranda.android.Engine
import app.ciranda.android.MainActivity
import app.ciranda.android.Prefs
import app.ciranda.android.Updater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(activity: MainActivity, state: AppState, onClose: () -> Unit) {
    val p = LocalPalette.current
    val st = state.settings
    var changingPassword by remember { mutableStateOf(false) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().background(p.surface).height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Voltar"), tint = p.text) }
                Text(tr("Settings", "Configurações"), style = MaterialTheme.typography.titleMedium, color = p.text)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Notifications(activity)
                Security(activity)

                Section(tr("Updates", "Atualizações"))
                UpdateCard(activity, state)
                Toggle(
                    tr("Look for updates", "Procurar atualizações"),
                    tr("At start and every few hours", "Ao abrir e a cada algumas horas"),
                    st.checkUpdates,
                ) { Engine.send("settings", "checkUpdates" to it) }

                Section(tr("Voice", "Voz"))
                Toggle(
                    tr("Noise suppression", "Supressão de ruído"),
                    tr("Keeps what isn't your voice out", "Tira o que não é a sua voz"),
                    st.noiseSuppression,
                ) { Engine.send("settings", "noiseSuppression" to it) }
                if (st.noiseSuppression) {
                    Toggle(
                        tr("Neural suppressor (DeepFilterNet)", "Supressor neural (DeepFilterNet)"),
                        tr("Better, and uses more battery", "Melhor, e gasta mais bateria"),
                        st.neural,
                    ) { Engine.send("settings", "neural" to it) }
                }
                Toggle(
                    tr("Echo cancellation", "Cancelamento de eco"),
                    tr("For the loudspeaker; not needed with headphones", "Para o alto-falante; dispensável com fone"),
                    st.echoCancellation,
                ) { Engine.send("settings", "echoCancellation" to it) }
                Toggle(
                    tr("Automatic gain", "Ganho automático"),
                    tr("Brings your voice to one level", "Deixa sua voz num volume constante"),
                    st.autoGain,
                ) { Engine.send("settings", "autoGain" to it) }
                Level(tr("Microphone volume", "Volume do microfone"), st.micGain, 0..200) { Engine.send("settings", "micGain" to it) }
                Level(
                    tr("Noise gate", "Portão de ruído"), st.micSensitivity, 0..100,
                    label = { if (it == 0) tr("off", "desligado") else "$it" },
                ) { Engine.send("settings", "micSensitivity" to it) }
                Toggle(
                    tr("Voice sounds", "Sons da voz"),
                    tr("Joining, leaving, muting", "Entrar, sair, mutar"),
                    st.voiceSounds,
                ) { Engine.send("settings", "voiceSounds" to it) }
                Level(tr("Sounds volume", "Volume dos sons"), st.soundVolume, 0..200) { Engine.send("settings", "soundVolume" to it) }

                Section(tr("Language", "Idioma"))
                FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((code, name) in listOf("" to tr("System", "Sistema"), "pt" to "Português", "en" to "English")) {
                        val on = st.language == code
                        val shape = RoundedCornerShape(10.dp)
                        if (on) {
                            Button({}, shape = shape, modifier = Modifier.padding(end = 8.dp)) { Text(name) }
                        } else {
                            OutlinedButton({ Engine.send("settings", "language" to code) }, shape = shape, modifier = Modifier.padding(end = 8.dp)) { Text(name) }
                        }
                    }
                }

                Section(tr("Account", "Conta"))
                state.session?.let { s ->
                    Text("${s.me.name} · @${s.me.nickname}", color = p.text)
                    Text(s.url, color = p.muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Action(Icons.Filled.Key, tr("Change password", "Trocar senha")) { changingPassword = true }
                }
                Action(Icons.Filled.SwapHoriz, tr("Switch server", "Trocar de servidor")) {
                    activity.leavingCall {
                        onClose()
                        Engine.send("switch")
                    }
                }
                Action(Icons.AutoMirrored.Filled.Logout, tr("Sign out of this server", "Sair deste servidor")) {
                    onClose()
                    Engine.send("signOut", "forget" to false)
                }
                Action(Icons.AutoMirrored.Filled.Logout, tr("Sign out and forget this server", "Sair e esquecer este servidor"), danger = true) {
                    onClose()
                    Engine.send("signOut", "forget" to true)
                }
                Spacer(Modifier.height(24.dp))
                Text("Ciranda ${state.version} · Android", color = p.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (changingPassword) ChangePassword { changingPassword = false }
    }
}

/** The app lock (`AppLock`): on and off, and how long away before it asks again. */
@Composable
private fun Security(activity: MainActivity) {
    val lock by Prefs.lock.collectAsState()
    val after by Prefs.lockAfterSec.collectAsState()
    val resumed by activity.resumes.collectAsState()
    val can = remember(resumed) { AppLock.available(activity) }
    var busy by remember { mutableStateOf(false) }
    Section(tr("Security", "Segurança"))
    Toggle(
        tr("Lock with fingerprint or PIN", "Bloquear com digital ou PIN"),
        when {
            !can && !lock -> tr(
                "Set up a fingerprint, face or screen lock on the phone to use it.",
                "Configure uma digital, rosto ou bloqueio de tela no celular para usar.",
            )
            else -> tr("Asks for it to open Ciranda after a while away.", "Pede para abrir o Ciranda depois de um tempo fora.")
        },
        lock,
        enabled = (can || lock) && !busy,
    ) { on ->
        if (on) {
            busy = true
            AppLock.enable(activity) { error ->
                busy = false
                if (error != null) android.widget.Toast.makeText(activity, error, android.widget.Toast.LENGTH_LONG).show()
                activity.applyPrivacy()
            }
        } else {
            AppLock.disable()
            activity.applyPrivacy()
        }
    }
    if (lock) {
        Text(tr("Ask again after", "Pedir de novo depois de"), color = LocalPalette.current.muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((seconds, name) in listOf(
                0 to tr("Right away", "Na hora"), 60 to "1 min", 300 to "5 min", 1800 to "30 min",
            )) {
                val shape = RoundedCornerShape(10.dp)
                if (after == seconds) {
                    Button({}, shape = shape) { Text(name) }
                } else {
                    OutlinedButton({ Prefs.setLockAfter(seconds) }, shape = shape) { Text(name) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The account's password, changed: the current one, and the new one twice. */
@Composable
private fun ChangePassword(onClose: () -> Unit) {
    val p = LocalPalette.current
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        Engine.events.collect { e ->
            if (e is app.ciranda.android.Event.PasswordChanged) {
                busy = false
                if (e.ok) {
                    android.widget.Toast.makeText(ctx, tr("Password changed. Other devices were signed out.", "Senha trocada. Os outros aparelhos foram desconectados."), android.widget.Toast.LENGTH_LONG).show()
                    onClose()
                } else {
                    error = e.error
                }
            }
        }
    }
    val problem = when {
        next.isNotEmpty() && next.length < 6 -> tr("Use at least 6 characters.", "Use pelo menos 6 caracteres.")
        again.isNotEmpty() && again != next -> tr("The new passwords don't match.", "As senhas novas não batem.")
        else -> null
    }
    val ready = current.isNotEmpty() && next.length >= 6 && again == next && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(tr("Change password", "Trocar senha")) },
        text = {
            Column {
                for ((value, set, label) in listOf(
                    Triple(current, { v: String -> current = v }, tr("Current password", "Senha atual")),
                    Triple(next, { v: String -> next = v }, tr("New password", "Senha nova")),
                    Triple(again, { v: String -> again = v }, tr("New password again", "Senha nova de novo")),
                )) {
                    androidx.compose.material3.OutlinedTextField(
                        value, set, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        label = { Text(label) }, singleLine = true, enabled = !busy,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                    )
                }
                (problem ?: error)?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button({
                busy = true
                error = null
                Engine.send("changePassword", "current" to current, "password" to next)
            }, enabled = ready) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(tr("Change", "Trocar"))
            }
        },
        dismissButton = { TextButton({ onClose() }, enabled = !busy) { Text(tr("Cancel", "Cancelar")) } },
    )
}

@Composable
private fun Notifications(activity: MainActivity) {
    val notify by Prefs.notify.collectAsState()
    // Looked at again on coming back from the system's settings.
    val resumed by activity.resumes.collectAsState()
    Section(tr("Notifications", "Notificações"))
    Toggle(
        tr("Mentions with the app closed", "Menções com o app fechado"),
        tr(
            "When someone writes @you, @everyone or @all, on any of your servers. Keeps a light connection, with a silent notice of its own.",
            "Quando alguém escrever @você, @everyone ou @todos, em qualquer servidor seu. Mantém uma conexão leve, com um aviso silencioso fixo.",
        ),
        notify,
    ) { Prefs.setNotify(it) }
    if (!notify) return
    val allowed = remember(resumed) { androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled() }
    if (!allowed) {
        Action(Icons.Filled.NotificationsOff, tr("Allow notifications", "Permitir notificações"), danger = true) {
            activity.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName),
            )
        }
    }
    val free = remember(resumed) {
        activity.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
    }
    if (!free) {
        Action(Icons.Filled.BatteryAlert, tr("Let it run in the background (some phones cut it)", "Liberar em segundo plano (alguns celulares cortam)")) {
            activity.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${activity.packageName}"),
                ),
            )
        }
    }
}

@Composable
private fun UpdateCard(activity: MainActivity, state: AppState) {
    val p = LocalPalette.current
    val u by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(16.dp)) {
        Text(tr("This is version ${state.version}.", "Esta é a versão ${state.version}."), color = p.text)
        val line = when (val st = u) {
            Updater.State.Idle -> null
            Updater.State.Checking -> tr("Looking…", "Procurando…")
            Updater.State.UpToDate -> tr("It's the latest.", "É a mais recente.")
            is Updater.State.Available -> tr("Version ${st.release.version} is out.", "A versão ${st.release.version} está disponível.")
            is Updater.State.Downloading -> tr("Downloading…", "Baixando…")
            is Updater.State.Ready -> tr("Version ${st.release.version} is downloaded.", "A versão ${st.release.version} foi baixada.")
            is Updater.State.Failed -> tr("Could not update: ${st.message}", "Não foi possível atualizar: ${st.message}")
        }
        line?.let { Text(it, color = if (u is Updater.State.Failed) p.danger else p.muted, style = MaterialTheme.typography.bodySmall) }
        (u as? Updater.State.Available)?.release?.notes?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 8)
        }
        (u as? Updater.State.Downloading)?.let {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator({ it.fraction }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
        when (val st = u) {
            is Updater.State.Available -> Button({ Updater.download(activity, st.release) }) { Text(tr("Update", "Atualizar")) }
            is Updater.State.Ready -> Button({ Updater.install(activity, st.file) }) { Text(tr("Install", "Instalar")) }
            is Updater.State.Failed -> st.release?.let { r ->
                Button({ Updater.download(activity, r) }) { Text(tr("Try again", "Tentar de novo")) }
            } ?: OutlinedButton({ scope.launch { Updater.check(activity) } }) { Text(tr("Look for updates", "Procurar atualizações")) }
            Updater.State.Checking, is Updater.State.Downloading -> {}
            else -> OutlinedButton({ scope.launch { Updater.check(activity) } }) { Text(tr("Look for updates", "Procurar atualizações")) }
        }
    }
}

@Composable
private fun Section(title: String) {
    val p = LocalPalette.current
    Text(title, style = MaterialTheme.typography.titleSmall, color = p.accent, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
private fun Toggle(title: String, detail: String?, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { set(!on) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text)
            detail?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, set, enabled = enabled)
    }
}

@Composable
private fun Level(title: String, value: Int, range: IntRange, label: (Int) -> String = { "$it%" }, set: (Int) -> Unit) {
    val p = LocalPalette.current
    var drag by remember(value) { mutableStateOf<Float?>(null) }
    val shown = (drag ?: value.toFloat()).toInt()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(title, color = p.text, modifier = Modifier.weight(1f))
            Text(label(shown), color = p.muted)
        }
        Slider(
            drag ?: value.toFloat(), { drag = it },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { drag?.let { set(it.toInt()) }; drag = null },
        )
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) p.danger else p.muted)
        Spacer(Modifier.width(14.dp))
        Text(text, color = if (danger) p.danger else p.text)
    }
    HorizontalDivider(color = p.border)
}
