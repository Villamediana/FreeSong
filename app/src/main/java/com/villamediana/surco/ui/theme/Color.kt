package com.villamediana.surco.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Surco palette — warm black "vinyl", paper-white ink and ONE accent (tangerine, the groove of
 * the logo). Everything else comes from the album covers at runtime.
 */
object SurcoColors {
    val Ink = Color(0xFF0B0A09)          // app background
    val Surface1 = Color(0xFF141210)     // cards, mini player
    val Surface2 = Color(0xFF1C1916)     // raised cards, sheets
    val Surface3 = Color(0xFF26221E)     // inputs, pressed
    val Hairline = Color(0xFF2E2925)     // dividers, outlines

    val Text = Color(0xFFF4EFE7)         // primary text (warm paper)
    val TextMuted = Color(0xFFA9A096)    // secondary text
    val TextFaint = Color(0xFF6F675E)    // tertiary / disabled

    val Accent = Color(0xFFFF6B35)       // tangerine — the only brand color
    val AccentPressed = Color(0xFFE85A26)
    val OnAccent = Color(0xFF1A0B04)
    val AccentSoft = Color(0x29FF6B35)   // 16% accent wash (selected chips, progress tracks)

    val Liked = Color(0xFFFF6B35)
    val Danger = Color(0xFFFF5A5F)
    val Success = Color(0xFF7BD88F)

    val Scrim = Color(0xCC000000)
}
