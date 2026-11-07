package com.villamediana.sound.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.villamediana.sound.data.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

private fun schemeOf(p: SoundPalette): ColorScheme {
    val base = if (p.isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.accent,
        secondary = p.text,
        onSecondary = p.ink,
        secondaryContainer = p.surface3,
        onSecondaryContainer = p.text,
        tertiary = p.accent,
        background = p.ink,
        onBackground = p.text,
        surface = p.ink,
        onSurface = p.text,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.textMuted,
        surfaceContainerLowest = p.ink,
        surfaceContainerLow = p.surface1,
        surfaceContainer = p.surface1,
        surfaceContainerHigh = p.surface2,
        surfaceContainerHighest = p.surface3,
        inverseSurface = p.text,
        inverseOnSurface = p.ink,
        outline = p.hairline,
        outlineVariant = p.hairline,
        error = p.danger,
        onError = p.ink,
        scrim = p.scrim,
    )
}

val SoundShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Spacing / sizes shared by every screen. */
object Dimens {
    val Gutter = 20.dp            // screen side padding
    val Gap = 12.dp
    val SectionGap = 28.dp
    val RowHeight = 70.dp
    val RowCover = 54.dp
    val CardCover = 150.dp        // home carousels
    val MiniPlayerHeight = 70.dp
    val BottomBarHeight = 66.dp
    val CoverCorner = 13.dp       // album art corners (soft, rounded)
}

/** Motion vocabulary — springy but calm. */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> enter() = tween<T>(durationMillis = 380, easing = Emphasized)
    fun <T> exit() = tween<T>(durationMillis = 220, easing = Emphasized)
    fun <T> soft() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium)
    val offsetSoft = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
}

/**
 * Bottom space every scrolling screen must leave so its last item clears the mini player and the
 * bottom bar. Provided by [com.villamediana.sound.ui.nav.SoundRoot].
 */
val LocalBottomInset = staticCompositionLocalOf<Dp> { 0.dp }

/**
 * Applies [mode] (Claro / Escuro / Sistema): swaps [SoundColors.palette] and the status/navigation
 * bar icons. Without a mode (tests, previews) it keeps whatever palette is current.
 */
@Composable
fun SoundTheme(mode: ThemeMode? = null, accent: String? = null, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    if (mode != null) {
        val palette = paletteFor(mode, systemDark, accent)
        if (SoundColors.palette != palette) SoundColors.palette = palette
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = palette.isLight
                    isAppearanceLightNavigationBars = palette.isLight
                }
            }
        }
    }
    val scheme = remember(SoundColors.palette) { schemeOf(SoundColors.palette) }
    MaterialTheme(colorScheme = scheme, typography = SoundTypography, shapes = SoundShapes) {
        CompositionLocalProvider(content = content)
    }
}

fun paletteFor(mode: ThemeMode, systemDark: Boolean, accent: String? = null): SoundPalette {
    val base = when (mode) {
        ThemeMode.LIGHT -> SoundPalettes.Light
        ThemeMode.DARK -> SoundPalettes.Dark
        ThemeMode.SYSTEM -> if (systemDark) SoundPalettes.Dark else SoundPalettes.Light
    }
    return base.withAccent(SoundAccents.byId(accent))
}
