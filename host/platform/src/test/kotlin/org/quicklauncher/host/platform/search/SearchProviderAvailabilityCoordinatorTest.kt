package org.quicklauncher.host.platform.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ThemeProfileId
import org.quicklauncher.host.data.preferences.GestureMode
import org.quicklauncher.host.data.preferences.HistoryPolicy
import org.quicklauncher.host.data.preferences.LauncherPreferences
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore
import org.quicklauncher.host.data.preferences.NotificationStyle
import org.quicklauncher.host.data.preferences.OnboardingState
import org.quicklauncher.host.data.preferences.PrivateSpaceVisibility
import org.quicklauncher.host.runtime.search.SearchProviderAvailability

@OptIn(ExperimentalCoroutinesApi::class)
class SearchProviderAvailabilityCoordinatorTest {
    @Test
    fun `first bootstrap enables safe providers once and preserves an intentional empty set`() = runTest {
        val preferences = FakeSearchPreferences()
        val access = FakeSearchAccess()
        val coordinator = coordinator(access, preferences)

        coordinator.bootstrapSafeProviders()
        val first = preferences.read()
        assertTrue(first.searchProvidersInitialized)
        assertTrue(APPS in first.enabledSearchProviders)
        assertFalse(CONTACTS in first.enabledSearchProviders)
        assertFalse(FILES in first.enabledSearchProviders)
        assertFalse(GRAPHENE in first.enabledSearchProviders)

        coordinator.setEnabled(APPS, false)
        coordinator.setEnabled(SHORTCUTS, false)
        coordinator.setEnabled(COMMANDS, false)
        coordinator.setEnabled(SETTINGS, false)
        coordinator.setEnabled(WEB, false)
        coordinator.setEnabled(INFORMATION, false)
        assertTrue(preferences.read().enabledSearchProviders.isEmpty())
        coordinator.close()

        val recreated = coordinator(access, preferences)
        recreated.bootstrapSafeProviders()
        assertTrue(preferences.read().enabledSearchProviders.isEmpty())
        recreated.close()
    }

    @Test
    fun `contact denial later grant and revocation update only the affected provider`() = runTest {
        val preferences = FakeSearchPreferences(
            LauncherPreferences.create(
                enabledSearchProviders = setOf(APPS, FILES),
                searchProvidersInitialized = true,
            ),
        )
        val access = FakeSearchAccess(contacts = false, files = true)
        val coordinator = coordinator(access, preferences)
        runCurrent()

        coordinator.beginContactsRequest()
        assertEquals(SearchProviderAvailability.DENIED, coordinator.state(CONTACTS).value)
        coordinator.completeContactsRequest(granted = false)
        assertFalse(CONTACTS in preferences.read().enabledSearchProviders)
        assertEquals(SearchProviderAvailability.DENIED, coordinator.state(CONTACTS).value)
        assertTrue(APPS in preferences.read().enabledSearchProviders)
        assertTrue(FILES in preferences.read().enabledSearchProviders)

        access.contacts = true
        coordinator.beginContactsRequest()
        coordinator.completeContactsRequest(granted = true)
        assertEquals(SearchProviderAvailability.ENABLED, coordinator.state(CONTACTS).value)
        assertTrue(CONTACTS in preferences.read().enabledSearchProviders)

        access.contacts = false
        coordinator.reportAccessLost(CONTACTS)
        assertEquals(SearchProviderAvailability.DENIED, coordinator.state(CONTACTS).value)
        assertFalse(CONTACTS in preferences.read().enabledSearchProviders)
        assertTrue(APPS in preferences.read().enabledSearchProviders)
        assertTrue(FILES in preferences.read().enabledSearchProviders)
        coordinator.close()
    }

