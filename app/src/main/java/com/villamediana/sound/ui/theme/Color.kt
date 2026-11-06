package com.villamediana.sound.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Sound palette — cool black stage, white type, a bright blue for selection and actions, a deep
 * royal blue for the player surfaces and a mint for what is playing. Album covers tint the
 * player at runtime.
 */
object SoundColors {
    val Ink = Color(0xFF08080C)          // app background
    val Surface1 = Color(0xFF15161D)     // pills, cards
    val Surface2 = Color(0xFF1C1D26)     // sheets, raised cards
    val Surface3 = Color(0xFF262833)     // inputs, pressed
    val Hairline = Color(0xFF2B2E3A)     // dividers, outlines

    val Text = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFFA2A7BC)
    val TextFaint = Color(0xFF666B82)

    val Accent = Color(0xFF4F7DFF)       // bright blue — selection, primary actions
    val AccentPressed = Color(0xFF3D69EE)
    val OnAccent = Color(0xFFFFFFFF)
    val AccentSoft = Color(0x334F7DFF)   // 20% blue wash

    val Royal = Color(0xFF2341C9)        // mini player, player default background
    val RoyalDeep = Color(0xFF16248A)
    val Mint = Color(0xFF8CF5D9)         // playing: player controls, equalizer, current line
    val OnMint = Color(0xFF0B2A55)
    val Lyric = Color(0xFFFBE468)        // the line being sung

    val Liked = Color(0xFF8CF5D9)
    val Danger = Color(0xFFFF5C6C)
    val Success = Color(0xFF8CF5D9)

    val Scrim = Color(0xCC000000)
}
