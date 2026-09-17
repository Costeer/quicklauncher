package org.quicklauncher.host.runtime.theme

import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.quicklauncher.contracts.contribution.ConfigurationDocument
import org.quicklauncher.contracts.contribution.EncodedConfiguration
import org.quicklauncher.contracts.domain.ArgbColor
import org.quicklauncher.contracts.domain.ConfigTypeId
import org.quicklauncher.contracts.domain.ConfigurationDocumentId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.DestinationId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SchemaVersion
import org.quicklauncher.contracts.domain.StableKey
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.preferences.FileLauncherPreferencesStore
import org.quicklauncher.host.data.preferences.LauncherPreferences
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore
import org.quicklauncher.host.data.spatial.DestinationCoordinate
import org.quicklauncher.host.data.store.InMemoryLauncherStore
import org.quicklauncher.host.data.store.CommitResult
import org.quicklauncher.host.data.store.ConfigurationReconciliation
import org.quicklauncher.host.data.store.DestinationInstall
import org.quicklauncher.host.data.store.DestinationLayoutRecord
import org.quicklauncher.host.data.store.DestinationRecord
import org.quicklauncher.host.data.store.LauncherEdit
import org.quicklauncher.host.data.store.LauncherStore
import org.quicklauncher.host.data.store.LauncherTransaction
import org.quicklauncher.host.data.store.ModuleInstanceRecord
import org.quicklauncher.host.data.store.StoreRejection
import org.quicklauncher.host.data.store.StoreRejectionCode
import org.quicklauncher.host.data.store.StoreRevision
import org.quicklauncher.host.data.store.StoredConfigurationDocument
import org.quicklauncher.host.data.store.ThemeProfileRecord

class ThemeControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `start exposes built ins without creating rows in an otherwise empty store`() = runTest {
        val file = File(temporaryFolder.root, "empty-preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryLauncherStore()
        val preferences = FileLauncherPreferencesStore.open(file, dispatcher)
        val controller = ThemeController(store, preferences, emptyMap(), parentScope = this)

        controller.start()

        assertEquals(BuiltInThemeProfiles.All.toSet(), controller.state.value.profiles.toSet())
        assertTrue(store.read().themeProfiles.isEmpty())
        controller.close()
        preferences.close()
    }

    @Test
    fun `selection is durable while preview remains transient across recreation`() = runTest {
        val file = File(temporaryFolder.root, "preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryLauncherStore()
        val preferences = FileLauncherPreferencesStore.open(file, dispatcher)
        val custom = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/controller-test"),
            "Controller test",
            mode = ThemeModePolicy.FOLLOW_SYSTEM,
            palette = PaletteDefinition.MaterialYou(ArgbColor.of(0xff006875L)),
        )
        val first = ThemeController(store, preferences, emptyMap(), parentScope = this)

        first.start()
        assertTrue(first.save(custom, select = true) is ThemeMutationResult.Applied)
        assertTrue(first.preview(BuiltInThemeProfiles.Dark) is ThemeMutationResult.Applied)
        assertEquals(custom.id, preferences.read().themeProfileId)
        assertEquals(BuiltInThemeProfiles.DarkId, first.state.value.profileForPresentation().id)
        first.close()
        preferences.close()

        val reopenedPreferences = FileLauncherPreferencesStore.open(file, dispatcher)
        val recreated = ThemeController(store, reopenedPreferences, emptyMap(), parentScope = this)
        recreated.start()

        assertEquals(custom.id, recreated.state.value.activeProfile.id)
        assertNull(recreated.state.value.previewProfile)
        assertEquals(ThemeRecoveryReason.NONE, recreated.state.value.recoveryReason)
        recreated.close()
        reopenedPreferences.close()
    }

