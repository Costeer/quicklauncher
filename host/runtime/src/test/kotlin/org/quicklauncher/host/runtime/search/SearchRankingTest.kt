package org.quicklauncher.host.runtime.search

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.ActivityName
import org.quicklauncher.contracts.domain.AppActivityIdentity
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SearchResultId

class SearchRankingTest {
    @Test
    fun `exact prefix token and fuzzy matches have defined ordering`() = runTest {
        val values = listOf(
            value("fuzzy", "Alpha Presentation Program"),
            value("token", "Manager Application"),
            value("prefix", "App Manager"),
            value("exact", "app"),
        )

        val ranked = SearchRanker().rank("app", values)

        assertEquals(listOf("app", "App Manager", "Manager Application", "Alpha Presentation Program"), ranked.titles())
    }

    @Test
    fun `provider relevance and stable fields deterministically break ties`() = runTest {
        val low = value("z-provider", "Same", relevance = 400)
        val high = value("a-provider", "Same", relevance = 700)
        val tieB = value("b-provider", "same", relevance = 700)

        val first = SearchRanker().rank("same", listOf(low, tieB, high))
        val second = SearchRanker().rank("same", listOf(high, low, tieB))

        assertEquals(first.map { it.value.result.id }, second.map { it.value.result.id })
        assertEquals(listOf("a-provider", "b-provider", "z-provider"), first.providers())
    }

    @Test
    fun `unmatched ordinary candidates are omitted while safe actions remain available`() = runTest {
        val app = value("app", "Calendar")
        val information = value("information", "Open search recovery", kind = SearchResultKind.INFORMATION)

        val ranked = SearchRanker().rank("mail", listOf(app, information))

        assertEquals(listOf("Open search recovery"), ranked.titles())
    }

    @Test
    fun `duplicate targets merge across providers using the highest ranked result`() = runTest {
        val target = target("duplicate")
        val low = value("low", "Duplicate", relevance = 100, target = target)
        val high = value("high", "Duplicate", relevance = 900, target = target)

        val ranked = SearchRanker().rank("duplicate", listOf(low, high))

        assertEquals(1, ranked.size)
        assertEquals("high", ranked.single().value.providerId.value.substringAfterLast('/'))
    }

    @Test
    fun `bounded history retains only app and shortcut target identities`() = runTest {
        val history = BoundedSearchHistory(maximumEntries = 2)
        val first = historyTarget("first")
        val second = historyTarget("second")
        val third = historyTarget("third")

        history.record(first, 100)
        history.record(second, 200)
        history.record(third, 300)

        val entries = history.entries()
        assertEquals(listOf(second, third), entries.map { it.target }.sortedBy { key(it) })
        assertThrowsUnsupported { (entries as MutableList).clear() }
    }

    @Test
    fun `history decays and backward clocks remain bounded`() = runTest {
        val history = BoundedSearchHistory()
        val target = historyTarget("used")
        history.record(target, 10_000)
        history.record(target, 5_000)
        assertEquals(5_000, history.entries().single().lastLaunchedAtMillis)

        val recent = SearchRanker(history, SearchClock { 5_000 })
            .rank("", listOf(value("used", "Used", target = target(target)), value("plain", "Plain")))
        val decayed = SearchRanker(history, SearchClock { 5_000 + 31L * 24 * 60 * 60 * 1_000 })
            .rank("", listOf(value("used", "Used", target = target(target)), value("plain", "Plain")))

        assertTrue(recent.first().score > decayed.first { it.value.providerId == provider("used") }.score)
    }

    @Test
    fun `malformed persisted timestamps are ignored without affecting deterministic order`() = runTest {
        val malformed = object : SearchHistory {
            override suspend fun entries() = listOf(SearchHistoryEntry(historyTarget("bad"), 1, -1))
            override suspend fun record(target: SearchHistoryTarget, nowMillis: Long) = Unit
        }
        val ranked = SearchRanker(malformed, SearchClock { 0 }).rank(
            "",
            listOf(value("b", "Same"), value("a", "Same")),
        )

        assertEquals(listOf("a", "b"), ranked.providers())
    }
}

private fun value(
    providerLocal: String,
    title: String,
    relevance: Int = 500,
    target: ResolvedSearchTarget? = null,
    kind: SearchResultKind = SearchResultKind.COMMAND,
): ValidatedProviderResult {
    val provider = provider(providerLocal)
    return ValidatedProviderResult(
        providerId = provider,
        result = ProviderResult(
            id = SearchResultId.parse("org.quicklauncher.rank/item-$providerLocal"),
            title = DisplayText.of(title),
            subtitle = null,
            kind = kind,
            action = SearchResultAction.InvokeCommand(provider("command")),
            providerRelevance = relevance,
        ),
        target = target,
    )
}

private fun target(local: String): ResolvedSearchTarget = ResolvedSearchTarget(
    actionId = org.quicklauncher.contracts.domain.SearchActionId.parse("org.quicklauncher.rank/$local"),
    kind = SearchResultKind.APP,
    duplicateKey = SearchTargetKey.of("target:$local"),
    profile = ProfileSerial.of(0),
    profileKind = SearchProfileKind.PERSONAL,
    workBadged = false,
    historyTarget = historyTarget(local),
)

private fun target(historyTarget: SearchHistoryTarget): ResolvedSearchTarget = ResolvedSearchTarget(
    actionId = org.quicklauncher.contracts.domain.SearchActionId.parse("org.quicklauncher.rank/history"),
    kind = SearchResultKind.APP,
    duplicateKey = SearchTargetKey.of("history:${key(historyTarget)}"),
    profile = ProfileSerial.of(0),
    profileKind = SearchProfileKind.PERSONAL,
    workBadged = false,
    historyTarget = historyTarget,
)

private fun historyTarget(local: String): SearchHistoryTarget.App = SearchHistoryTarget.App(
    AppActivityIdentity(
        ProfileSerial.of(0),
        PackageName.parse("org.quicklauncher.$local"),
        ActivityName.parse("org.quicklauncher.$local.MainActivity"),
    ),
    SearchProfileKind.PERSONAL,
)

private fun provider(local: String): ContributionId = ContributionId.parse("org.quicklauncher.rank/$local")

private fun List<RankedResult>.titles(): List<String> = map { it.value.result.title.value }

private fun List<RankedResult>.providers(): List<String> = map { it.value.providerId.value.substringAfterLast('/') }

private fun key(value: SearchHistoryTarget): String = when (value) {
    is SearchHistoryTarget.App -> value.identity.packageName.value
    is SearchHistoryTarget.Shortcut -> value.identity.packageName.value
}

private fun assertThrowsUnsupported(block: () -> Unit) {
    var threw = false
    try {
        block()
    } catch (_: UnsupportedOperationException) {
        threw = true
    }
    assertTrue(threw)
}
