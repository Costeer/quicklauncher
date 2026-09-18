package org.quicklauncher.host.platform.motion

import android.animation.ValueAnimator

/** Android platform adapter for the launcher's reduced-motion contract. */
object AndroidMotionPolicy {
    fun reducedMotion(): Boolean = reducedMotion(ValueAnimator.areAnimatorsEnabled())

    internal fun reducedMotion(animatorsEnabled: Boolean): Boolean = !animatorsEnabled
}
