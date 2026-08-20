package org.quicklauncher.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelIdentityTest {
    @Test
    fun applicationIdMatchesChannel() {
        val expectedApplicationId = when (BuildConfig.CHANNEL) {
            "stable" -> "org.quicklauncher"
            "preview" -> "org.quicklauncher.preview"
            else -> error("Unknown channel: ${BuildConfig.CHANNEL}")
        }

        assertEquals(expectedApplicationId, BuildConfig.APPLICATION_ID)
    }
}
