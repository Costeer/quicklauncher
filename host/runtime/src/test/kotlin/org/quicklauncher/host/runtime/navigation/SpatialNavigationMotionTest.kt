package org.quicklauncher.host.runtime.navigation

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.TwoWayConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialNavigationMotionTest {
    @Test
    fun `reduced motion settles immediately while normal motion retains configured duration`() {
        val reduced = SpatialNavigationMotion.settleAnimationSpec(reducedMotion = true)
        val normal = SpatialNavigationMotion.settleAnimationSpec(reducedMotion = false)

        assertTrue(reduced is SnapSpec<*>)
        assertTrue(normal is TweenSpec<*>)
        assertEquals(0, reduced.vectorize(FloatVectorConverter).durationMillis)
        assertEquals(
            SpatialNavigationMotion.SETTLE_DURATION_MILLIS,
            normal.vectorize(FloatVectorConverter).durationMillis,
        )
    }

    private companion object {
        val FloatVectorConverter = TwoWayConverter<Float, AnimationVector1D>(
            convertToVector = ::AnimationVector1D,
            convertFromVector = AnimationVector1D::value,
        )
    }
}
