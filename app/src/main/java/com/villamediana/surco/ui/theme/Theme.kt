package com.villamediana.surco.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

private val SurcoScheme = darkColorScheme(
    primary = SurcoColors.Accent,
    onPrimary = SurcoColors.OnAccent,
    primaryContainer = SurcoColors.AccentSoft,
    onPrimaryContainer = SurcoColors.Accent,
    secondary = SurcoColors.Text,
    onSecondary = SurcoColors.Ink,
    secondaryContainer = SurcoColors.Surface3,
    onSecondaryContainer = SurcoColors.Text,
    tertiary = SurcoColors.Accent,
    background = SurcoColors.Ink,
    onBackground = SurcoColors.Text,
    surface = SurcoColors.Ink,
    onSurface = SurcoColors.Text,
    surfaceVariant = SurcoColors.Surface2,
    onSurfaceVariant = SurcoColors.TextMuted,
    surfaceContainerLowest = SurcoColors.Ink,
    surfaceContainerLow = SurcoColors.Surface1,
    surfaceContainer = SurcoColors.Surface1,
    surfaceContainerHigh = SurcoColors.Surface2,
    surfaceContainerHighest = SurcoColors.Surface3,
    inverseSurface = SurcoColors.Text,
    inverseOnSurface = SurcoColors.Ink,
    outline = SurcoColors.Hairline,
    outlineVariant = SurcoColors.Hairline,
    error = SurcoColors.Danger,
    onError = SurcoColors.Ink,
    scrim = SurcoColors.Scrim,
)

val SurcoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Spacing / sizes shared by every screen. */
object Dimens {
    val Gutter = 20.dp            // screen side padding
    val Gap = 12.dp
    val SectionGap = 28.dp
    val RowHeight = 64.dp
    val RowCover = 48.dp
    val CardCover = 148.dp        // home carousels
    val MiniPlayerHeight = 64.dp
    val BottomBarHeight = 64.dp
    val CoverCorner = 6.dp        // album art corners (small, like a real sleeve)
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
 * bottom bar. Provided by [com.villamediana.surco.ui.nav.SurcoRoot].
 */
val LocalBottomInset = staticCompositionLocalOf<Dp> { 0.dp }

@Composable
fun SurcoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SurcoScheme, typography = SurcoTypography, shapes = SurcoShapes) {
        CompositionLocalProvider(content = content)
    }
}
