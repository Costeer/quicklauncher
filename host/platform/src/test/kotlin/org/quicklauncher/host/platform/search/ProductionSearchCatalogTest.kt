package org.quicklauncher.host.platform.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContentItemId
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.host.runtime.catalog.AppCatalog
import org.quicklauncher.host.runtime.catalog.AppCatalogSnapshot
import org.quicklauncher.host.runtime.catalog.AppCatalogStatus
import org.quicklauncher.host.runtime.catalog.AppIcon
import org.quicklauncher.host.runtime.catalog.AppLaunchResult
import org.quicklauncher.host.runtime.catalog.AppProfileKind
import org.quicklauncher.host.runtime.catalog.CatalogApp
import org.quicklauncher.host.runtime.catalog.CatalogProfile
import org.quicklauncher.host.runtime.profile.PrivateLaunchResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceActionResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceSettingsResult
import org.quicklauncher.host.runtime.profile.PrivateSpaceState
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileCoordinator
import org.quicklauncher.host.runtime.profile.ProfileCoordinatorState
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.profile.ProfileState
import org.quicklauncher.host.runtime.profile.ProfileTransition
import org.quicklauncher.host.runtime.profile.WorkModeResult
import org.quicklauncher.host.runtime.search.SearchExecutionTarget
import org.quicklauncher.host.runtime.search.SearchCandidatePreparation
import org.quicklauncher.host.runtime.search.SearchSettingsAvailability
import org.quicklauncher.host.runtime.search.WebProviderId
import org.quicklauncher.host.runtime.shortcuts.ShortcutCoordinator
import org.quicklauncher.host.runtime.shortcuts.ShortcutLaunchResult
import org.quicklauncher.host.runtime.shortcuts.ShortcutSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class ProductionSearchCatalogTest {
    @Test
    fun `profile policy and work badging apply before candidates cross the host boundary`() = runTest {
        val personal = ProfileSerial.of(0)
        val work = ProfileSerial.of(10)
        val privateProfile = ProfileSerial.of(20)
        val apps = FakeAppCatalog(
            AppCatalogSnapshot(
                AppCatalogStatus.READY,
                listOf(
                    catalogProfile(personal, AppProfileKind.PERSONAL, "Personal app"),
                    catalogProfile(work, AppProfileKind.WORK, "Work app"),
                ),
            ),
        )
        val profiles = FakeProfiles(
            profileState(personal, ProfileKind.PERSONAL, ProfileAvailability.AVAILABLE),
            profileState(work, ProfileKind.WORK, ProfileAvailability.AVAILABLE),
            profileState(privateProfile, ProfileKind.PRIVATE, ProfileAvailability.LOCKED),
        )
        val catalog = ProductionSearchCatalog(
            apps,
            profiles,
            FakeShortcuts(),
            emptyList(),
            SearchSettingsAvailability { false },
            FILE_PROVIDER,
            this,
        )
        val provider = ContributionId.parse("org.quicklauncher.search/apps")

        val candidates = catalog.apps(provider).prepare(1L, 1L, SearchQuery.of("app")).results()

        assertEquals(listOf("Personal app", "Work app"), candidates.map { it.title.value })
        val workResult = candidates.single { it.title.value == "Work app" }
        val workAction = (workResult.action as SearchResultAction.Execute).id
        assertTrue(requireNotNull(catalog.resolve(workAction)).workBadged)
        assertEquals(work, catalog.resolve(workAction)?.profile)

        profiles.mutableState.value = profileCoordinatorState(
            profileState(personal, ProfileKind.PERSONAL, ProfileAvailability.AVAILABLE),
            profileState(work, ProfileKind.WORK, ProfileAvailability.QUIET),
            profileState(privateProfile, ProfileKind.PRIVATE, ProfileAvailability.LOCKED),
        )
        runCurrent()
        assertNull(catalog.resolve(workAction))

        profiles.mutableState.value = profileCoordinatorState(
            profileState(personal, ProfileKind.PERSONAL, ProfileAvailability.AVAILABLE),
            profileState(work, ProfileKind.WORK, ProfileAvailability.AVAILABLE),
            profileState(privateProfile, ProfileKind.PRIVATE, ProfileAvailability.LOCKED),
        )
        runCurrent()
        assertNull("An old work target must not revive from the cache", catalog.resolve(workAction))

        val refreshed = catalog.apps(provider).prepare(1L, 2L, SearchQuery.of("app")).results()
        assertEquals(listOf("Personal app", "Work app"), refreshed.map { it.title.value })
        assertTrue(requireNotNull(catalog.resolve(workAction)).workBadged)
        catalog.close()
    }

    @Test
    fun `ephemeral web targets are isolated by presentation and released with their owner`() = runTest {
        val catalog = ProductionSearchCatalog(
            FakeAppCatalog(AppCatalogSnapshot(AppCatalogStatus.READY, emptyList())),
            FakeProfiles(),
            FakeShortcuts(),
            emptyList(),
            SearchSettingsAvailability { false },
            FILE_PROVIDER,
            this,
        )
        val provider = ContributionId.parse("org.quicklauncher.search/web")
        val first = catalog.web(provider).prepare(1L, 1L, SearchQuery.of("synthetic one")).results().first()
        val second = catalog.web(provider).prepare(2L, 1L, SearchQuery.of("synthetic two")).results().first()
        val firstAction = (first.action as SearchResultAction.Execute).id
        val secondAction = (second.action as SearchResultAction.Execute).id

        val firstTarget = catalog.executionTarget(firstAction) as SearchExecutionTarget.Web
        val secondTarget = catalog.executionTarget(secondAction) as SearchExecutionTarget.Web
        assertEquals("synthetic one", firstTarget.query.value)
        assertEquals("synthetic two", secondTarget.query.value)
        assertEquals(WebProviderId.DUCKDUCKGO, firstTarget.provider)

        catalog.releaseSession(1L)
        assertNull(catalog.executionTarget(firstAction))
        assertEquals("synthetic two", (catalog.executionTarget(secondAction) as SearchExecutionTarget.Web).query.value)
        catalog.close()
    }

    @Test
    fun `profile change during protected lookup cannot reinsert prepared metadata`() = runTest {
        val personal = ProfileSerial.of(0)
        val profiles = FakeProfiles(
            profileState(personal, ProfileKind.PERSONAL, ProfileAvailability.AVAILABLE),
        )
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = object : ContactSearchSource {
            override suspend fun query(
                query: SearchQuery,
                personalProfile: ProfileSerial,
                eligibleWorkProfile: ProfileSerial?,
            ): ContactSearchResult {
                started.complete(Unit)
                release.await()
                return ContactSearchResult.Available(
                    listOf(ContactSearchRecord(personal, 1L, "synthetic-key", "Synthetic contact", false)),
                )
            }
        }
        val catalog = ProductionSearchCatalog(
            FakeAppCatalog(AppCatalogSnapshot(AppCatalogStatus.READY, emptyList())),
            profiles,
            FakeShortcuts(),
            emptyList(),
            SearchSettingsAvailability { false },
            FILE_PROVIDER,
            this,
        )
        val provider = ContributionId.parse("org.quicklauncher.search/contacts")

        val preparation = async {
            catalog.contacts(provider, adapter).prepare(4L, 1L, SearchQuery.of("synthetic"))
        }
        started.await()
        profiles.mutableState.value = profileCoordinatorState(
            profileState(personal, ProfileKind.PERSONAL, ProfileAvailability.QUIET),
        )
        release.complete(Unit)

        assertTrue(preparation.await().results().isEmpty())
        catalog.close()
    }

    @Test
    fun `graphene catalog keeps a callable public security fallback`() = runTest {
        val catalog = ProductionSearchCatalog(
            FakeAppCatalog(AppCatalogSnapshot(AppCatalogStatus.READY, emptyList())),
            FakeProfiles(),
            FakeShortcuts(),
            emptyList(),
            SearchSettingsAvailability { target -> target.route == org.quicklauncher.host.runtime.search.SearchSettingsRoute.SECURITY },
            FILE_PROVIDER,
            this,
        )
        val provider = ContributionId.parse("org.quicklauncher.search/graphene-settings")

        val result = catalog.grapheneSettings(provider)
            .prepare(8L, 1L, SearchQuery.of("security"))
            .results()
            .single()
        val action = (result.action as SearchResultAction.Execute).id

        assertEquals("GrapheneOS security settings", result.title.value)
        assertEquals(
            org.quicklauncher.host.runtime.search.SearchSettingsRoute.SECURITY,
            (catalog.executionTarget(action) as SearchExecutionTarget.Setting).route,
        )
        catalog.close()
    }

    @Test
    fun `reviewed graphene entries retain a callable public app-details fallback`() = runTest {
        val personal = ProfileSerial.of(0)
        val catalog = ProductionSearchCatalog(
            FakeAppCatalog(
                AppCatalogSnapshot(
                    AppCatalogStatus.READY,
                    listOf(catalogProfile(personal, AppProfileKind.PERSONAL, "Synthetic app")),
                ),
            ),
            FakeProfiles(profileState(personal, ProfileKind.PERSONAL, ProfileAvailability.AVAILABLE)),
            FakeShortcuts(),
            emptyList(),
            SearchSettingsAvailability { target ->
                target.fallbackRoute ==
                    org.quicklauncher.host.runtime.search.SearchSettingsRoute.APPLICATION_DETAILS
            },
            FILE_PROVIDER,
            this,
            reviewedGrapheneBuild = { true },
        )
        val provider = ContributionId.parse("org.quicklauncher.search/graphene-settings")

        val results = catalog.grapheneSettings(provider)
            .prepare(9L, 1L, SearchQuery.of("protection"))
            .results()

        assertEquals(2, results.size)
        results.forEach { result ->
            val target = catalog.executionTarget((result.action as SearchResultAction.Execute).id)
                as SearchExecutionTarget.Setting
            assertEquals(
                org.quicklauncher.host.runtime.search.SearchSettingsRoute.APPLICATION_DETAILS,
                target.fallbackRoute,
            )
        }
        catalog.close()
    }
}

