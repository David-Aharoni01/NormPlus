package com.normplus.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Dark Dial's motion grammar, and nothing beyond it: fade-through between top-level tabs,
 * Material's shared axis (X) between days, the dial's pager and the sweep of its hands, and a
 * progress bar filling. With the system's Remove animations setting on, every one of them
 * becomes a cut ([EnterTransition.None] / [ExitTransition.None], [snap]), not a faster
 * animation.
 *
 * Read whether animations are removed as `NormPlusTheme.animationsRemoved`.
 */
object NormMotion {
    /** Material 3 emphasized easings. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Material's shared-axis duration and slide distance. */
    const val SHARED_AXIS_MILLIS = 300
    val SharedAxisSlide = 30.dp

    /** Fade-through: the outgoing screen fades in the first 90 ms, the incoming one after it. */
    const val FADE_THROUGH_MILLIS = 300
    private const val FADE_THROUGH_OUT_MILLIS = 90

    /**
     * Shared axis X, for moving between days. [forward] means to a later day. The direction
     * follows the layout direction: forward slides in from the end, so in a right-to-left
     * layout it comes from the left.
     */
    fun sharedAxisX(
        forward: Boolean,
        animationsRemoved: Boolean,
        density: Density,
        layoutDirection: LayoutDirection,
    ): ContentTransform {
        if (animationsRemoved) return EnterTransition.None togetherWith ExitTransition.None
        val slide = with(density) { SharedAxisSlide.roundToPx() }
        val towardsEnd = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
        val sign = if (forward) towardsEnd else -towardsEnd
        val enter = slideInHorizontally(tween(SHARED_AXIS_MILLIS, easing = EmphasizedDecelerate)) { sign * slide } +
            fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate))
        val exit = slideOutHorizontally(tween(SHARED_AXIS_MILLIS, easing = EmphasizedAccelerate)) { -sign * slide } +
            fadeOut(tween(90, easing = EmphasizedAccelerate))
        return enter togetherWith exit
    }

    /** Fade-through, for switching between top-level destinations. */
    fun fadeThrough(animationsRemoved: Boolean): ContentTransform {
        if (animationsRemoved) return EnterTransition.None togetherWith ExitTransition.None
        val inMillis = FADE_THROUGH_MILLIS - FADE_THROUGH_OUT_MILLIS
        val enter = fadeIn(tween(inMillis, delayMillis = FADE_THROUGH_OUT_MILLIS, easing = EmphasizedDecelerate)) +
            scaleIn(tween(inMillis, delayMillis = FADE_THROUGH_OUT_MILLIS, easing = EmphasizedDecelerate), initialScale = 0.92f)
        val exit = fadeOut(tween(FADE_THROUGH_OUT_MILLIS, easing = EmphasizedAccelerate))
        return enter togetherWith exit
    }

    /** The hands' sweep to a new time: a stepper's quick, settling move. */
    const val HAND_SWEEP_MILLIS = 600

    /** The dial's hands moving to a new angle (a new minute, or calibration's nudges). */
    fun <T> handSweep(animationsRemoved: Boolean): AnimationSpec<T> =
        if (animationsRemoved) snap() else tween(HAND_SWEEP_MILLIS, easing = EmphasizedDecelerate)

    /** The dial's pager moving to a page programmatically (Earlier / Later). */
    fun <T> dialPage(animationsRemoved: Boolean): AnimationSpec<T> =
        if (animationsRemoved) snap() else tween(SHARED_AXIS_MILLIS, easing = Standard)

    /** A progress bar filling, a page dot stretching: small state changes. */
    fun <T> stateChange(animationsRemoved: Boolean): AnimationSpec<T> =
        if (animationsRemoved) snap() else tween(STATE_CHANGE_MILLIS, easing = Standard)

    const val STATE_CHANGE_MILLIS = 450

    /** For a NavHost: the enter half of [sharedAxisX]. */
    fun sharedAxisXEnter(forward: Boolean, animationsRemoved: Boolean, density: Density, layoutDirection: LayoutDirection) =
        sharedAxisX(forward, animationsRemoved, density, layoutDirection).targetContentEnter

    /** For a NavHost: the exit half of [sharedAxisX]. */
    fun sharedAxisXExit(forward: Boolean, animationsRemoved: Boolean, density: Density, layoutDirection: LayoutDirection) =
        sharedAxisX(forward, animationsRemoved, density, layoutDirection).initialContentExit
}

/**
 * Whether the system's Remove animations setting is on (Settings > Accessibility). It sets
 * every animation scale to 0; the animator scale is the one Compose's own clock follows, so
 * that is the one read here. Observed, so turning it on while the app is open takes effect.
 */
@Composable
internal fun rememberAnimationsRemoved(): Boolean {
    val context = LocalContext.current
    var removed by remember { mutableStateOf(readAnimationsRemoved(context)) }
    DisposableEffect(context) {
        val resolver = runCatching { context.contentResolver }.getOrNull()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                removed = readAnimationsRemoved(context)
            }
        }
        val registered = resolver != null && runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
            )
        }.isSuccess
        onDispose { if (registered) runCatching { resolver?.unregisterContentObserver(observer) } }
    }
    return removed
}

private fun readAnimationsRemoved(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
