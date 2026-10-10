package com.normplus.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The motion grammar's one rule that is not visible in a screenshot: Remove animations means a cut. */
class MotionTest {
    private val density = Density(2.625f)

    @Test
    fun sharedAxisIsACutWhenAnimationsAreRemoved() {
        val t = NormMotion.sharedAxisX(forward = true, animationsRemoved = true, density, LayoutDirection.Ltr)
        assertEquals(EnterTransition.None, t.targetContentEnter)
        assertEquals(ExitTransition.None, t.initialContentExit)
    }

    @Test
    fun fadeThroughIsACutWhenAnimationsAreRemoved() {
        val t = NormMotion.fadeThrough(animationsRemoved = true)
        assertEquals(EnterTransition.None, t.targetContentEnter)
        assertEquals(ExitTransition.None, t.initialContentExit)
    }

    @Test
    fun backIsACutWhenAnimationsAreRemoved() {
        assertEquals(ExitTransition.None, NormMotion.backPreviewExit(animationsRemoved = true))
        assertNotEquals(ExitTransition.None, NormMotion.backPreviewExit(animationsRemoved = false))
    }

    @Test
    fun sharedAxisAnimatesOtherwise() {
        val t = NormMotion.sharedAxisX(forward = false, animationsRemoved = false, density, LayoutDirection.Rtl)
        assertNotEquals(EnterTransition.None, t.targetContentEnter)
        assertNotEquals(ExitTransition.None, t.initialContentExit)
    }
}
