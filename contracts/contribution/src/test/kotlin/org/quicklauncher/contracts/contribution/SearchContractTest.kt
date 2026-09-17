package org.quicklauncher.contracts.contribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.SearchActionId

class SearchContractTest {
    @Test
    fun `query enforces bounds and never renders its value`() {
        val privateValue = "fixture private query"
        val query = SearchQuery.of(privateValue)

        assertFalse(query.toString().contains(privateValue))
        assertFails { SearchQuery.of("x".repeat(513)) }
        assertFails { SearchQuery.of("invalid\u0000query") }
    }

    @Test
    fun `provider result identity mapping is canonical and segment exact`() {
        val provider = ContributionId.parse("org.quicklauncher.search/graphene-settings")
        val result = SearchProviderResultIds.create(provider, "route")

        assertEquals("org.quicklauncher.search.graphene.settings/route", result.value)
        assertTrue(SearchProviderResultIds.belongsTo(result, provider))
        assertFalse(
            SearchProviderResultIds.belongsTo(
                org.quicklauncher.contracts.domain.SearchResultId.parse(
                    "org.quicklauncher.search.graphene.settings.evil/route",
                ),
                provider,
            ),
        )
    }

    @Test
    fun `requests snapshots and result diagnostics are bounded and redacted`() {
        val provider = ContributionId.parse("org.quicklauncher.search/apps")
        val title = "Synthetic sensitive title"
        val result = ProviderResult(
            SearchProviderResultIds.create(provider, "fixture"),
            DisplayText.of(title),
            null,
            SearchResultKind.APP,
            SearchResultAction.Execute(SearchActionId.parse("org.quicklauncher.search.action/fixture")),
            1_000,
        )
        val source = mutableListOf(result)
        val request = SearchProviderRequest(1L, SearchQuery.of("fixture"), source)
        val snapshot = ProviderResultSnapshot(1L, source)
        source.clear()

        assertEquals(1, request.candidates.size)
        assertEquals(1, snapshot.results.size)
        assertFalse(result.toString().contains(title))
        assertFails { (request.candidates as MutableList).clear() }
        assertFails { (snapshot.results as MutableList).clear() }
        assertFails {
            ProviderResult(
                result.id,
                result.title,
                null,
                result.kind,
                result.action,
                1_001,
            )
        }
    }

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }
}
