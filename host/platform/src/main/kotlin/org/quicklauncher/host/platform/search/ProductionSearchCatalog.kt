package org.quicklauncher.host.platform.search

import android.os.Build
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.quicklauncher.contracts.contribution.CommandContext
import org.quicklauncher.contracts.contribution.DisplayText
import org.quicklauncher.contracts.contribution.ProviderResult
import org.quicklauncher.contracts.contribution.RegisteredLauncherCommand
import org.quicklauncher.contracts.contribution.SearchQuery
import org.quicklauncher.contracts.contribution.SearchProviderResultIds
import org.quicklauncher.contracts.contribution.SearchResultAction
import org.quicklauncher.contracts.contribution.SearchResultKind
import org.quicklauncher.contracts.domain.ContributionId
import org.quicklauncher.contracts.domain.PackageName
import org.quicklauncher.contracts.domain.ProfileSerial
import org.quicklauncher.contracts.domain.SearchActionId
import org.quicklauncher.contracts.domain.SearchResultId
import org.quicklauncher.host.runtime.catalog.AppCatalog
import org.quicklauncher.host.runtime.catalog.AppProfileKind
import org.quicklauncher.host.runtime.profile.ProfileAvailability
import org.quicklauncher.host.runtime.profile.ProfileCoordinator
import org.quicklauncher.host.runtime.profile.ProfileKind
import org.quicklauncher.host.runtime.search.ExecutableSearchTargetCatalog
import org.quicklauncher.host.runtime.search.ResolvedSearchTarget
import org.quicklauncher.host.runtime.search.SearchCandidateSource
import org.quicklauncher.host.runtime.search.SearchCandidatePreparation
import org.quicklauncher.host.runtime.search.SearchExecutionTarget
import org.quicklauncher.host.runtime.search.SearchProfileKind
import org.quicklauncher.host.runtime.search.SearchRecoveryAction
import org.quicklauncher.host.runtime.search.SearchSettingsRoute
import org.quicklauncher.host.runtime.search.SearchSettingsAvailability
import org.quicklauncher.host.runtime.search.SearchTargetKey
import org.quicklauncher.host.runtime.search.WebProviderId
import org.quicklauncher.host.runtime.shortcuts.ShortcutCoordinator

