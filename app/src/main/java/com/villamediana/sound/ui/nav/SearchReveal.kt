package com.villamediana.sound.ui.nav

import androidx.compose.ui.graphics.Color
import com.villamediana.sound.ui.theme.SoundColors
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.villamediana.sound.ui.theme.Motion
import kotlin.math.hypot
import kotlin.math.max

/**
 * The search screen grows out of the magnifier that opened it, as a circle filling the page, and
 * shrinks back into it on the way out.
 */
object SearchReveal {
    /** Center of the magnifier that opened the search, in root coordinates. */
    var origin: Offset? by mutableStateOf(null)

    /** Set by [Navigator.openSearchScreen] when it pushes the screen (not when it comes back to it). */
    internal var armed = false

    internal fun consume(): Boolean = armed.also { armed = false }
}

/** Marks this (the magnifier) as where the search screen grows from. */
fun Modifier.searchRevealOrigin(): Modifier = onGloballyPositioned { SearchReveal.origin = it.boundsInRoot().center }

private const val REVEAL_IN_MS = 460
private const val REVEAL_OUT_MS = 340

/**
 * [content] clipped to a circle from [SearchReveal.origin]: growing when the screen is pushed,
 * shrinking when it is popped. Covered by a page opened from it, or uncovered on the way back,
 * it just stays whole (the NavHost slides it as any page).
 */
@Composable
fun AnimatedVisibilityScope.CircularReveal(entry: NavBackStackEntry, navigator: Navigator, content: @Composable () -> Unit) {
    val reveal = remember { SearchReveal.consume() }
    val stack by navigator.controller.currentBackStack.collectAsState()
    val onStack = stack.any { it.id == entry.id }
    val progress by transition.animateFloat(
        transitionSpec = { tween(if (targetState == EnterExitState.Visible) REVEAL_IN_MS else REVEAL_OUT_MS, easing = Motion.Emphasized) },
        label = "searchReveal",
    ) { state ->
        when (state) {
            EnterExitState.Visible -> 1f
            EnterExitState.PreEnter -> if (reveal) 0f else 1f
            EnterExitState.PostExit -> if (onStack) 1f else 0f
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                if (progress >= 1f) {
                    drawContent()
                    return@drawWithContent
                }
                val o = SearchReveal.origin ?: Offset(size.width - 40.dp.toPx(), 56.dp.toPx())
                val far = max(max(hypot(o.x, o.y), hypot(size.width - o.x, o.y)), max(hypot(o.x, size.height - o.y), hypot(size.width - o.x, size.height - o.y)))
                val r = far * progress
                if (r <= 0f) return@drawWithContent
                // What grows is the search page itself, in its own colors from start to end (no color
                // that switches at the end). It reads as a circle because the page beneath dims as
                // it grows and a thin accent ring runs along its edge, fading out as it fills.
                drawRect(Color.Black, alpha = 0.42f * progress)
                val circle = Path().apply { addOval(Rect(o, r)) }
                clipPath(circle) { this@drawWithContent.drawContent() }
                val ring = (1f - progress * progress * progress).coerceIn(0f, 1f)
                drawCircle(SoundColors.Accent, radius = r, center = o, alpha = 0.9f * ring, style = Stroke(width = 2.5.dp.toPx()))
            },
    ) { content() }
}
