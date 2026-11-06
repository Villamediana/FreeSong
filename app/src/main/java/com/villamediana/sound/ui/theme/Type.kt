package com.villamediana.sound.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.villamediana.sound.R

private fun outfit(weight: Int) = Font(
    R.font.outfit,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Outfit (OFL) — geometric and round, one family for the whole app. */
val Outfit = FontFamily(outfit(400), outfit(500), outfit(600), outfit(700), outfit(800), outfit(900))

val SoundTypography = Typography(
    displayLarge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em),
    displaySmall = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 35.sp, letterSpacing = (-0.015).em),
    headlineLarge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 33.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
    headlineSmall = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = 0.em),
    titleLarge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = 0.em),
    titleMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 16.5.sp, lineHeight = 21.sp, letterSpacing = 0.em),
    titleSmall = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp, letterSpacing = 0.em),
    bodyLarge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.em),
    bodyMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.em),
    bodySmall = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp, letterSpacing = 0.01.em),
    labelLarge = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 18.sp, letterSpacing = 0.em),
    labelMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em),
    labelSmall = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.em),
)
