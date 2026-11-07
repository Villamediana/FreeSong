package com.villamediana.sound.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Tokens of one theme. Screens never read this directly: they read [SoundColors]. */
data class SoundPalette(
    val isLight: Boolean,
    val ink: Color,          // app background
    val surface1: Color,     // pills, cards
    val surface2: Color,     // sheets, raised cards
    val surface3: Color,     // inputs, pressed
    val hairline: Color,     // dividers, outlines
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,       // selection, primary actions
    val accentPressed: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val mint: Color,         // what is playing: player controls, equalizer, liked
    val onMint: Color,
    val lyric: Color,        // the line being sung
    val danger: Color,
    val scrim: Color,
    /** Hero cards (smart shuffle) and the equalizer backdrop: a gentle gradient, never a heavy block. */
    val softTop: Color,
    val softBottom: Color,
    /** How strongly the cover color shows on headers and the player (0..1). */
    val washAlpha: Float,
)

object SoundPalettes {
    val Light = SoundPalette(
        isLight = true,
        ink = Color(0xFFF7F8FB),
        surface1 = Color(0xFFEEF0F5),
        surface2 = Color(0xFFFFFFFF),
        surface3 = Color(0xFFE4E7EE),
        hairline = Color(0xFFDDE1E9),
        text = Color(0xFF15161C),
        textMuted = Color(0xFF5D6273),
        textFaint = Color(0xFF9CA1B0),
        accent = Color(0xFF4C6FFF),
        accentPressed = Color(0xFF3B5EEE),
        onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0x1F4C6FFF),
        mint = Color(0xFF0FA383),
        onMint = Color(0xFFFFFFFF),
        lyric = Color(0xFF3355F0),
        danger = Color(0xFFE5484D),
        scrim = Color(0x66000000),
        softTop = Color(0xFFE9EEFF),
        softBottom = Color(0xFFF4F6FD),
        washAlpha = 0.42f,
    )

    /** Dark, but soft: graphite surfaces, no heavy blue blocks. */
    val Dark = SoundPalette(
        isLight = false,
        ink = Color(0xFF0E0F14),
        surface1 = Color(0xFF181A21),
        surface2 = Color(0xFF1F222A),
        surface3 = Color(0xFF292C36),
        hairline = Color(0xFF2C303A),
        text = Color(0xFFF2F3F7),
        textMuted = Color(0xFFA0A5B5),
        textFaint = Color(0xFF686D7E),
        accent = Color(0xFF7C9BFF),
        accentPressed = Color(0xFF6A8AF2),
        onAccent = Color(0xFF0E0F14),
        accentSoft = Color(0x297C9BFF),
        mint = Color(0xFF86E8CE),
        onMint = Color(0xFF0B2A24),
        lyric = Color(0xFFF3DD7C),
        danger = Color(0xFFFF6B72),
        scrim = Color(0xB3000000),
        softTop = Color(0xFF1C2030),
        softBottom = Color(0xFF14161D),
        washAlpha = 0.5f,
    )
}

/** A highlight color the user can pick, tuned for each theme ([play] = what is playing). */
data class AccentDef(val id: String, val light: Color, val dark: Color, val playLight: Color = light, val playDark: Color = dark)

object SoundAccents {
    val all = listOf(
        AccentDef("blue", Color(0xFF4C6FFF), Color(0xFF7C9BFF), playLight = Color(0xFF0FA383), playDark = Color(0xFF86E8CE)),
        AccentDef("green", Color(0xFF149E5C), Color(0xFF5FD69C)),
        AccentDef("pink", Color(0xFFE0457B), Color(0xFFFF7AA8)),
        AccentDef("orange", Color(0xFFEF6A1A), Color(0xFFFF9A5A)),
        AccentDef("purple", Color(0xFF7B4DFF), Color(0xFFA88BFF)),
        AccentDef("red", Color(0xFFE5484D), Color(0xFFFF7B7F)),
        AccentDef("teal", Color(0xFF0E9C9C), Color(0xFF5ED8D8)),
        AccentDef("graphite", Color(0xFF2B2E36), Color(0xFFE6E8EE)),
    )

    fun byId(id: String?): AccentDef = all.firstOrNull { it.id == id } ?: all.first()
}

/** [this] palette with [a] as its highlight color (selection, buttons, player, equalizer). */
fun SoundPalette.withAccent(a: AccentDef): SoundPalette {
    val c = if (isLight) a.light else a.dark
    val play = if (isLight) a.playLight else a.playDark
    val onDark = lerp(c, Color.Black, 0f).luminance() > 0.5f
    return copy(
        accent = c,
        accentPressed = lerp(c, if (isLight) Color.Black else Color.White, 0.12f),
        onAccent = if (onDark) Color(0xFF0E0F14) else Color.White,
        accentSoft = c.copy(alpha = if (isLight) 0.12f else 0.16f),
        mint = play,
        onMint = if (play.luminance() > 0.5f) Color(0xFF0E0F14) else Color.White,
        lyric = if (isLight) c else lyric,
        softTop = if (isLight) lerp(Color.White, c, 0.10f) else lerp(Color(0xFF14161D), c, 0.14f),
        softBottom = if (isLight) lerp(Color(0xFFF7F8FB), c, 0.03f) else Color(0xFF14161D),
    )
}

/**
 * The colors of the current theme. Backed by snapshot state: switching the theme recomposes and
 * redraws everything that read a color (composables and draw lambdas alike).
 */
object SoundColors {
    var palette: SoundPalette by mutableStateOf(SoundPalettes.Light)

    val isLight: Boolean get() = palette.isLight

    val Ink: Color get() = palette.ink
    val Surface1: Color get() = palette.surface1
    val Surface2: Color get() = palette.surface2
    val Surface3: Color get() = palette.surface3
    val Hairline: Color get() = palette.hairline

    val Text: Color get() = palette.text
    val TextMuted: Color get() = palette.textMuted
    val TextFaint: Color get() = palette.textFaint

    val Accent: Color get() = palette.accent
    val AccentPressed: Color get() = palette.accentPressed
    val OnAccent: Color get() = palette.onAccent
    val AccentSoft: Color get() = palette.accentSoft

    val Mint: Color get() = palette.mint
    val OnMint: Color get() = palette.onMint
    val Lyric: Color get() = palette.lyric

    val Liked: Color get() = palette.mint
    val Danger: Color get() = palette.danger
    val Success: Color get() = palette.mint
    val Scrim: Color get() = palette.scrim

    val SoftTop: Color get() = palette.softTop
    val SoftBottom: Color get() = palette.softBottom

    /** Brand colors of the logo — the same in every theme. */
    val BrandRoyal = Color(0xFF2341C9)
    val BrandMint = Color(0xFF8CF5D9)

    /** The mini player card: the theme's raised surface with a hint of the cover. */
    fun miniBackground(cover: Color): Color = lerp(palette.surface2, cover, if (palette.isLight) 0.14f else 0.2f)

    /** The full player / lyrics backdrop: the cover color, light and airy or deep and quiet. */
    fun playerBackground(cover: Color): Color =
        if (palette.isLight) lerp(cover, Color.White, 0.84f) else lerp(cover, palette.ink, 0.66f)

    fun lyricsBackground(cover: Color): Color =
        if (palette.isLight) lerp(cover, Color.White, 0.8f) else lerp(cover, palette.ink, 0.6f)
}
