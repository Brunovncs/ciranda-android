package app.ciranda.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.ciranda.android.R

/** The desktop's default palettes (`theme.rs`, "Midnight" and "Light"). */
@Immutable
data class Palette(
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val border: Color,
    val strong: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
)

val Dark = Palette(
    bg = Color(0xFF0F1116), surface = Color(0xFF171A21), raised = Color(0xFF1F232C), border = Color(0xFF2A2F3A),
    strong = Color(0xFF3B4252), text = Color(0xFFE6E8EE), muted = Color(0xFF8B93A7), accent = Color(0xFF6682FF),
    danger = Color(0xFFE05561), success = Color(0xFF3FD07F), warning = Color(0xFFE3A008),
)

val Light = Palette(
    bg = Color(0xFFF2F3F5), surface = Color(0xFFFFFFFF), raised = Color(0xFFEBEDEF), border = Color(0xFFD4D7DC),
    strong = Color(0xFFB6BBC4), text = Color(0xFF22262B), muted = Color(0xFF5C6670), accent = Color(0xFF0B2ECB),
    danger = Color(0xFFC4303A), success = Color(0xFF1A9550), warning = Color(0xFF9A6A00),
)

val LocalPalette = staticCompositionLocalOf { Dark }

val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.f() = copy(fontFamily = Figtree)
    return Typography(
        displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
        titleLarge = base.titleLarge.f().copy(fontWeight = FontWeight.SemiBold), titleMedium = base.titleMedium.f().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.f(), bodyLarge = base.bodyLarge.f().copy(fontSize = 16.sp), bodyMedium = base.bodyMedium.f(),
        bodySmall = base.bodySmall.f(), labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
    )
}

@Composable
fun CirandaTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (p == Dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.text, surface = p.surface,
            onSurface = p.text, surfaceVariant = p.raised, onSurfaceVariant = p.muted, outline = p.border, error = p.danger,
            surfaceContainer = p.surface, surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised,
            surfaceContainerLow = p.surface, secondaryContainer = p.raised, onSecondaryContainer = p.text,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = Color.White, background = p.bg, onBackground = p.text, surface = p.surface,
            onSurface = p.text, surfaceVariant = p.raised, onSurfaceVariant = p.muted, outline = p.border, error = p.danger,
            surfaceContainer = p.surface, surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised,
            surfaceContainerLow = p.surface, secondaryContainer = p.raised, onSecondaryContainer = p.text,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
    }
}
