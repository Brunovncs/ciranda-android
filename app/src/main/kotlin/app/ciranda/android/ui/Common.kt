package app.ciranda.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ciranda.android.Engine
import app.ciranda.android.Pictures
import kotlinx.coroutines.delay
import java.util.Locale

/** English or Portuguese, as the settings say, or the phone's language. */
fun tr(en: String, pt: String): String {
    // `Engine.language` is Compose state: whatever shows text through here is drawn again when it changes.
    val lang = Engine.language.ifEmpty { Locale.getDefault().language }
    return if (lang.startsWith("pt")) pt else en
}

private val NAME_COLORS = listOf(0xFF6682FF, 0xFFE05561, 0xFF3FD07F, 0xFFE3A008, 0xFFA78BFA, 0xFF38BDF8, 0xFFFF7A4D, 0xFF5FD3A6).map { Color(it) }

/** A colour of its own for each name, as the desktop's avatars have. */
fun nameColor(name: String): Color = NAME_COLORS[Math.floorMod(name.lowercase().hashCode(), NAME_COLORS.size)]

private fun contrast(a: Color, b: Color): Float {
    val x = a.luminance() + 0.05f
    val y = b.luminance() + 0.05f
    return maxOf(x, y) / minOf(x, y)
}

/** What reads on top of a colour: white on the dark ones, near-black on the light ones. */
fun onColor(c: Color): Color = if (contrast(c, Color.White) >= 3f) Color.White else Color(0xFF10131A)

/**
 * The name's colour as text on the page. The avatar colours are bright, which suits the dark
 * theme as it is; on the light one the same colours are darkened until they can be read.
 */
@Composable
fun nameTextColor(name: String): Color {
    val p = LocalPalette.current
    return remember(name, p) {
        var c = nameColor(name)
        var steps = 0
        while (contrast(c, p.bg) < 4.5f && steps++ < 12) c = lerp(c, Color.Black, 0.12f)
        c
    }
}

@Composable
fun Avatar(name: String, hash: String?, size: Dp = 40.dp, speaking: Boolean = false, modifier: Modifier = Modifier, kind: String = "picture") {
    val p = LocalPalette.current
    // The ring comes and goes rather than blinking: someone who pauses between words flickers otherwise.
    val ring by animateColorAsState(if (speaking) p.success else Color.Transparent, tween(140), label = "speaking")
    val pic = Pictures.get(hash, kind)
    val back = nameColor(name)
    Box(
        modifier.size(size).then(if (ring.alpha > 0f) Modifier.border(2.dp, ring, CircleShape) else Modifier).clip(CircleShape).background(back),
        contentAlignment = Alignment.Center,
    ) {
        if (pic != null) {
            Image(pic, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = onColor(back),
                fontWeight = FontWeight.SemiBold,
                // Follows the avatar, not the phone's font size: the letter has only the circle to live in.
                fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() },
            )
        }
    }
}

/**
 * A spinner for a wait that may be nothing: it comes up only if it lasts. One that flashes for
 * a tenth of a second reads as a stutter, not as progress.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier, delayMs: Long = 250) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        show = true
    }
    AnimatedVisibility(show, modifier, enter = fadeIn(tween(160))) { CircularProgressIndicator() }
}

/** Its content coming in over a moment, rather than all at once. */
@Composable
fun FadeIn(content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(140)) }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) { content() }
}

/**
 * A password field with an eye to see what was typed. Hidden again whenever the field is drawn
 * anew (a turned screen, a reopened dialog): a password left showing is not what anyone expects.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onValueChange, modifier,
        label = { Text(label) }, singleLine = true, enabled = enabled, isError = isError,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton({ shown = !shown }, enabled = enabled) {
                Icon(
                    if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    tr(if (shown) "Hide password" else "Show password", if (shown) "Ocultar senha" else "Mostrar senha"),
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            // Password either way: the keyboard neither suggests nor learns from what is typed.
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = keyboardActions,
    )
}

/** A one-pixel line along an edge, drawn rather than laid out: no extra node, no extra height. */
fun Modifier.bottomBorder(color: Color): Modifier = drawBehind {
    val h = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - h / 2), Offset(size.width, size.height - h / 2), h)
}

fun Modifier.topBorder(color: Color): Modifier = drawBehind {
    val h = 1.dp.toPx()
    drawLine(color, Offset(0f, h / 2), Offset(size.width, h / 2), h)
}

fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}
