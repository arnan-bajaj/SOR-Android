package org.systemoverload.team.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

// The look: the board's dark slate with soft colour pools behind frosted cards, like the website and the iPhone app.
// Tints and rings instead of coloured stripes. Nothing animates on its own behind the page.

object Brand {
    val red = Color(0xFFDC2128)
    val blue = Color(0xFF2E8FFF)
    val violet = Color(0xFF8C5CF5)
    val teal = Color(0xFF29C7BF)
    val amber = Color(0xFFFF9E29)
    val green = Color(0xFF34C759)
    val yellow = Color(0xFFFFCC00)
    val cyan = Color(0xFF32ADE6)
    val indigo = Color(0xFF5E5CE6)
    val purple = Color(0xFFAF52DE)
    val orange = Color(0xFFFF9500)
    val pink = Color(0xFFFF2D55)
    val gray = Color(0xFF8E8E93)

    /** A two-stop gradient from one colour, for hero cards and tiles. */
    fun wash(c: Color) = Brush.linearGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0.55f)))
}

data class Palette(
    val dark: Boolean, val page: Color, val card: Color, val sheet: Color, val row: Color, val text: Color, val secondary: Color,
    val tertiary: Color, val hairline: Color, val accent: Color,
)

private val DarkPalette = Palette(
    dark = true, page = Color(0xFF0F1214), card = Color(0xFF1C2125), sheet = Color(0xFF22282C), row = Color(0xB81C2125),
    text = Color(0xFFF2F4F5), secondary = Color(0xFF9AA3AB), tertiary = Color(0xFF5F6870), hairline = Color(0x14FFFFFF), accent = Color(0xFF2F8FFF),
)
private val LightPalette = Palette(
    dark = false, page = Color(0xFFF2F2F7), card = Color(0xFFFFFFFF), sheet = Color(0xFFFFFFFF), row = Color(0xC7FFFFFF),
    text = Color(0xFF111416), secondary = Color(0xFF6B7278), tertiary = Color(0xFFA9AFB4), hairline = Color(0x14000000), accent = Color(0xFF007AFF),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val P: Palette @Composable get() = LocalPalette.current

@Composable
fun SorTheme(appearance: String, content: @Composable () -> Unit) {
    val dark = when (appearance) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.page, surface = p.card, surfaceContainer = p.card,
        surfaceContainerHigh = p.sheet, surfaceContainerHighest = p.sheet, surfaceContainerLow = p.card, surfaceContainerLowest = p.page,
        onBackground = p.text, onSurface = p.text, onSurfaceVariant = p.secondary, outline = p.tertiary, outlineVariant = Color(0x22FFFFFF),
        secondaryContainer = p.accent.copy(alpha = 0.22f), onSecondaryContainer = p.text, error = Brand.red,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White, background = p.page, surface = p.card, surfaceContainer = p.card,
        surfaceContainerHigh = p.sheet, surfaceContainerHighest = Color(0xFFE9E9EE), surfaceContainerLow = p.card, surfaceContainerLowest = p.page,
        onBackground = p.text, onSurface = p.text, onSurfaceVariant = p.secondary, outline = p.tertiary, outlineVariant = Color(0x1F000000),
        secondaryContainer = p.accent.copy(alpha = 0.16f), onSecondaryContainer = p.text, error = Brand.red,
    )
    val base = Typography()
    val type = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type) {
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}

object T {
    val largeTitle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val title3 = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
    val headline = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)
    val bodyMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp)
    val sub = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val subStrong = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp)
    val foot = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val captionStrong = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
    val tiny = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp, letterSpacing = 0.6.sp)
}

/** Soft colour pools behind every page, drawn as plain radial gradients (never animated or blurred). */
@Composable
fun AppBackground(tint: Color = Brand.blue, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = P
    val a = if (p.dark) 0.26f else 0.16f
    Box(
        modifier.fillMaxSize().background(p.page).drawBehind {
            val w = size.width; val h = size.height
            drawRect(Brush.radialGradient(listOf(tint.copy(alpha = a), Color.Transparent), Offset(w * 0.12f, h * 0.06f), w * 0.95f))
            drawRect(Brush.radialGradient(listOf(Brand.red.copy(alpha = a * 0.8f), Color.Transparent), Offset(w, h * 0.38f), w * 0.85f))
            drawRect(Brush.radialGradient(listOf(Brand.violet.copy(alpha = a * 0.9f), Color.Transparent), Offset(w * 0.2f, h * 0.92f), max(w, h) * 0.6f))
        },
        content = content,
    )
}

/** Frosted card with a hairline, tinted when asked. */
@Composable
fun Modifier.glass(radius: Dp = 20.dp, tint: Color? = null): Modifier {
    val p = P
    val shape = RoundedCornerShape(radius)
    return this
        .background(p.row, shape)
        .then(if (tint != null) Modifier.background(tint.copy(alpha = if (p.dark) 0.12f else 0.08f), shape) else Modifier)
        .border(1.dp, if (tint != null) tint.copy(alpha = 0.22f) else p.hairline, shape)
}