private val FILE_PROVIDER = ContributionId.parse("org.quicklauncher.search/files")

private fun SearchCandidatePreparation.results() =
    (this as SearchCandidatePreparation.Available).results

private class FakeAppCatalog(initial: AppCatalogSnapshot) : AppCatalog {
    val mutableState = MutableStateFlow(initial)
    override val state = mutableState
    override suspend fun refresh() = Unit
    override suspend fun launch(identity: AppActivityIdentity) = AppLaunchResult.Launched
    override fun close() = Unit
}

private class FakeProfiles(vararg profiles: ProfileState) : ProfileCoordinator {
    val mutableState = MutableStateFlow(profileCoordinatorState(*profiles))
    override val state = mutableState
    override suspend fun refresh() = Unit
    override suspend fun setWorkMode(profile: ProfileSerial, enabled: Boolean) = WorkModeResult.Requested
    override suspend fun setPrivateSpaceLocked(locked: Boolean) = PrivateSpaceActionResult.Requested
    override suspend fun launchPrivate(identity: AppActivityIdentity) = PrivateLaunchResult.REJECTED
    override suspend fun openPrivateSpaceSettings() = PrivateSpaceSettingsResult.Rejected
    override fun close() = Unit
}

private class FakeShortcuts : ShortcutCoordinator {
    override val state = MutableStateFlow(ShortcutSnapshot.Empty)
    override suspend fun refresh() = Unit
    override suspend fun launch(placementId: ContentItemId) = ShortcutLaunchResult.Closed
    override fun close() = Unit
}

