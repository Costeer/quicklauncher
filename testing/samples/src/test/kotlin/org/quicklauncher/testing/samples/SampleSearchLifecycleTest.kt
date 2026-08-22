package org.quicklauncher.testing.samples

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.testing.fakes.FakeCancellationSignal

class SampleSearchLifecycleTest {
    @Test
    fun `query results are produced by work owned by the supplied instance scope`() {
        val host = DeterministicSearchHost()
        val session = host.open()
        val emissions = mutableListOf<List<ProviderResult>>()
        val collector = host.collect(session.results, emissions)

        assertTrue(host.hasActiveChildren)
        session.updateQuery(SearchQuery.of("normal"))
        assertTrue(emissions.isEmpty())

        host.runUntilIdle()

        assertEquals(
            listOf(SampleSearchContract.fixtures.single { it.query.value == "normal" }.expectedResults),
            emissions,
        )
        collector.cancel()
        session.close()
        host.runUntilIdle()
        host.close()
    }

    @Test
    fun `a replacement query prevents the stale query from publishing`() {
        val host = DeterministicSearchHost()
        val session = host.open()
        val emissions = mutableListOf<List<ProviderResult>>()
        val collector = host.collect(session.results, emissions)

        session.updateQuery(SearchQuery.of("normal"))
        session.updateQuery(SearchQuery.of("replacement"))
        host.runUntilIdle()

        assertEquals(listOf(emptyList<ProviderResult>()), emissions)
        collector.cancel()
        session.close()
        host.runUntilIdle()
        host.close()
    }

    @Test
    fun `closing cancels pending work completes results and rejects later queries`() {
        val host = DeterministicSearchHost()
        val session = host.open()
        val emissions = mutableListOf<List<ProviderResult>>()
        val collector = host.collect(session.results, emissions)

        session.updateQuery(SearchQuery.of("normal"))
        session.close()
        session.close()
        host.runUntilIdle()

        assertTrue(session.isClosed)
        assertTrue(collector.isCompleted)
        assertTrue(emissions.isEmpty())
        assertFalse(host.hasActiveChildren)
        assertThrows(IllegalStateException::class.java) {
            session.updateQuery(SearchQuery.of("normal"))
        }
        host.close()
    }

    @Test
    fun `host cancellation prevents pending query results from publishing`() {
        val cancellation = FakeCancellationSignal()
        val host = DeterministicSearchHost(cancellation)
        val session = host.open()
        val emissions = mutableListOf<List<ProviderResult>>()
        val collector = host.collect(session.results, emissions)

        session.updateQuery(SearchQuery.of("normal"))
        cancellation.cancel()
        host.runUntilIdle()

        assertTrue(emissions.isEmpty())
        session.close()
        host.runUntilIdle()
        assertTrue(collector.isCompleted)
        assertFalse(host.hasActiveChildren)
        host.close()
    }
}

private class DeterministicSearchHost(
    private val cancellation: FakeCancellationSignal = FakeCancellationSignal(),
) : AutoCloseable {
    private val dispatcher = QueuedDispatcher()
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + dispatcher)

    val hasActiveChildren: Boolean
        get() = rootJob.children.any(Job::isActive)

    fun open() = SampleSearchProvider.open(
        ContributionContext(
            SampleSearchContract.instanceId,
            SearchConfigurationCodec.default,
            cancellation,
            scope,
        ),
    )

    fun collect(
        flow: kotlinx.coroutines.flow.Flow<List<ProviderResult>>,
        destination: MutableList<List<ProviderResult>>,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        flow.collect(destination::add)
    }

    fun runUntilIdle() {
        dispatcher.runUntilIdle()
    }

    override fun close() {
        scope.cancel()
        dispatcher.runUntilIdle()
    }
}

private class QueuedDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.addLast(block)
    }

    fun runUntilIdle() {
        while (queued.isNotEmpty()) {
            queued.removeFirst().run()
        }
    }
}