class ProductionSearchCatalog(
    private val appCatalog: AppCatalog,
    private val profiles: ProfileCoordinator,
    private val shortcuts: ShortcutCoordinator,
    private val commands: List<RegisteredLauncherCommand<*>>,
    private val settings: SearchSettingsAvailability,
    private val fileProviderId: ContributionId,
    parentScope: CoroutineScope,
    private val reviewedGrapheneBuild: () -> Boolean = GrapheneSearchCatalog::isReviewedBuild,
) : ExecutableSearchTargetCatalog, AutoCloseable {
    private val lock = Any()
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    private val mutableInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val actions = LinkedHashMap<SearchActionId, ActionEntry>()
    private val generations = LinkedHashMap<SessionProviderKey, Long>()
    private val fileAdapters = LinkedHashSet<FileSearchSource>()
    override val invalidations: Flow<Unit> = mutableInvalidations

    init {
        scope.launch { appCatalog.state.collect { invalidateTargets() } }
        scope.launch { profiles.state.collect { invalidateTargets() } }
        scope.launch { shortcuts.state.collect { invalidateTargets() } }
    }

    fun apps(providerId: ContributionId): SearchCandidateSource = SearchCandidateSource { sessionId, generation, _ ->
        val eligibleProfiles = profiles.state.value.profiles.asSequence()
            .filter {
                it.kind != ProfileKind.PRIVATE &&
                    it.availability == ProfileAvailability.AVAILABLE
            }
            .map { it.serial }
            .toSet()
        val candidates = appCatalog.state.value.profiles.asSequence()
            .filter { it.available && it.serial in eligibleProfiles }
            .flatMap { profile ->
                profile.apps.asSequence().filter { it.searchVisible }.map { app -> profile to app }
            }
            .take(MAX_CANDIDATE_SCAN)
            .sortedWith(compareBy({ it.second.label.lowercase() }, { it.second.identity.packageName.value }))
            .map { (profile, app) ->
                val target = SearchExecutionTarget.App(app.identity)
                val descriptor = ResolvedSearchTarget(
                    actionId = actionId(providerId, sessionId, targetKey(target)),
                    kind = SearchResultKind.APP,
                    duplicateKey = SearchTargetKey.of(targetKey(target)),
                    profile = profile.serial,
                    profileKind = if (profile.kind == AppProfileKind.WORK) {
                        SearchProfileKind.WORK
                    } else {
                        SearchProfileKind.PERSONAL
                    },
                    workBadged = profile.kind == AppProfileKind.WORK,
                    historyTarget = org.quicklauncher.host.runtime.search.SearchHistoryTarget.App(
                        app.identity,
                        if (profile.kind == AppProfileKind.WORK) {
                            SearchProfileKind.WORK
                        } else {
                            SearchProfileKind.PERSONAL
                        },
                    ),
                )
                Candidate(
                    ProviderResult(
                        resultId(providerId, targetKey(target)),
                        DisplayText.of(app.label),
                        if (profile.kind == AppProfileKind.WORK) DisplayText.of("Work") else null,
                        SearchResultKind.APP,
                        SearchResultAction.Execute(descriptor.actionId),
                        700,
                    ),
                    descriptor,
                    target,
                )
            }
            .take(MAX_PREPARED_RESULTS)
            .toList()
        prepared(replace(providerId, sessionId, generation, candidates))
    }

    fun shortcuts(providerId: ContributionId): SearchCandidateSource = SearchCandidateSource { sessionId, generation, _ ->
        val profileBySerial = profiles.state.value.profiles.associateBy { it.serial }
        val candidates = shortcuts.state.value.availableShortcuts.asSequence()
            .mapNotNull { shortcut ->
                val profile = profileBySerial[shortcut.target.profile] ?: return@mapNotNull null
                if (profile.kind == ProfileKind.PRIVATE || profile.availability != ProfileAvailability.AVAILABLE) {
                    return@mapNotNull null
                }
                val target = SearchExecutionTarget.Shortcut(shortcut.target)
                val key = targetKey(target)
                val descriptor = ResolvedSearchTarget(
                    actionId(providerId, sessionId, key),
                    SearchResultKind.SHORTCUT,
                    SearchTargetKey.of(key),
                    profile.serial,
                    if (profile.kind == ProfileKind.WORK) SearchProfileKind.WORK else SearchProfileKind.PERSONAL,
                    workBadged = profile.kind == ProfileKind.WORK,
                    historyTarget = org.quicklauncher.host.runtime.search.SearchHistoryTarget.Shortcut(
                        shortcut.target,
                        if (profile.kind == ProfileKind.WORK) {
                            SearchProfileKind.WORK
                        } else {
                            SearchProfileKind.PERSONAL
                        },
                    ),
                )
                Candidate(
                    ProviderResult(
                        resultId(providerId, key),
                        DisplayText.of(shortcut.label),
                        if (profile.kind == ProfileKind.WORK) DisplayText.of("Work") else null,
                        SearchResultKind.SHORTCUT,
                        SearchResultAction.Execute(descriptor.actionId),
                        if (shortcut.pinned) 750 else 650,
                    ),
                    descriptor,
                    target,
                )
            }
            .take(MAX_CANDIDATE_SCAN)
            .sortedWith(compareBy({ it.result.title.value.lowercase() }, { it.result.id.value }))
            .take(MAX_PREPARED_RESULTS)
            .toList()
        prepared(replace(providerId, sessionId, generation, candidates))
    }

    fun commands(providerId: ContributionId): SearchCandidateSource = SearchCandidateSource { _, _, _ ->
        prepared(immutableResults(
            commands.asSequence()
                .filter { CommandContext.SEARCH in it.descriptor.contexts }
                .sortedBy { it.descriptor.metadata.id.value }
                .map { command ->
                    ProviderResult(
                        resultId(providerId, command.descriptor.metadata.id.value),
                        command.descriptor.metadata.displayName,
                        command.descriptor.metadata.description,
                        SearchResultKind.COMMAND,
                        SearchResultAction.InvokeCommand(command.descriptor.metadata.id),
                        600,
                    )
                }
                .take(MAX_PREPARED_RESULTS)
                .toList(),
        ))
    }

    fun contacts(
        providerId: ContributionId,
        adapter: ContactSearchSource,
        onAccessLost: suspend () -> Unit = {},
    ): SearchCandidateSource = SearchCandidateSource { sessionId, generation, query ->
        val available = profiles.state.value.profiles.filter {
            it.availability == ProfileAvailability.AVAILABLE && it.kind != ProfileKind.PRIVATE
        }
        val personal = available.singleOrNull { it.kind == ProfileKind.PERSONAL }
            ?: return@SearchCandidateSource SearchCandidatePreparation.Unavailable
        val work = available.singleOrNull { it.kind == ProfileKind.WORK }
        val records = when (val result = adapter.query(query, personal.serial, work?.serial)) {
            is ContactSearchResult.Available -> result.records
            ContactSearchResult.Denied -> {
                onAccessLost()
                return@SearchCandidateSource SearchCandidatePreparation.Denied
            }
            ContactSearchResult.Unavailable ->
                return@SearchCandidateSource SearchCandidatePreparation.Unavailable
        }
        val candidates = records.map { record ->
            val target = SearchExecutionTarget.Contact(
                record.profile,
                record.contactId,
                record.lookupKey,
            )
            val key = "contact:${record.profile.value}:${record.contactId}:${record.lookupKey}"
            val descriptor = ResolvedSearchTarget(
                actionId(providerId, sessionId, key),
                SearchResultKind.CONTACT,
                SearchTargetKey.of(key),
                record.profile,
                if (record.work) SearchProfileKind.WORK else SearchProfileKind.PERSONAL,
                workBadged = record.work,
                historyTarget = null,
            )
            Candidate(
                ProviderResult(
                    resultId(providerId, key),
                    DisplayText.of(record.displayName),
                    if (record.work) DisplayText.of("Work") else null,
                    SearchResultKind.CONTACT,
                    SearchResultAction.Execute(descriptor.actionId),
                    600,
                ),
                descriptor,
                target,
            )
        }
        prepared(replace(providerId, sessionId, generation, candidates))
    }

    fun files(
        providerId: ContributionId,
        adapter: FileSearchSource,
        onAccessLost: suspend () -> Unit = {},
    ): SearchCandidateSource = SearchCandidateSource { sessionId, generation, query ->
        synchronized(lock) { fileAdapters += adapter }
        val records = when (val result = adapter.query(sessionId, query)) {
            is FileSearchResult.Available -> result.records
            FileSearchResult.Denied -> {
                onAccessLost()
                return@SearchCandidateSource SearchCandidatePreparation.Denied
            }
            FileSearchResult.Unavailable ->
                return@SearchCandidateSource SearchCandidatePreparation.Unavailable
        }
        val candidates = records.map { record ->
            val target = SearchExecutionTarget.File(sessionId, record.authorizationId, record.documentId)
            val key = "file:${record.authorizationId.value}:${record.documentId.value}"
            val descriptor = descriptor(providerId, sessionId, key, SearchResultKind.FILE, null, false)
            Candidate(
                ProviderResult(
                    resultId(providerId, key),
                    DisplayText.of(record.displayName),
                    record.mimeType?.let(DisplayText::of),
                    SearchResultKind.FILE,
                    SearchResultAction.Execute(descriptor.actionId),
                    550,
                ),
                descriptor,
                target,
            )
        }
        prepared(replace(providerId, sessionId, generation, candidates))
    }

    fun publicSettings(providerId: ContributionId): SearchCandidateSource =
        SearchCandidateSource { sessionId, generation, _ ->
        val routes = listOf(
            SearchSettingsRoute.ROOT to "Settings",
            SearchSettingsRoute.WIFI to "Wi-Fi settings",
            SearchSettingsRoute.BLUETOOTH to "Bluetooth settings",
            SearchSettingsRoute.SECURITY to "Security settings",
            SearchSettingsRoute.PRIVACY to "Privacy settings",
            SearchSettingsRoute.ACCESSIBILITY to "Accessibility settings",
            SearchSettingsRoute.NOTIFICATION_LISTENER to "Notification access",
        )
        val candidates = routes.mapNotNull { (route, label) ->
            settingCandidate(providerId, sessionId, route, label, null)?.takeIf {
                settings.isCallable(it.target as SearchExecutionTarget.Setting)
            }
        }
        prepared(replace(providerId, sessionId, generation, candidates))
    }

    fun grapheneSettings(providerId: ContributionId): SearchCandidateSource =
        SearchCandidateSource { sessionId, generation, _ ->
        val eligibleProfiles = profiles.state.value.profiles.asSequence()
            .filter {
                it.kind != ProfileKind.PRIVATE &&
                    it.availability == ProfileAvailability.AVAILABLE
            }
            .map { it.serial }
            .toSet()
        val privateCandidates = if (reviewedGrapheneBuild()) {
            appCatalog.state.value.profiles.asSequence()
                .filter { it.available && it.serial in eligibleProfiles }
                .flatMap { it.apps.asSequence() }
                .take(MAX_PREPARED_RESULTS / 2)
                .flatMap { app ->
                    sequenceOf(
                        SearchSettingsRoute.GRAPHENE_NATIVE_DEBUGGING to "Native debugging protection",
                        SearchSettingsRoute.GRAPHENE_HARDENED_MALLOC to "Hardened memory allocator",
                    ).mapNotNull { (route, label) ->
                        settingCandidate(
                            providerId,
                            sessionId,
                            route,
                            label,
                            app.identity.packageName,
                            SearchSettingsRoute.APPLICATION_DETAILS,
                        )
                            ?.takeIf { settings.isCallable(it.target as SearchExecutionTarget.Setting) }
                    }
                }
                .take(MAX_PREPARED_RESULTS)
                .toList()
        } else {
            emptyList()
        }
        val candidates = privateCandidates.ifEmpty {
            listOfNotNull(
                settingCandidate(
                    providerId,
                    sessionId,
                    SearchSettingsRoute.SECURITY,
                    "GrapheneOS security settings",
                    null,
                )?.takeIf { settings.isCallable(it.target as SearchExecutionTarget.Setting) },
            )
        }
        prepared(replace(providerId, sessionId, generation, candidates))
    }

    fun web(providerId: ContributionId): SearchCandidateSource = SearchCandidateSource { sessionId, generation, query ->
        val candidates = WebProviderId.entries.map { webProvider ->
            val target = SearchExecutionTarget.Web(webProvider, query)
            val key = "web:${webProvider.name.lowercase()}:${digest(query.value)}"
            val descriptor = descriptor(providerId, sessionId, key, SearchResultKind.WEB, null, false)
            Candidate(
                ProviderResult(
                    resultId(providerId, key),
                    DisplayText.of(
                        when (webProvider) {
                            WebProviderId.DUCKDUCKGO -> "Search DuckDuckGo"
                            WebProviderId.BRAVE -> "Search Brave"
                        },
                    ),
                    null,
                    SearchResultKind.WEB,
                    SearchResultAction.Execute(descriptor.actionId),
                    400,
                ),
                descriptor,
                target,
            )
        }
        prepared(replace(providerId, sessionId, generation, candidates))
    }

    fun information(providerId: ContributionId): SearchCandidateSource =
        SearchCandidateSource { sessionId, generation, query ->
        val candidates = if (query.value.isBlank()) {
            listOf(
                informationCandidate(
                    providerId,
                    sessionId,
                    "Enable contact search",
                    SearchExecutionTarget.Information(SearchRecoveryAction.ENABLE_CONTACTS),
                ),
                informationCandidate(
                    providerId,
                    sessionId,
                    "Authorize a file",
                    SearchExecutionTarget.Information(SearchRecoveryAction.AUTHORIZE_FILES),
                ),
                informationCandidate(
                    providerId,
                    sessionId,
                    "Search settings",
                    SearchExecutionTarget.Information(SearchRecoveryAction.OPEN_LAUNCHER_SETTINGS),
                ),
                informationCandidate(
                    providerId,
                    sessionId,
                    "Open app permission settings",
                    SearchExecutionTarget.Information(SearchRecoveryAction.OPEN_APP_DETAILS),
                ),
            )
        } else {
            emptyList()
        }
        prepared(replace(providerId, sessionId, generation, candidates))
    }

    override fun resolve(id: SearchActionId): ResolvedSearchTarget? = synchronized(lock) {
        actions[id]?.takeIf { isCurrent(it.target) }?.descriptor
    }

    override fun executionTarget(id: SearchActionId): SearchExecutionTarget? = synchronized(lock) {
        actions[id]?.target?.takeIf(::isCurrent)
    }

    override fun releaseSession(sessionId: Long) {
        synchronized(lock) {
            actions.entries.removeAll { it.value.sessionId == sessionId }
            generations.keys.removeAll { it.sessionId == sessionId }
            fileAdapters.forEach { it.releaseSession(sessionId) }
        }
    }

    override fun releaseProvider(sessionId: Long, providerId: ContributionId) {
        synchronized(lock) {
            actions.entries.removeAll {
                it.value.sessionId == sessionId && it.value.providerId == providerId
            }
            generations.remove(SessionProviderKey(sessionId, providerId))
            if (providerId == fileProviderId) {
                fileAdapters.forEach { it.releaseSession(sessionId) }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            actions.clear()
            generations.clear()
        }
        scope.cancel()
    }

    private suspend fun replace(
        providerId: ContributionId,
        sessionId: Long,
        generation: Long,
        values: List<Candidate>,
    ): List<ProviderResult> {
        val context = currentCoroutineContext()
        context.ensureActive()
        return synchronized(lock) {
            context.ensureActive()
            val owner = SessionProviderKey(sessionId, providerId)
            val currentGeneration = generations[owner]
            if (currentGeneration != null && generation < currentGeneration) {
                return@synchronized emptyList()
            }
            generations[owner] = generation
            actions.entries.removeAll {
                it.value.sessionId == sessionId && it.value.providerId == providerId
            }
            val eligible = values.filter { value -> isCurrent(value.target) }
            context.ensureActive()
            eligible.forEach { value ->
                actions[value.descriptor.actionId] = ActionEntry(
                    providerId,
                    sessionId,
                    generation,
                    value.descriptor,
                    value.target,
                )
            }
            immutableResults(eligible.map(Candidate::result))
        }
    }

    private fun prepared(values: Collection<ProviderResult>): SearchCandidatePreparation =
        SearchCandidatePreparation.Available(values)

    private fun isCurrent(target: SearchExecutionTarget): Boolean = when (target) {
        is SearchExecutionTarget.App -> {
            val profile = profiles.state.value.profiles.singleOrNull {
                it.serial == target.identity.profile
            }
            profile != null && profile.kind != ProfileKind.PRIVATE &&
                profile.availability == ProfileAvailability.AVAILABLE &&
                appCatalog.state.value.profiles.any { catalogProfile ->
                    catalogProfile.available && catalogProfile.apps.any {
                        it.identity == target.identity && it.searchVisible
                    }
                }
        }
        is SearchExecutionTarget.Shortcut -> {
            val profile = profiles.state.value.profiles.singleOrNull { it.serial == target.identity.profile }
            profile != null && profile.kind != ProfileKind.PRIVATE &&
                profile.availability == ProfileAvailability.AVAILABLE &&
                shortcuts.state.value.availableShortcuts.any { it.target == target.identity && it.enabled }
        }
        is SearchExecutionTarget.Setting -> settings.isCallable(target)
        is SearchExecutionTarget.Contact -> profiles.state.value.profiles.any {
            it.serial == target.profile && it.kind != ProfileKind.PRIVATE &&
                it.availability == ProfileAvailability.AVAILABLE
        }
        is SearchExecutionTarget.File,
        is SearchExecutionTarget.Web,
        is SearchExecutionTarget.Information,
        -> true
    }

    private fun invalidateTargets() {
        synchronized(lock) {
            actions.entries.removeAll { !isCurrent(it.value.target) }
        }
        mutableInvalidations.tryEmit(Unit)
    }

    private fun settingCandidate(
        providerId: ContributionId,
        sessionId: Long,
        route: SearchSettingsRoute,
        label: String,
        packageName: PackageName?,
        fallbackRoute: SearchSettingsRoute? = null,
    ): Candidate? {
        if (route == SearchSettingsRoute.APPLICATION_DETAILS && packageName == null) return null
        val target = SearchExecutionTarget.Setting(route, packageName, fallbackRoute)
        val key = "setting:${route.name.lowercase()}:${packageName?.value.orEmpty()}"
        val descriptor = descriptor(providerId, sessionId, key, SearchResultKind.SETTING, null, false)
        return Candidate(
            ProviderResult(
                resultId(providerId, key),
                DisplayText.of(label),
                null,
                SearchResultKind.SETTING,
                SearchResultAction.Execute(descriptor.actionId),
                500,
            ),
            descriptor,
            target,
        )
    }

    private fun informationCandidate(
        providerId: ContributionId,
        sessionId: Long,
        label: String,
        target: SearchExecutionTarget,
    ): Candidate {
        val key = "information:${label.lowercase().replace(' ', '-')}"
        val descriptor = descriptor(providerId, sessionId, key, SearchResultKind.INFORMATION, null, false)
        return Candidate(
            ProviderResult(
                resultId(providerId, key),
                DisplayText.of(label),
                null,
                SearchResultKind.INFORMATION,
                SearchResultAction.Execute(descriptor.actionId),
                300,
            ),
            descriptor,
            target,
        )
    }

    private fun descriptor(
        providerId: ContributionId,
        sessionId: Long,
        key: String,
        kind: SearchResultKind,
        profile: ProfileSerial?,
        workBadged: Boolean,
    ) = ResolvedSearchTarget(
        actionId(providerId, sessionId, key),
        kind,
        SearchTargetKey.of(key),
        profile,
        null,
        workBadged,
        null,
    )

    private data class Candidate(
        val result: ProviderResult,
        val descriptor: ResolvedSearchTarget,
        val target: SearchExecutionTarget,
    )

    private data class ActionEntry(
        val providerId: ContributionId,
        val sessionId: Long,
        val generation: Long,
        val descriptor: ResolvedSearchTarget,
        val target: SearchExecutionTarget,
    )

    private data class SessionProviderKey(
        val sessionId: Long,
        val providerId: ContributionId,
    )

    private companion object {
        const val MAX_PREPARED_RESULTS = 1_000
        const val MAX_CANDIDATE_SCAN = 2_000

        fun targetKey(target: SearchExecutionTarget.App): String = with(target.identity) {
            "app:${profile.value}:${packageName.value}:${activityName.value}"
        }

        fun targetKey(target: SearchExecutionTarget.Shortcut): String = with(target.identity) {
            "shortcut:${profile.value}:${packageName.value}:${shortcutId.value}"
        }

        fun actionId(providerId: ContributionId, sessionId: Long, key: String): SearchActionId =
            SearchActionId.parse(
                "org.quicklauncher.search.action/a-${digest("${providerId.value}:$sessionId:$key")}",
            )

        fun resultId(providerId: ContributionId, key: String): SearchResultId =
            SearchProviderResultIds.create(providerId, "r-${digest(key)}")

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

        fun immutableResults(values: Collection<ProviderResult>): List<ProviderResult> =
            Collections.unmodifiableList(ArrayList(values))
    }
}

private object GrapheneSearchCatalog {
    private val REVIEWED_BUILD_IDS = setOf("CP2A.260805.005")

    fun isReviewedBuild(): Boolean =
        Build.VERSION.SDK_INT == 37 && Build.ID in REVIEWED_BUILD_IDS
}
