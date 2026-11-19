package app.ciranda.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ciranda.android.News
import app.ciranda.android.R
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "What's new": what changed since [since], the version last shown, every skipped version
 * included, newest first; or, with no [since], the whole history from Settings.
 */
@Composable
fun NewsDialog(entries: List<News.Entry>, since: String?, onClose: () -> Unit) {
    val p = LocalPalette.current
    val greeting = since != null
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight)
                .clip(RoundedCornerShape(20.dp)).background(p.surface).border(1.dp, p.border, RoundedCornerShape(20.dp)),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    Text(
                        if (greeting) tr("What's new", "Novidades") else tr("Version history", "Histórico de versões"),
                        style = MaterialTheme.typography.titleLarge, color = p.text,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(subtitle(entries, since), style = MaterialTheme.typography.bodySmall, color = p.muted)
                }
                IconButton(onClose) { Icon(Icons.Filled.Close, tr("Close", "Fechar"), tint = p.muted) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
                entries.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 18.dp), color = p.border)
                    Release(e)
                }
            }
            Row(
                Modifier.fillMaxWidth().background(p.raised).topBorder(p.border).padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClose, shape = RoundedCornerShape(12.dp)) { Text(if (greeting) tr("Got it", "Entendi") else tr("Close", "Fechar")) }
            }
        }
    }
}

private fun subtitle(entries: List<News.Entry>, since: String?): String = when {
    since == null -> tr("What changed in each version, the newest first.", "O que mudou em cada versão, da mais nova para a mais antiga.")
    entries.size == 1 -> tr("Ciranda is now on version ${News.current}.", "O Ciranda agora está na versão ${News.current}.")
    else -> tr(
        "Ciranda is now on version ${News.current}. Here is what changed since $since, the newest first.",
        "O Ciranda agora está na versão ${News.current}. Veja o que mudou desde a $since, da mais nova para a mais antiga.",
    )
}

@Composable
private fun Release(e: News.Entry) {
    val p = LocalPalette.current
    val pt = tr("en", "pt") == "pt"
    val date = e.date.format(
        if (pt) DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR")) else DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.version, style = MaterialTheme.typography.titleMedium, color = p.text)
            if (e.version == News.current) {
                Spacer(Modifier.width(8.dp))
                Text(
                    tr("This version", "Esta versão"), color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.border(1.dp, p.accent.copy(alpha = 0.55f), CircleShape).padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(date, style = MaterialTheme.typography.labelSmall, color = p.muted)
        }
        for (kind in News.Kind.entries) {
            val notes = e.notes.filter { it.kind == kind }
            if (notes.isEmpty()) continue
            val (icon, color) = look(kind)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(15.dp), tint = color)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label(kind).uppercase(), color = p.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                for (n in notes) {
                    Row {
                        Box(Modifier.padding(top = 9.dp, start = 4.dp).size(4.dp).clip(CircleShape).background(p.muted))
                        Spacer(Modifier.width(10.dp))
                        Text(n.text, color = p.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun label(kind: News.Kind): String = when (kind) {
    News.Kind.New -> tr("New", "Novo")
    News.Kind.Better -> tr("Improved", "Melhorias")
    News.Kind.Fixed -> tr("Fixed", "Correções")
}

@Composable
private fun look(kind: News.Kind): Pair<ImageVector, Color> {
    val p = LocalPalette.current
    return when (kind) {
        News.Kind.New -> Icons.Filled.AutoAwesome to p.accent
        News.Kind.Better -> Icons.Filled.KeyboardArrowUp to p.muted
        News.Kind.Fixed -> Icons.Filled.Check to p.success
    }
}