    @Test
    fun `file denial later grant and revocation update only the affected provider`() = runTest {
        val preferences = FakeSearchPreferences(
            LauncherPreferences.create(
                enabledSearchProviders = setOf(APPS, CONTACTS),
                searchProvidersInitialized = true,
            ),
        )
        val access = FakeSearchAccess(contacts = true, files = false)
        val coordinator = coordinator(access, preferences)
        runCurrent()

        coordinator.beginFileRequest()
        assertEquals(SearchProviderAvailability.DENIED, coordinator.state(FILES).value)
        coordinator.completeFileRequest(granted = false)
        assertFalse(FILES in preferences.read().enabledSearchProviders)
        assertTrue(APPS in preferences.read().enabledSearchProviders)
        assertTrue(CONTACTS in preferences.read().enabledSearchProviders)

        access.files = true
        coordinator.beginFileRequest()
        coordinator.completeFileRequest(granted = true)
        assertEquals(SearchProviderAvailability.ENABLED, coordinator.state(FILES).value)
        assertTrue(FILES in preferences.read().enabledSearchProviders)

        access.files = false
        coordinator.disableRevokedProviders()
        assertEquals(SearchProviderAvailability.DENIED, coordinator.state(FILES).value)
        assertFalse(FILES in preferences.read().enabledSearchProviders)
        assertTrue(APPS in preferences.read().enabledSearchProviders)
        assertTrue(CONTACTS in preferences.read().enabledSearchProviders)
        coordinator.close()
    }

    private fun kotlinx.coroutines.CoroutineScope.coordinator(
        access: SearchProviderAccessState,
        preferences: LauncherPreferencesStore,
    ) = SearchProviderAvailabilityCoordinator.forTesting(
        access,
        preferences,
        ALL_PROVIDERS,
        IDENTITIES,
        this,
    )

    private companion object {
        val APPS = ContributionId.parse("org.quicklauncher.search/apps")
        val SHORTCUTS = ContributionId.parse("org.quicklauncher.search/shortcuts")
        val COMMANDS = ContributionId.parse("org.quicklauncher.search/commands")
        val CONTACTS = ContributionId.parse("org.quicklauncher.search/contacts")
        val FILES = ContributionId.parse("org.quicklauncher.search/files")
        val SETTINGS = ContributionId.parse("org.quicklauncher.search/settings")
        val GRAPHENE = ContributionId.parse("org.quicklauncher.search/graphene-settings")
        val WEB = ContributionId.parse("org.quicklauncher.search/web")
        val INFORMATION = ContributionId.parse("org.quicklauncher.search/information")
        val ALL_PROVIDERS = setOf(
            APPS,
            SHORTCUTS,
            COMMANDS,
            CONTACTS,
            FILES,
            SETTINGS,
            GRAPHENE,
            WEB,
            INFORMATION,
        )
        val IDENTITIES = SearchProviderIdentitySet(
            contacts = CONTACTS,
            files = FILES,
            graphene = GRAPHENE,
            safeDefaults = setOf(APPS, SHORTCUTS, COMMANDS, SETTINGS, WEB, INFORMATION),
        )
    }
}

private class FakeSearchAccess(
    var contacts: Boolean = false,
    var files: Boolean = false,
) : SearchProviderAccessState {
    override fun contactsGranted(): Boolean = contacts
    override fun filesGranted(): Boolean = files
}

private class FakeSearchPreferences(
    initial: LauncherPreferences = LauncherPreferences.Default,
) : LauncherPreferencesStore {
    private val mutableState = MutableStateFlow(initial)
    override val state = mutableState
    override suspend fun read(): LauncherPreferences = mutableState.value
    override suspend fun setGestureMode(mode: GestureMode) = mutableState.value
    override suspend fun setEnabledSearchProviders(providerIds: Set<ContributionId>) = updateProviders(providerIds)
    override suspend fun setHistoryPolicy(policy: HistoryPolicy) = mutableState.value
    override suspend fun setThemeProfile(themeProfileId: ThemeProfileId?) = mutableState.value
    override suspend fun setNotificationStyle(style: NotificationStyle) = mutableState.value
    override suspend fun setOnboardingState(state: OnboardingState) = mutableState.value
    override suspend fun setPrivateSpaceVisibility(visibility: PrivateSpaceVisibility) = mutableState.value
    override fun close() = Unit

    private fun updateProviders(providerIds: Set<ContributionId>): LauncherPreferences {
        val current = mutableState.value
        return LauncherPreferences.create(
            gestureMode = current.gestureMode,
            enabledSearchProviders = providerIds,
            searchProvidersInitialized = true,
            historyPolicy = current.historyPolicy,
            themeProfileId = current.themeProfileId,
            notificationStyle = current.notificationStyle,
            onboardingState = current.onboardingState,
            privateSpaceVisibility = current.privateSpaceVisibility,
        ).also { mutableState.value = it }
    }
}
