package org.quicklauncher.host.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.profile.PrivateProfileItem
import org.quicklauncher.host.runtime.profile.PrivateSpaceState
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.profile.SecureOverlayProtection
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PrivateSpaceOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `locked overlay contains no private metadata and offers unlock`() {
        var unlocks = 0
        val protection = RecordingProtection()
        compose.setContent {
            MaterialTheme {
                PrivateSpaceOverlay(
                    state = state(ProfileAvailability.LOCKED, emptyList()),
                    protection = protection,
                    onLock = {},
                    onUnlock = { unlocks += 1 },
                    onLaunch = {},
                    onOpenSettings = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Private Space is locked").assertIsDisplayed()
        compose.onNodeWithText("Unlock Private Space").performClick()
        assertEquals(1, unlocks)
        assertEquals(1, protection.sessions)
    }

    @Test
    fun `unresolved overlay offers no unlock action`() {
        var unlocks = 0
        compose.setContent {
            MaterialTheme {
                PrivateSpaceOverlay(
                    state = state(ProfileAvailability.UNRESOLVED, emptyList()),
                    protection = RecordingProtection(),
                    onLock = {},
                    onUnlock = { unlocks += 1 },
                    onLaunch = {},
                    onOpenSettings = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Private Space is unavailable").assertIsDisplayed()
        compose.onNodeWithText("Unlock Private Space").assertIsNotDisplayed()
        assertEquals(0, unlocks)
    }

    @Test
    fun `lock transition removes private label immediately`() {
        val item = item("Vault")
        val protection = RecordingProtection()
        compose.setContent {
            MaterialTheme {
                PrivateSpaceOverlay(
                    state = state(
                        ProfileAvailability.AVAILABLE,
                        listOf(item),
                        ProfileTransition.LOCKING,
                    ),
                    protection = protection,
                    onLock = {},
                    onUnlock = {},
                    onLaunch = {},
                    onOpenSettings = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText(item.label).assertIsNotDisplayed()
        compose.onNodeWithText("Locking Private Space").assertIsDisplayed()
    }

    @Test
    fun `unlocked overlay routes launch lock and close actions`() {
        val item = item("Vault")
        var launches = 0
        var locks = 0
        var closes = 0
        var settings = 0
        compose.setContent {
            MaterialTheme {
                PrivateSpaceOverlay(
                    state = state(ProfileAvailability.AVAILABLE, listOf(item)),
                    protection = RecordingProtection(),
                    onLock = { locks += 1 },
                    onUnlock = {},
                    onLaunch = { launches += 1 },
                    onOpenSettings = { settings += 1 },
                    onClose = { closes += 1 },
                )
            }
        }

        compose.onNodeWithText("Vault").performClick()
        compose.onNodeWithText("Lock Private Space").performClick()
        compose.onNodeWithText("Private Space settings").performClick()
        compose.onNodeWithText("Close").performClick()

        assertEquals(1, launches)
        assertEquals(1, locks)
        assertEquals(1, settings)
        assertEquals(1, closes)
    }

    private class RecordingProtection : SecureOverlayProtection {
        var sessions = 0
        override fun begin(): AutoCloseable {
            sessions += 1
            return AutoCloseable { sessions -= 1 }
        }
    }

    private fun state(
        availability: ProfileAvailability,
        items: List<PrivateProfileItem>,
        transition: ProfileTransition = ProfileTransition.IDLE,
    ) = PrivateSpaceState(ProfileSerial.of(20), availability, transition, items)

    private fun item(label: String) = PrivateProfileItem(
        AppActivityIdentity(
            ProfileSerial.of(20),
            PackageName.parse("org.example.private"),
            ActivityName.parse("org.example.private.Main"),
        ),
        label,
        AppIcon.of(byteArrayOf(1)),
    )
}