private fun catalogProfile(serial: ProfileSerial, kind: AppProfileKind, label: String): CatalogProfile {
    val packageName = PackageName.parse("org.quicklauncher.synthetic.${kind.name.lowercase()}")
    return CatalogProfile(
        serial,
        kind,
        available = true,
        badgeText = if (kind == AppProfileKind.WORK) "Work" else null,
        apps = listOf(
            CatalogApp(
                AppActivityIdentity(
                    serial,
                    packageName,
                    ActivityName.parse("${packageName.value}.MainActivity"),
                ),
                label,
                AppIcon.of(byteArrayOf(1)),
                favorite = false,
            ),
        ),
    )
}

private fun profileState(
    serial: ProfileSerial,
    kind: ProfileKind,
    availability: ProfileAvailability,
) = ProfileState(serial, kind, availability, ProfileTransition.IDLE)

private fun profileCoordinatorState(vararg profiles: ProfileState) = ProfileCoordinatorState(
    profiles.toList(),
    PrivateSpaceState(
        profiles.singleOrNull { it.kind == ProfileKind.PRIVATE }?.serial,
        profiles.singleOrNull { it.kind == ProfileKind.PRIVATE }?.availability
            ?: ProfileAvailability.UNRESOLVED,
        ProfileTransition.IDLE,
        emptyList(),
    ),
)
