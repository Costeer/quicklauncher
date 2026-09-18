package org.quicklauncher.host.platform.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMotionPolicyTest {
    @Test
    fun `enabled system animators retain motion`() {
        assertFalse(AndroidMotionPolicy.reducedMotion(animatorsEnabled = true))
    }

    @Test
    fun `disabled system animators select reduced motion`() {
        assertTrue(AndroidMotionPolicy.reducedMotion(animatorsEnabled = false))
    }
}
