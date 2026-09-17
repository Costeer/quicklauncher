package org.quicklauncher.host.runtime.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import org.quicklauncher.contracts.contribution.CancellationSignal
import org.quicklauncher.contracts.contribution.ContributionContext
import org.quicklauncher.contracts.contribution.GenerationAwareSearchProviderSession
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.RegisteredSearchProvider
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.domain.ModuleInstanceId

fun interface SearchCandidateSource {
    suspend fun prepare(
        sessionId: Long,
        generation: Long,
        query: SearchQuery,
    ): SearchCandidatePreparation
}

class RegisteredSearchProviderBinding<C : Any>(
    private val entry: RegisteredSearchProvider<C>,
    override val availability: StateFlow<SearchProviderAvailability>,
    private val candidates: SearchCandidateSource,
) : SearchProviderBinding {
    override val descriptor = entry.descriptor

    override suspend fun preparedCandidates(
        sessionId: Long,
        generation: Long,
        query: SearchQuery,
    ): SearchCandidatePreparation = candidates.prepare(sessionId, generation, query)

    override fun open(scope: CoroutineScope): GenerationAwareSearchProviderSession {
        val job = checkNotNull(scope.coroutineContext[Job]) { "Search provider scope must own a Job" }
        val session = entry.target.open(
            ContributionContext(
                instanceId = ModuleInstanceId.parse(instanceId(entry.descriptor.metadata.id.value)),
                configuration = entry.codec.default,
                cancellation = object : CancellationSignal {
                    override val isCancelled: Boolean get() = !job.isActive
                },
                instanceScope = scope,
            ),
        )
        return session as? GenerationAwareSearchProviderSession
            ?: error("Production search provider must implement generation-aware sessions")
    }

    private fun instanceId(contributionId: String): String {
        val namespace = contributionId.substringBefore('/')
        val local = contributionId.substringAfter('/')
        return "$namespace.search.instance/$local"
    }
}
