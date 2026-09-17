package org.quicklauncher.modules.search.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ActiveCancellationSignal
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.GenerationAwareSearchProviderSession
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.SearchProviderContribution
import org.quicklauncher.contracts.contribution.SearchProviderRequest
import org.quicklauncher.contracts.contribution.SearchProviderResultIds
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.ModuleInstanceId
import org.quicklauncher.contracts.domain.SearchResultId

@OptIn(ExperimentalCoroutinesApi::class)
class CoreSearchProvidersTest {
    @Test
    fun `every production provider uses generation aware bounded sessions`() = runTest {
        providers().forEach { provider ->
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + SupervisorJob())
            val session = provider.target.open(
                ContributionContext(
                    ModuleInstanceId.parse("org.quicklauncher.search.test/${provider.local}"),
                    CoreSearchConfiguration(maximumResults = 1),
                    ActiveCancellationSignal,
                    scope,
                ),
            ) as GenerationAwareSearchProviderSession
            val result = result(provider.id, provider.kind, "matching value")
            val received = async { session.snapshots.first { it.generation == 1L } }
            runCurrent()

            session.update(
                SearchProviderRequest(
                    1L,
                    SearchQuery.of("matching"),
                    listOf(result, result(provider.id, provider.kind, "matching second", "second")),
                ),
            )
            val snapshot = received.await()

            assertEquals(1L, snapshot.generation)
            assertEquals(1, snapshot.results.size)
            assertEquals(provider.kind, snapshot.results.single().kind)
            session.close()
            assertTrue(session.isClosed)
            scope.cancel()
        }
    }

    @Test
    fun `query replacement rejects an older generation`() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + SupervisorJob())
        val session = AppsSearchProvider.open(
            ContributionContext(
                ModuleInstanceId.parse("org.quicklauncher.search.test/apps-replacement"),
                CoreSearchConfiguration(),
                ActiveCancellationSignal,
                scope,
            ),
        ) as GenerationAwareSearchProviderSession
        session.update(SearchProviderRequest(2L, SearchQuery.of("new"), emptyList()))

        val failure = runCatching {
            session.update(SearchProviderRequest(1L, SearchQuery.of("old"), emptyList()))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        session.close()
        scope.cancel()
    }

    @Test
    fun `production providers preserve candidates for host token and fuzzy ranking`() = runTest {
        val providerId = ContributionId.parse(CoreSearchIds.APPS)
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + SupervisorJob())
        val session = AppsSearchProvider.open(
            ContributionContext(
                ModuleInstanceId.parse("org.quicklauncher.search.test/apps-host-ranking"),
                CoreSearchConfiguration(),
                ActiveCancellationSignal,
                scope,
            ),
        ) as GenerationAwareSearchProviderSession
        val received = async { session.snapshots.first { it.generation == 1L } }
        runCurrent()

        session.update(
            SearchProviderRequest(
                1L,
                SearchQuery.of("gml"),
                listOf(result(providerId, SearchResultKind.APP, "Gmail")),
            ),
        )

        assertEquals(listOf("Gmail"), received.await().results.map { it.title.value })
        session.close()
        scope.cancel()
    }
}

private data class ProviderCase(
    val local: String,
    val id: ContributionId,
    val kind: SearchResultKind,
    val target: SearchProviderContribution<CoreSearchConfiguration>,
)

private fun providers() = listOf(
    provider("apps", CoreSearchIds.APPS, SearchResultKind.APP, AppsSearchProvider),
    provider("shortcuts", CoreSearchIds.SHORTCUTS, SearchResultKind.SHORTCUT, ShortcutsSearchProvider),
    provider("commands", CoreSearchIds.COMMANDS, SearchResultKind.COMMAND, CommandsSearchProvider),
    provider("contacts", CoreSearchIds.CONTACTS, SearchResultKind.CONTACT, ContactsSearchProvider),
    provider("files", CoreSearchIds.FILES, SearchResultKind.FILE, FilesSearchProvider),
    provider("settings", CoreSearchIds.SETTINGS, SearchResultKind.SETTING, SettingsSearchProvider),
    provider(
        "graphene-settings",
        CoreSearchIds.GRAPHENE_SETTINGS,
        SearchResultKind.SETTING,
        GrapheneSettingsSearchProvider,
    ),
    provider("web", CoreSearchIds.WEB, SearchResultKind.WEB, WebSearchProvider),
    provider("information", CoreSearchIds.INFORMATION, SearchResultKind.INFORMATION, InformationSearchProvider),
)

private fun provider(
    local: String,
    id: String,
    kind: SearchResultKind,
    target: SearchProviderContribution<CoreSearchConfiguration>,
) = ProviderCase(local, ContributionId.parse(id), kind, target)

private fun result(
    providerId: ContributionId,
    kind: SearchResultKind,
    title: String,
    suffix: String = "item",
): ProviderResult {
    return ProviderResult(
        SearchProviderResultIds.create(providerId, suffix),
        DisplayText.of(title),
        null,
        kind,
        SearchResultAction.InvokeCommand(ContributionId.parse("org.quicklauncher.command/test")),
        500,
    )
}
