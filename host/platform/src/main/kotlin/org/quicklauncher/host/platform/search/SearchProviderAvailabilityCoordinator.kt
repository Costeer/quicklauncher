package org.quicklauncher.host.platform.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.host.data.preferences.LauncherPreferencesStore
import org.quicklauncher.host.runtime.search.SearchProviderAvailability

class SearchProviderIdentitySet(
    val contacts: ContributionId,
    val files: ContributionId,
    val graphene: ContributionId,
    safeDefaults: Collection<ContributionId>,
) {
    val safeDefaults: Set<ContributionId> =
        java.util.Collections.unmodifiableSet(LinkedHashSet(safeDefaults))

    init {
        require(contacts != files && contacts != graphene && files != graphene)
        require(contacts !in safeDefaults && files !in safeDefaults && graphene !in safeDefaults)
    }
}

class SearchProviderAvailabilityCoordinator private constructor(
    private val access: SearchProviderAccessState,
    private val preferences: LauncherPreferencesStore,
    providerIds: Collection<ContributionId>,
    private val identities: SearchProviderIdentitySet,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val states = providerIds.associateWith {
        MutableStateFlow(SearchProviderAvailability.UNAVAILABLE)
    }
    private val denied = LinkedHashSet<ContributionId>()

    constructor(
        context: Context,
        preferences: LauncherPreferencesStore,
        providerIds: Collection<ContributionId>,
        identities: SearchProviderIdentitySet,
        parentScope: CoroutineScope,
    ) : this(AndroidSearchProviderAccessState(context), preferences, providerIds, identities, parentScope)

    init {
        scope.launch {
            preferences.state.collect { refresh() }
        }
    }

    fun state(id: ContributionId): StateFlow<SearchProviderAvailability> =
        requireNotNull(states[id]) { "Unknown registered search provider" }

    suspend fun bootstrapSafeProviders() {
        val current = preferences.read()
        if (!current.searchProvidersInitialized) {
            preferences.setEnabledSearchProviders(identities.safeDefaults)
        }
        refresh()
    }

    suspend fun beginContactsRequest() {
        denied.remove(identities.contacts)
        enableOnly(identities.contacts, enabled = true)
        refresh()
    }

    suspend fun completeContactsRequest(granted: Boolean) {
        if (!granted) denied += identities.contacts
        enableOnly(identities.contacts, enabled = granted)
        refresh()
        if (!granted) states[identities.contacts]?.value = SearchProviderAvailability.DENIED
    }

    suspend fun completeFileRequest(granted: Boolean) {
        if (!granted) denied += identities.files else denied.remove(identities.files)
        enableOnly(identities.files, enabled = granted)
        refresh()
        if (!granted) states[identities.files]?.value = SearchProviderAvailability.DENIED
    }

    suspend fun beginFileRequest() {
        denied.remove(identities.files)
        enableOnly(identities.files, enabled = true)
        refresh()
    }

    suspend fun setGrapheneEnabled(enabled: Boolean) {
        enableOnly(identities.graphene, enabled)
        refresh()
    }

    suspend fun setEnabled(id: ContributionId, enabled: Boolean) {
        require(id in states) { "Unknown registered search provider" }
        denied.remove(id)
        enableOnly(id, enabled)
        refresh()
    }

    suspend fun refresh() {
        val enabled = preferences.read().enabledSearchProviders
        states.forEach { (id, state) ->
            state.value = when {
                id in denied -> SearchProviderAvailability.DENIED
                id !in enabled -> SearchProviderAvailability.UNAVAILABLE
                id == identities.contacts && !access.contactsGranted() -> SearchProviderAvailability.DENIED
                id == identities.files && !access.filesGranted() -> SearchProviderAvailability.DENIED
                else -> SearchProviderAvailability.ENABLED
            }
        }
    }

    suspend fun disableRevokedProviders() {
        val enabled = preferences.read().enabledSearchProviders
        val revoked = buildSet {
            if (identities.contacts in enabled && !access.contactsGranted()) add(identities.contacts)
            if (identities.files in enabled && !access.filesGranted()) add(identities.files)
        }
        if (revoked.isNotEmpty()) {
            denied += revoked
            preferences.setEnabledSearchProviders(enabled - revoked)
            revoked.forEach { states[it]?.value = SearchProviderAvailability.DENIED }
        } else {
            refresh()
        }
    }

    suspend fun reportAccessLost(id: ContributionId) {
        require(id == identities.contacts || id == identities.files) {
            "Only access-controlled providers can lose access"
        }
        denied += id
        enableOnly(id, enabled = false)
        states[id]?.value = SearchProviderAvailability.DENIED
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun enableOnly(id: ContributionId, enabled: Boolean) {
        val current = preferences.read().enabledSearchProviders
        preferences.setEnabledSearchProviders(if (enabled) current + id else current - id)
    }

    companion object {
        internal fun forTesting(
            access: SearchProviderAccessState,
            preferences: LauncherPreferencesStore,
            providerIds: Collection<ContributionId>,
            identities: SearchProviderIdentitySet,
            parentScope: CoroutineScope,
        ) = SearchProviderAvailabilityCoordinator(
            access,
            preferences,
            providerIds,
            identities,
            parentScope,
        )
    }
}

internal interface SearchProviderAccessState {
    fun contactsGranted(): Boolean
    fun filesGranted(): Boolean
}

private class AndroidSearchProviderAccessState(context: Context) : SearchProviderAccessState {
    private val context = context.applicationContext

    override fun contactsGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun filesGranted(): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.isReadPermission && it.uri.scheme == "content"
    }
}
