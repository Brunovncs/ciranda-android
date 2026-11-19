package app.ciranda.android.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ciranda.android.CallActivity
import app.ciranda.android.Engine
import app.ciranda.android.Ring
import app.ciranda.android.VoiceLink
import app.ciranda.android.VoiceState
import kotlinx.coroutines.delay

/** The call screen: the ring, the call under way, or a moment saying it is over. */
@Composable
fun CallScreen(activity: CallActivity) {
    val p = LocalPalette.current
    val state by Engine.state.collectAsState()
    val voice by Engine.voice.collectAsState()
    val ring = state.ring
    val call = voice?.takeIf { it.private }
    // What there was, for the moment after it ends.
    val last = remember { arrayOfNulls<String>(1) }
    (call?.peerName ?: ring?.name)?.let { last[0] = it }
    val over = ring == null && call == null
    LaunchedEffect(over) {
        if (over) {
            delay(1500)
            activity.finish()
        }
    }
    Box(Modifier.fillMaxSize().background(p.bg).systemBarsPadding(), contentAlignment = Alignment.Center) {
        when {
            call != null -> InCall(activity, call)
            ring != null -> Incoming(activity, ring)
            else -> Ended(last[0])
        }
    }
}

@Composable
private fun Incoming(activity: CallActivity, ring: Ring) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    if (!ring.answering) Ringing()
    // Answered or declined, never only put away: Home leaves it ringing in the notification.
    BackHandler {}
    Column(
        Modifier.widthIn(max = 420.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sealed()
        Spacer(Modifier.weight(1f))
        Avatar(ring.name, ring.avatar, size = 128.dp, speaking = !ring.answering)
        Spacer(Modifier.height(20.dp))
        Text(
            ring.name, style = MaterialTheme.typography.headlineMedium, color = p.text, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (ring.answering) tr("Connecting…", "Conectando…") else tr("is calling you", "está te ligando"),
            color = p.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (ring.serverName.isNotBlank()) {
            Text(ring.serverName, color = p.muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.weight(1.4f))
        if (ring.answering) {
            CircularProgressIndicator()
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallButton(Icons.Filled.CallEnd, tr("Decline", "Recusar"), p.danger) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    activity.decline()
                }
                CallButton(Icons.Filled.Call, tr("Answer", "Atender"), p.success) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    activity.answer()
                }
            }
        }
    }
}

@Composable
private fun InCall(activity: CallActivity, v: VoiceState) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    val name = v.peerName ?: tr("Someone", "Alguém")
    val them = v.roster.firstOrNull { it.userId == v.peer }
    val speaking = v.peer != null && v.peer in v.speaking
    BackHandler { activity.moveTaskToBack(true) }
    Column(
        Modifier.widthIn(max = 420.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Sealed()
        Spacer(Modifier.weight(1f))
        Box {
            Avatar(name, v.peerAvatar, size = 128.dp, speaking = speaking)
            if (them != null && (them.muted || them.deafened || them.forceMuted)) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(36.dp).clip(CircleShape).background(p.surface),
                    contentAlignment = Alignment.Center,
                ) { MemberMarks(them) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            name, style = MaterialTheme.typography.headlineMedium, color = p.text, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(6.dp))
        val status = when {
            v.ringing -> tr("Calling…", "Chamando…")
            v.state == VoiceLink.Reconnecting -> tr("Reconnecting…", "Reconectando…")
            v.state == VoiceLink.Joining || v.since == null -> tr("Connecting…", "Conectando…")
            else -> null
        }
        if (status != null) {
            Text(
                status, color = if (v.state == VoiceLink.Reconnecting) p.warning else p.muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            Timer(v.since ?: 0)
        }
        if (v.micFailed) {
            Text(tr("Microphone not connected", "Microfone sem conexão"), color = p.warning, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.weight(1.4f))
        v.conversationId?.let { id ->
            TextButton({ activity.openConversation(id) }) {
                Icon(Icons.AutoMirrored.Filled.Chat, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Open the conversation", "Abrir a conversa"))
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CallButton(
                if (v.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                if (v.muted) tr("Unmute", "Ativar microfone") else tr("Mute", "Mutar"),
                if (v.muted) p.danger else p.raised,
            ) {
                haptic.performHapticFeedback(if (v.muted) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                Engine.send("mute", "on" to !v.muted)
            }
            CallButton(
                if (v.deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
                if (v.deafened) tr("Undeafen", "Voltar a ouvir") else tr("Deafen", "Ensurdecer"),
                if (v.deafened) p.danger else p.raised,
            ) {
                haptic.performHapticFeedback(if (v.deafened) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                Engine.send("deafen", "on" to !v.deafened)
            }
            CallButton(Icons.Filled.CallEnd, tr("Hang up", "Desligar"), p.danger) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                Engine.send("leave")
            }
        }
    }
}

@Composable
private fun Ended(name: String?) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        name?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = p.text) }
        Text(tr("Call ended", "Chamada encerrada"), color = p.muted)
    }
}

/** "End-to-end encrypted", at the top of both screens. */
@Composable
private fun Sealed() {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, null, tint = p.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr("End-to-end encrypted", "Criptografia de ponta a ponta"), color = p.muted, style = MaterialTheme.typography.labelMedium)
    }
}

/** How long the call has lasted, a second at a time. */
@Composable
private fun Timer(since: Long) {
    val p = LocalPalette.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val secs = ((now - since) / 1000).coerceAtLeast(0)
    val text = if (secs >= 3600) {
        "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
    } else {
        "%d:%02d".format(secs / 60, secs % 60)
    }
    Text(text, color = p.muted, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun CallButton(icon: ImageVector, label: String, bg: Color, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (bg == p.raised) p.text else Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = p.muted, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * The phone's ringtone and vibration while the ring is on screen, as the phone's ringer mode has
 * them: silent rings nothing, vibrate only vibrates.
 */
@Composable
private fun Ringing() {
    val ctx = LocalContext.current
    val mode = remember { ctx.getSystemService(AudioManager::class.java).ringerMode }
    val tone = remember {
        if (mode != AudioManager.RINGER_MODE_NORMAL) return@remember null
        RingtoneManager.getRingtone(ctx, RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE))?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
        }
    }
    // Before Android 9 a ringtone plays once: it is started again as it ends.
    if (Build.VERSION.SDK_INT < 28 && tone != null) {
        LaunchedEffect(tone) {
            while (true) {
                delay(1000)
                if (!tone.isPlaying) tone.play()
            }
        }
    }
    DisposableEffect(tone) {
        tone?.play()
        val buzz = if (mode != AudioManager.RINGER_MODE_SILENT) vibrator(ctx) else null
        buzz?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 1200), 0))
        onDispose {
            tone?.stop()
            buzz?.cancel()
        }
    }
}

private fun vibrator(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
    ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
} else {
    @Suppress("DEPRECATION")
    ctx.getSystemService(Vibrator::class.java)
}