    @Test
    fun `corrupt selected row resolves to one complete built in fallback`() = runTest {
        val file = File(temporaryFolder.root, "corrupt-preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryLauncherStore()
        val corruptId = ThemeProfileId.parse("org.quicklauncher.theme/corrupt-test")
        val before = store.read()
        store.commit(
            LauncherTransaction(
                before.revision,
                listOf(
                    LauncherEdit.PutThemeProfile(
                        ThemeProfileRecord(corruptId, "Corrupt", encoded = "{not-json", schemaVersion = 1),
                    ),
                ),
            ),
        )
        val preferences = FileLauncherPreferencesStore.open(file, dispatcher)
        preferences.setThemeProfile(corruptId)
        val controller = ThemeController(store, preferences, emptyMap(), parentScope = this)

        controller.start()

        assertEquals(BuiltInThemeProfiles.SystemId, controller.state.value.activeProfile.id)
        assertEquals(ThemeRecoveryReason.INVALID_PROFILE, controller.state.value.recoveryReason)
        val resolved = BuiltInLauncherThemeResolver.resolve(
            ThemeResolutionRequest(false, 1f, false, controller.state.value.activeProfile),
        )
        assertEquals(BuiltInThemeProfiles.SystemId, resolved.profileId)
        validatePalette(resolved.theme.colors)
        controller.close()
        preferences.close()
    }

    @Test
    fun `checked asset inventory failures keep the built in recovery route usable`() = runTest {
        val file = File(temporaryFolder.root, "failed-assets-preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = FileLauncherPreferencesStore.open(file, dispatcher)
        val controller = ThemeController(
            InMemoryLauncherStore(),
            preferences,
            emptyMap(),
            availableFontAssets = { throw IOException("synthetic font inventory failure") },
            availableImageAssets = { throw IOException("synthetic image inventory failure") },
            cleanupAssets = { throw IOException("synthetic cleanup failure") },
            parentScope = this,
        )

        controller.start()

        assertEquals(BuiltInThemeProfiles.SystemId, controller.state.value.activeProfile.id)
        assertTrue(controller.state.value.fontAssets.isEmpty())
        assertTrue(controller.state.value.imageAssets.isEmpty())
        controller.close()
        preferences.close()
    }

    @Test
    fun `startup reconciles installed assets against current Room references`() = runTest {
        val file = File(temporaryFolder.root, "startup-asset-recovery-preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val preferences = FileLauncherPreferencesStore.open(file, dispatcher)
        val orphan = StableKey.parse("interrupted-restore-orphan")
        var referencedAtCleanup: Set<StableKey>? = null
        val controller = ThemeController(
            InMemoryLauncherStore(),
            preferences,
            emptyMap(),
            availableImageAssets = { setOf(orphan) },
            cleanupAssets = { referencedAtCleanup = it },
            parentScope = this,
        )

        controller.start()

        assertEquals(emptySet<StableKey>(), referencedAtCleanup)
        controller.close()
        preferences.close()
    }

    @Test
    fun `rejected active profile deletion does not change durable selection`() = runTest {
        val file = File(temporaryFolder.root, "delete-conflict-preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backing = InMemoryLauncherStore()
        val custom = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/delete-conflict"),
            "Delete conflict",
            mode = ThemeModePolicy.LIGHT,
            palette = PaletteDefinition.BuiltIn,
        )
        val before = backing.read()
        backing.commit(
            LauncherTransaction(
                before.revision,
                listOf(LauncherEdit.PutThemeProfile(ThemeProfileCodec.encode(custom))),
            ),
        )
        val rejecting = object : LauncherStore {
            override suspend fun read() = backing.read()
            override suspend fun commit(transaction: LauncherTransaction): CommitResult =
                CommitResult.Rejected(
                    StoreRejection(StoreRejectionCode.STALE_REVISION, "synthetic conflict"),
                    backing.read(),
                )

            override suspend fun reconcileConfigurations(): ConfigurationReconciliation =
                backing.reconcileConfigurations()
        }
        val preferences = FileLauncherPreferencesStore.open(file, dispatcher)
        preferences.setThemeProfile(custom.id)
        val controller = ThemeController(rejecting, preferences, emptyMap(), parentScope = this)
        controller.start()

        assertEquals(ThemeMutationResult.Conflict, controller.delete(custom.id))
        assertEquals(custom.id, preferences.read().themeProfileId)
        assertEquals(custom.id, controller.state.value.activeProfile.id)
        controller.close()
        preferences.close()
    }

    @Test
    fun `destination background atomically materializes a built in profile`() = runTest {
        val file = File(temporaryFolder.root, "built-in-background-preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val destinationId = DestinationId.parse("org.quicklauncher.destination/theme-test")
        val layoutId = ContributionId.parse("org.quicklauncher.layout/theme-test")
        val instanceId = ModuleInstanceId.parse("org.quicklauncher.instance/theme-test")
        val configurationId = ConfigurationDocumentId.parse("org.quicklauncher.configuration/theme-test")
        val store = InMemoryLauncherStore()
        val bootstrapped = store.commit(
            LauncherTransaction(
                StoreRevision.ZERO,
                listOf(
                    LauncherEdit.Bootstrap(
                        DestinationInstall(
                            DestinationRecord(
                                destinationId,
                                "Theme test",
                                DestinationCoordinate(0, 0),
                            ),
                            DestinationLayoutRecord(destinationId, layoutId, instanceId, true),
                            ModuleInstanceRecord(instanceId, layoutId, configurationId),
                            StoredConfigurationDocument(
                                configurationId,
                                ConfigurationDocument(
                                    ConfigTypeId.parse("org.quicklauncher.config/theme-test"),
                                    SchemaVersion.of(1),
                                    EncodedConfiguration.of("{}"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(bootstrapped is CommitResult.Committed)
        val backingPreferences = FileLauncherPreferencesStore.open(file, dispatcher)
        val preferences = FailingSelectionPreferences(backingPreferences)
        val controller = ThemeController(store, preferences, emptyMap(), parentScope = this)
        val background = BackgroundDefinition.Solid(ArgbColor.of(0xff243447L))
        val customId = ThemeProfileId.parse("org.quicklauncher.theme/materialized-system")
        controller.start()

        assertEquals(
            ThemeMutationResult.Invalid,
            controller.setDestinationBackground(destinationId, background),
        )
        assertTrue(store.read().themeProfiles.isEmpty())
        assertTrue(store.read().destinationBackgrounds.isEmpty())

        preferences.failSelectionWrites = true
        assertEquals(
            ThemeMutationResult.RecoveryRequired,
            controller.setDestinationBackground(destinationId, background, customId),
        )
        assertTrue(store.read().themeProfiles.isEmpty())
        assertTrue(store.read().destinationBackgrounds.isEmpty())
        preferences.failSelectionWrites = false

        assertTrue(
            controller.setDestinationBackground(destinationId, background, customId) is
                ThemeMutationResult.Applied,
        )
        val stored = store.read()
        assertEquals(listOf(customId), stored.themeProfiles.map { it.id })
        assertEquals(listOf(customId), stored.destinationBackgrounds.map { it.themeProfileId })
        assertEquals(customId, preferences.read().themeProfileId)
        assertEquals(customId, controller.state.value.activeProfile.id)
        assertEquals(background, controller.state.value.backgrounds[destinationId])
        controller.close()
        preferences.close()
    }

    @Test
    fun `selection write failure compensates Room profile changes`() = runTest {
        val file = File(temporaryFolder.root, "selection-failure-preferences.pb")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = InMemoryLauncherStore()
        val backingPreferences = FileLauncherPreferencesStore.open(file, dispatcher)
        val preferences = FailingSelectionPreferences(backingPreferences)
        val controller = ThemeController(store, preferences, emptyMap(), parentScope = this)
        val custom = ThemeProfile(
            ThemeProfileId.parse("org.quicklauncher.theme/selection-failure"),
            "Selection failure",
            mode = ThemeModePolicy.LIGHT,
            palette = PaletteDefinition.BuiltIn,
        )
        controller.start()

        preferences.failSelectionWrites = true
        assertEquals(ThemeMutationResult.RecoveryRequired, controller.save(custom, select = true))
        assertTrue(store.read().themeProfiles.isEmpty())
        assertEquals(null, preferences.read().themeProfileId)

        preferences.failSelectionWrites = false
        assertTrue(controller.save(custom, select = true) is ThemeMutationResult.Applied)
        preferences.failSelectionWrites = true
        assertEquals(ThemeMutationResult.RecoveryRequired, controller.delete(custom.id))
        assertEquals(listOf(custom.id), store.read().themeProfiles.map { it.id })
        assertEquals(custom.id, preferences.read().themeProfileId)

        controller.close()
        preferences.close()
    }

    private class FailingSelectionPreferences(
        private val delegate: LauncherPreferencesStore,
    ) : LauncherPreferencesStore by delegate {
        var failSelectionWrites = false

        override suspend fun setThemeProfile(themeProfileId: ThemeProfileId?): LauncherPreferences {
            if (failSelectionWrites) throw IOException("synthetic selection write failure")
            return delegate.setThemeProfile(themeProfileId)
        }
    }
}
