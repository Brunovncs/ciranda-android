package app.ciranda.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
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
import app.ciranda.android.AppState
import app.ciranda.android.Engine
import app.ciranda.android.MainActivity
import app.ciranda.android.Updater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(activity: MainActivity, state: AppState, onClose: () -> Unit) {
    val p = LocalPalette.current
    val st = state.settings
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().background(p.surface).height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Voltar"), tint = p.text) }
                Text(tr("Settings", "Configurações"), style = MaterialTheme.typography.titleMedium, color = p.text)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
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
                Row {
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
                }
                Action(Icons.Filled.SwapHoriz, tr("Switch server", "Trocar de servidor")) {
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
private fun Toggle(title: String, detail: String?, on: Boolean, set: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text)
            detail?.let { Text(it, color = p.muted, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, set)
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
