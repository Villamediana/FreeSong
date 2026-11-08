package com.villamediana.sound.ui.nav

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import com.villamediana.sound.ui.theme.Motion
import kotlinx.coroutines.delay
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

/**
 * Long enough to see the circle fill the page (it covers most of it by ~500 ms; the rest is its edge
 * reaching the far corner), on a gentle curve: the old fast-out one covered 90% in 200 ms and looked
 * like a glitch.
 */
private const val REVEAL_IN_MS = 800
private val RevealInEasing = CubicBezierEasing(0.33f, 0.12f, 0.35f, 1f)
private const val REVEAL_OUT_MS = 340

/**
 * The search page's contents coming in after the circle, one after another: the field and the mic,
 * then the recent searches one by one, then the genres. Only when the page grew out of the
 * magnifier; back from a page opened from it, everything is just there.
 */
class SearchEntrance internal constructor() {
    /** Frame time the circle started growing (−1 until its first frame). */
    internal var startMs = -1L

    /** Past the entrance: what comes on screen now (scrolled to, loaded late) shows as it is. */
    internal var done = false

    companion object {
        /** How long after the tap the entrance plays; later, nothing waits. */
        const val WINDOW_MS = 2_600L
    }
}

/** The entrance under way, or null when the search page was not opened from the magnifier. */
val LocalSearchEntrance = staticCompositionLocalOf<SearchEntrance?> { null }

/**
 * Fades this in, rising [rise] (and growing from [scaleFrom]), [atMs] after the tap that opened the
 * search. Something composed later in the entrance (data that came in late) starts at once.
 */
@Composable
fun Modifier.entrance(atMs: Int, durationMs: Int = 360, rise: Dp = 14.dp, scaleFrom: Float = 1f): Modifier {
    val entrance = LocalSearchEntrance.current ?: return this
    val progress = remember { Animatable(if (entrance.done) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        val now = withFrameMillis { it }
        if (entrance.startMs < 0) entrance.startMs = now
        val elapsed = now - entrance.startMs
        if (elapsed > SearchEntrance.WINDOW_MS) progress.snapTo(1f)
        else progress.animateTo(1f, tween(durationMs, delayMillis = (atMs - elapsed).coerceAtLeast(0).toInt(), easing = Motion.Emphasized))
    }
    val risePx = with(LocalDensity.current) { rise.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1 - p) * risePx
        if (scaleFrom != 1f) {
            val sc = scaleFrom + (1 - scaleFrom) * p
            scaleX = sc
            scaleY = sc
        }
    }
}

/**
 * [content] clipped to a circle from [SearchReveal.origin]: growing when the screen is pushed,
 * shrinking when it is popped. Covered by a page opened from it, or uncovered on the way back,
 * it just stays whole (the NavHost slides it as any page).
 */
@Composable
fun AnimatedVisibilityScope.CircularReveal(entry: NavBackStackEntry, navigator: Navigator, content: @Composable () -> Unit) {
    val reveal = remember { SearchReveal.consume() }
    val entrance = remember { if (reveal) SearchEntrance() else null }
    if (entrance != null) {
        LaunchedEffect(entrance) {
            entrance.startMs = withFrameMillis { it }
            delay(SearchEntrance.WINDOW_MS)
            entrance.done = true
        }
    }
    val stack by navigator.controller.currentBackStack.collectAsState()
    val onStack = stack.any { it.id == entry.id }
    val progress by transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) tween(REVEAL_IN_MS, easing = RevealInEasing) else tween(REVEAL_OUT_MS, easing = Motion.Emphasized)
        },
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
                // What grows is the search page itself, in its own color (SoundColors.Search, the
                // magnifier's color): the same surface opens and closes, nothing switches color.
                drawRect(Color.Black, alpha = 0.22f * progress)
                val circle = Path().apply { addOval(Rect(o, r)) }
                clipPath(circle) { this@drawWithContent.drawContent() }
            },
    ) { CompositionLocalProvider(LocalSearchEntrance provides entrance) { content() } }
}
