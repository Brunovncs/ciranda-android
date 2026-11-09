package app.ciranda.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ciranda.android.Engine
import app.ciranda.android.Pictures
import java.util.Locale

/** English or Portuguese, as the settings say, or the phone's language. */
fun tr(en: String, pt: String): String {
    val chosen = Engine.state.value.settings.language
    val lang = chosen.ifEmpty { Locale.getDefault().language }
    return if (lang.startsWith("pt")) pt else en
}

/** A colour of its own for each name, as the desktop's avatars have. */
fun nameColor(name: String): Color {
    val colors = listOf(0xFF6682FF, 0xFFE05561, 0xFF3FD07F, 0xFFE3A008, 0xFFA78BFA, 0xFF38BDF8, 0xFFFF7A4D, 0xFF5FD3A6)
    return Color(colors[Math.floorMod(name.lowercase().hashCode(), colors.size)])
}

@Composable
fun Avatar(name: String, hash: String?, size: Dp = 40.dp, speaking: Boolean = false, modifier: Modifier = Modifier, kind: String = "picture") {
    val p = LocalPalette.current
    val ring = if (speaking) Modifier.border(2.dp, p.success, CircleShape) else Modifier
    val pic = Pictures.get(hash, kind)
    Box(
        modifier.size(size).then(ring).clip(CircleShape).background(nameColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (pic != null) {
            Image(pic, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}
