package org.quicklauncher.host.data.preferences

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.preferences.proto.StoredLauncherPreferences

class FileLauncherPreferencesStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun newStoreReadsDefaults() = runTest {
        val store = FileLauncherPreferencesStore.open(
            file = File(temporaryFolder.root, "launcher_preferences.pb"),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            assertEquals(LauncherPreferences.Default, store.read())
        } finally {
            store.close()
        }
    }

    @Test
    fun typedUpdatesPersistCompletePreferenceState() = runTest {
        val store = FileLauncherPreferencesStore.open(
            file = File(temporaryFolder.root, "launcher_preferences.pb"),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val providerIds = setOf(
            ContributionId.parse("org.quicklauncher.search/apps"),
            ContributionId.parse("org.quicklauncher.search/web"),
        )
        val themeProfileId = ThemeProfileId.parse("org.quicklauncher.theme/night")

        try {
            store.setGestureMode(GestureMode.EDGE_ACTIVATION)
            store.setEnabledSearchProviders(providerIds)
            store.setHistoryPolicy(HistoryPolicy.LOCAL)
            store.setThemeProfile(themeProfileId)
            assertEquals(themeProfileId, store.read().themeProfileId)
            store.setThemeProfile(null)
            assertEquals(null, store.read().themeProfileId)
            store.setThemeProfile(themeProfileId)
            store.setNotificationStyle(NotificationStyle.APPROXIMATE_COUNT)
            store.setPrivateSpaceVisibility(PrivateSpaceVisibility.HIDDEN)
            val updated = store.setOnboardingState(OnboardingState.COMPLETED)

            assertEquals(GestureMode.EDGE_ACTIVATION, updated.gestureMode)
            assertEquals(providerIds, updated.enabledSearchProviders)
            assertTrue(updated.searchProvidersInitialized)
            assertEquals(HistoryPolicy.LOCAL, updated.historyPolicy)
            assertEquals(themeProfileId, updated.themeProfileId)
            assertEquals(NotificationStyle.APPROXIMATE_COUNT, updated.notificationStyle)
            assertEquals(OnboardingState.COMPLETED, updated.onboardingState)
            assertEquals(PrivateSpaceVisibility.HIDDEN, updated.privateSpaceVisibility)
            assertEquals(updated, store.read())
            assertEquals(updated, store.state.first())
        } finally {
            store.close()
        }
    }

    @Test
    fun enabledProviderSetIsAnImmutableSnapshot() = runTest {
        val store = FileLauncherPreferencesStore.open(
            file = File(temporaryFolder.root, "launcher_preferences.pb"),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val providerId = ContributionId.parse("org.quicklauncher.search/apps")
        val callerOwnedSet = linkedSetOf(providerId)

        try {
            val updated = store.setEnabledSearchProviders(callerOwnedSet)
            callerOwnedSet.clear()

            assertEquals(setOf(providerId), updated.enabledSearchProviders)
            assertTrue(updated.searchProvidersInitialized)
            try {
                (updated.enabledSearchProviders as MutableSet).clear()
                fail("Enabled providers must not be mutable")
            } catch (_: UnsupportedOperationException) {
                // Expected from the immutable view.
            }
            assertEquals(setOf(providerId), store.read().enabledSearchProviders)
        } finally {
            store.close()
        }
    }

    @Test
    fun intentionallyEmptyProviderSetRemainsInitializedAfterReopen() = runTest {
        val file = File(temporaryFolder.root, "launcher_preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val original = FileLauncherPreferencesStore.open(file, dispatcher)
        val updated = original.setEnabledSearchProviders(emptySet())
        assertTrue(updated.searchProvidersInitialized)
        original.close()

        val reopened = FileLauncherPreferencesStore.open(file, dispatcher)
        try {
            assertTrue(reopened.read().searchProvidersInitialized)
            assertTrue(reopened.read().enabledSearchProviders.isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun concurrentTypedUpdatesAreAtomic() = runTest {
        val store = FileLauncherPreferencesStore.open(
            file = File(temporaryFolder.root, "launcher_preferences.pb"),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            awaitAll(
                async { store.setGestureMode(GestureMode.EDGE_ACTIVATION) },
                async { store.setHistoryPolicy(HistoryPolicy.LOCAL) },
            )

            val updated = store.read()
            assertEquals(GestureMode.EDGE_ACTIVATION, updated.gestureMode)
            assertEquals(HistoryPolicy.LOCAL, updated.historyPolicy)
        } finally {
            store.close()
        }
    }

    @Test
    fun closeAndReopenRestoresStateLikeProcessRecreation() = runTest {
        val file = File(temporaryFolder.root, "launcher_preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val original = FileLauncherPreferencesStore.open(file, dispatcher)
        val themeProfileId = ThemeProfileId.parse("org.quicklauncher.theme/daylight")

        original.setThemeProfile(themeProfileId)
        original.setOnboardingState(OnboardingState.COMPLETED)
        original.setNotificationStyle(NotificationStyle.APPROXIMATE_COUNT)
        original.setPrivateSpaceVisibility(PrivateSpaceVisibility.HIDDEN)
        original.close()
        original.close()

        try {
            original.read()
            fail("A disposed store must reject new operations")
        } catch (_: IllegalStateException) {
            // Expected after disposal.
        }
        try {
            original.state.first()
            fail("A disposed store must reject new state collectors")
        } catch (_: IllegalStateException) {
            // Expected after disposal.
        }

        val reopened = FileLauncherPreferencesStore.open(file, dispatcher)
        try {
            val restored = reopened.read()
            assertEquals(themeProfileId, restored.themeProfileId)
            assertEquals(OnboardingState.COMPLETED, restored.onboardingState)
            assertEquals(NotificationStyle.APPROXIMATE_COUNT, restored.notificationStyle)
            assertEquals(PrivateSpaceVisibility.HIDDEN, restored.privateSpaceVisibility)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun legacyProtoWithoutPrivateSpaceVisibilityDefaultsToVisible() = runTest {
        val file = File(temporaryFolder.root, "launcher_preferences.pb")
        LauncherPreferencesProtoCodec.defaultValue.toBuilder()
            .clearPrivateSpaceVisibility()
            .build()
            .writeTo(file.outputStream())
        val store = FileLauncherPreferencesStore.open(
            file,
            StandardTestDispatcher(testScheduler),
        )

        try {
            assertEquals(PrivateSpaceVisibility.VISIBLE, store.read().privateSpaceVisibility)
        } finally {
            store.close()
        }
    }

    @Test
    fun corruptFileIsReplacedWithValidatedDefaults() = runTest {
        val file = File(temporaryFolder.root, "launcher_preferences.pb")
        file.outputStream().use { output ->
            LauncherPreferencesProtoCodec.defaultValue.toBuilder()
                .addEnabledSearchProviderIds("not a contribution id")
                .build()
                .writeTo(output)
        }
        val store = FileLauncherPreferencesStore.open(
            file = file,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            assertEquals(LauncherPreferences.Default, store.read())
        } finally {
            store.close()
        }
    }

    @Test
    fun malformedFileIsReplacedOnDiskAndCanBeReopened() = runTest {
        val file = File(temporaryFolder.root, "launcher_preferences.pb")
        file.writeBytes(byteArrayOf(0x08))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileLauncherPreferencesStore.open(file, dispatcher)

        assertEquals(LauncherPreferences.Default, store.read())
        store.close()
        assertEquals(
            LauncherPreferencesProtoCodec.defaultValue,
            file.inputStream().use { input ->
                StoredLauncherPreferences.parseFrom(input)
            },
        )

        val reopened = FileLauncherPreferencesStore.open(file, dispatcher)
        try {
            assertEquals(LauncherPreferences.Default, reopened.read())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun futureSchemaIsPreservedForACompatibleNewerApp() = runTest {
        val file = File(temporaryFolder.root, "launcher_preferences.pb")
        val futureValue = LauncherPreferencesProtoCodec.defaultValue.toBuilder()
            .setSchemaVersion(LauncherPreferencesProtoCodec.SCHEMA_VERSION + 1)
            .build()
        file.outputStream().use(futureValue::writeTo)
        val originalBytes = file.readBytes()
        val store = FileLauncherPreferencesStore.open(
            file = file,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val failure = try {
                store.read()
                null
            } catch (exception: IOException) {
                exception
            }
            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }
                    .any { it is UnsupportedLauncherPreferencesVersionException },
            )
            assertTrue(originalBytes.contentEquals(file.readBytes()))
        } finally {
            store.close()
        }
    }

    @Test
    fun cancellingAnUpdateDoesNotCommitIt() = runTest {
        val store = FileLauncherPreferencesStore.open(
            file = File(temporaryFolder.root, "launcher_preferences.pb"),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        try {
            val update = launch(start = CoroutineStart.UNDISPATCHED) {
                store.setGestureMode(GestureMode.EDGE_ACTIVATION)
            }
            update.cancelAndJoin()

            assertEquals(LauncherPreferences.Default, store.read())
        } finally {
            store.close()
        }
    }
}
