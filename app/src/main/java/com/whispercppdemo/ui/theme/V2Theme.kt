@file:OptIn(ExperimentalTextApi::class)

package com.whispercppdemo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.whispercppdemo.R

/** Colour tokens — the same three themes as the desktop app (Neon, Umbrella, Umbrella Dark). */
@Immutable
data class V2Colors(
    val id: String,
    val title: String,
    val subtitle: String,
    val corp: Boolean,          // Umbrella styling: emblem logo, spaced caps, scanlines, hazard stripe
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val headerLine: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    val label: Color,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val rec: Color,
    val ok: Color,
    val busy: Color,
    val glow: Color,
    val stripe: Color,
    val waves: List<Color>,
)

val Neon = V2Colors(
    id = "neon", title = "ALISA CORPORATION", subtitle = "NEON · 2.1.0", corp = false,
    bg = Color(0xFF0B0E13), panel = Color(0xFF11151C), panel2 = Color(0xFF161B24), line = Color(0xFF1F2632),
    headerLine = Color(0xFF1F2632), text = Color(0xFFE6EBF2), muted = Color(0xFF7D8899), dim = Color(0xFF4C5666),
    label = Color(0xFF5B6678), accent = Color(0xFF3EE6FF), accent2 = Color(0xFF9B6BFF), onAccent = Color(0xFF071017),
    rec = Color(0xFFFF4D6A), ok = Color(0xFF3DDC97), busy = Color(0xFFFFB547), glow = Color(0x1A3EE6FF),
    stripe = Color.Transparent,
    waves = listOf(Color(0xFF3EE6FF), Color(0xFF9B6BFF), Color(0xFFFF5FA8), Color(0xFF5B8CFF)),
)

val Umbrella = V2Colors(
    id = "umbrella", title = "ALISA CORPORATION", subtitle = "BIOACOUSTIC UNIT · 2.1.0", corp = true,
    bg = Color(0xFF070708), panel = Color(0xFF0F0F11), panel2 = Color(0xFF151517), line = Color(0xFF26262A),
    headerLine = Color(0xFF3A0A12), text = Color(0xFFF2F2F2), muted = Color(0xFF8E8E93), dim = Color(0xFF5A5A60),
    label = Color(0xFFB3101F), accent = Color(0xFFFF1F3D), accent2 = Color(0xFFA00018), onAccent = Color.White,
    rec = Color(0xFFE4002B), ok = Color(0xFFF2F2F2), busy = Color(0xFFFF7A1A), glow = Color(0x24E4002B),
    stripe = Color(0xFF1A0508),
    waves = listOf(Color(0xFFFF1F3D), Color(0xFFB0001E), Color(0xFFF2F2F2), Color(0xFFFF6B4A)),
)

val UmbrellaDark = V2Colors(
    id = "hive", title = "ALISA CORPORATION", subtitle = "UMBRELLA DARK · 2.1.0", corp = true,
    bg = Color(0xFF030303), panel = Color(0xFF09090A), panel2 = Color(0xFF0E0E0F), line = Color(0xFF1C1C1F),
    headerLine = Color(0xFF2A0509), text = Color(0xFFE8E6E6), muted = Color(0xFF807C7D), dim = Color(0xFF4D494A),
    label = Color(0xFF7A0A16), accent = Color(0xFFB3001B), accent2 = Color(0xFF4A0008), onAccent = Color.White,
    rec = Color(0xFF9E0018), ok = Color(0xFFE8E6E6), busy = Color(0xFFC2410C), glow = Color(0x298C0014),
    stripe = Color(0xFF120204),
    waves = listOf(Color(0xFFB3001B), Color(0xFF6E000F), Color(0xFFD8D4D4), Color(0xFF8C1C10)),
)

val Themes = listOf(Neon, Umbrella, UmbrellaDark)

private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Bundled fonts (OFL): Unbounded for brand/timer, Golos Text for text, JetBrains Mono for readouts. */
object V2Fonts {
    val display = FontFamily(variable(R.font.unbounded, 600), variable(R.font.unbounded, 800))
    val body = FontFamily(variable(R.font.golos_text, 400), variable(R.font.golos_text, 500), variable(R.font.golos_text, 600))
    val mono = FontFamily(variable(R.font.jetbrains_mono, 400), variable(R.font.jetbrains_mono, 500), variable(R.font.jetbrains_mono, 600))
}

val LocalV2 = staticCompositionLocalOf { Umbrella }

@Composable
fun V2Theme(colors: V2Colors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalV2 provides colors, content = content)
}
